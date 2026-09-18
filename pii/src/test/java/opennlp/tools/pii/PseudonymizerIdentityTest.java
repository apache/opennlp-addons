/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package opennlp.tools.pii;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Tests identity numbering for custom types with the same displayed prefix. */
class PseudonymizerIdentityTest {

  /**
   * Provides case variants, independent prefix groups and repeated identities.
   *
   * @return Types, normalized values, expected labels and wrapper options.
   */
  private static Stream<Arguments> identities() {
    final List<Arguments> cases = new ArrayList<>();
    final List<List<String>> types = List.of(
        List.of("id", "ID", "id", "Id", "ID", "id"),
        List.of("id", "Id", "ID", "iD", "id"),
        List.of("email", "EMAIL", "phone", "PHONE", "email"),
        List.of("a-1", "A-1", "a", "A", "a-1"),
        List.of("éid", "éID", "éid", "éID"));
    final List<List<String>> values = List.of(
        List.of("a", "a", "b", "a", "a", "a"),
        List.of("a", "a", "a", "a", "a"),
        List.of("a", "b", "a", "b", "a"),
        List.of("a", "b", "c", "d", "a"),
        List.of("a", "b", "a", "b"));
    final List<List<String>> labels = List.of(
        List.of("ID-1", "ID-2", "ID-3", "ID-4", "ID-2", "ID-1"),
        List.of("ID-1", "ID-2", "ID-3", "ID-4", "ID-1"),
        List.of("EMAIL-1", "EMAIL-2", "PHONE-1", "PHONE-2", "EMAIL-1"),
        List.of("A-1-1", "A-1-2", "A-1", "A-2", "A-1-1"),
        List.of("éID-1", "éID-2", "éID-1", "éID-2"));
    for (int i = 0; i < types.size(); i++) {
      for (final boolean wrapped : new boolean[] {false, true}) {
        for (final boolean document : new boolean[] {false, true}) {
          cases.add(Arguments.of(types.get(i), values.get(i), labels.get(i), wrapped, document));
        }
      }
    }
    return cases.stream();
  }

  /**
   * Checks exact labels, stable repeats, type metadata and numbering reset.
   *
   * @param types The custom type names.
   * @param values The normalized values.
   * @param labels The expected labels without wrappers.
   * @param wrapped Whether labels use brackets.
   * @param document Whether to use the document overload.
   */
  @ParameterizedTest
  @MethodSource("identities")
  void testCustomTypeIdentities(List<String> types, List<String> values, List<String> labels,
      boolean wrapped, boolean document) {
    final List<PiiMention> mentions = new ArrayList<>();
    for (int i = 0; i < types.size(); i++) {
      mentions.add(new PiiMention(new Span(2 * i, 2 * i + 1), types.get(i), values.get(i)));
    }
    final String text = "x ".repeat(types.size()).stripTrailing();
    final Pseudonymizer pseudonymizer = wrapped ? new Pseudonymizer("[", "]") : new Pseudonymizer();
    final List<String> expected = labels.stream().map(label -> wrapped ? '[' + label + ']' : label).toList();
    final Document annotated = Document.of(text).with(PiiAnnotator.PII,
        mentions.reversed().stream().map(mention -> new Annotation<>(mention.span(), mention)).toList());

    for (int run = 0; run < 2; run++) {
      final PiiRewrite rewrite = document ? pseudonymizer.rewrite(annotated)
          : pseudonymizer.rewrite(text, mentions.reversed());
      Assertions.assertEquals(String.join(" ", expected), rewrite.text());
      Assertions.assertEquals(types, rewrite.mentions().stream().map(PiiMention::type).toList());
      Assertions.assertEquals(expected, rewrite.mentions().stream().map(PiiMention::normalized).toList());
      for (final PiiMention mention : rewrite.mentions()) {
        Assertions.assertEquals(mention.normalized(),
            rewrite.text().substring(mention.span().getStart(), mention.span().getEnd()));
      }
    }
  }

  /**
   * Checks that concurrent rewrites do not share identity counters.
   *
   * @throws Exception If a worker fails or does not finish within the time limit.
   */
  @Test
  @Timeout(60)
  void testConcurrentIdentityNumbering() throws Exception {
    final Pseudonymizer pseudonymizer = new Pseudonymizer("[", "]");
    final CountDownLatch ready = new CountDownLatch(8);
    final List<Callable<Void>> calls = new ArrayList<>();
    for (int worker = 0; worker < 8; worker++) {
      final String value = "value-" + worker;
      final List<PiiMention> mentions = List.of(
          new PiiMention(new Span(0, 1), "id", value),
          new PiiMention(new Span(2, 3), "ID", value),
          new PiiMention(new Span(4, 5), "id", value));
      final Document document = Document.of("x x x").with(PiiAnnotator.PII,
          mentions.stream().map(mention -> new Annotation<>(mention.span(), mention)).toList());
      calls.add(() -> {
        ready.countDown();
        Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
        for (int run = 0; run < 32; run++) {
          Assertions.assertEquals("[ID-1] [ID-2] [ID-1]", pseudonymizer.rewrite(document).text());
          Assertions.assertEquals("[ID-1] [ID-2] [ID-1]",
              pseudonymizer.rewrite(document.text(), mentions).text());
        }
        return null;
      });
    }
    try (var executor = Executors.newFixedThreadPool(8)) {
      for (final var result : executor.invokeAll(calls, 30, TimeUnit.SECONDS)) {
        result.get();
      }
    }
  }
}
