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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Tests agreement between document annotation offsets and PII mention offsets. */
class PiiLayerTest {

  private static final String TEXT = "prefix jane@example.com suffix";
  private static final String ADDRESS = "jane@example.com";
  private static final Span ADDRESS_SPAN = new Span(7, 23);
  private static final Pseudonymizer PSEUDONYMIZER = new Pseudonymizer();
  private static final HmacTokenizer TOKENIZER = new HmacTokenizer(
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

  private enum Consumer {
    PSEUDONYMIZER, TOKENIZER, AUDIT
  }

  /**
   * Provides incorrect payload offsets for each document-taking consumer.
   *
   * @return A mismatched span and the consumer.
   */
  private static Stream<Arguments> mismatchedSpans() {
    final List<Arguments> cases = new ArrayList<>();
    for (final Span span : List.of(new Span(0, 6), new Span(0, 23), new Span(8, 23),
        new Span(7, 22), new Span(7, 24), new Span(7, 7), new Span(0, TEXT.length()),
        new Span(7, TEXT.length() + 1))) {
      for (final Consumer consumer : Consumer.values()) {
        cases.add(Arguments.of(span, consumer));
      }
    }
    return cases.stream();
  }

  /**
   * Rejects offset mismatches before generating rewritten text or an audit report.
   *
   * @param span The incorrect payload span.
   * @param consumer The document consumer.
   */
  @ParameterizedTest
  @MethodSource("mismatchedSpans")
  void testRejectsMismatchedOffsets(Span span, Consumer consumer) {
    final PiiMention mention = new PiiMention(span, PiiMention.TYPE_EMAIL, ADDRESS);
    final Document document = Document.of(TEXT).with(PiiAnnotator.PII,
        List.of(new Annotation<>(ADDRESS_SPAN, mention)));

    final IllegalArgumentException failure = Assertions.assertThrows(
        IllegalArgumentException.class, () -> consume(document, consumer));
    Assertions.assertEquals("PII mention offsets must match annotation offsets", failure.getMessage());
    Assertions.assertFalse(failure.getMessage().contains(ADDRESS));
    Assertions.assertEquals(TEXT, document.text());
  }

  /**
   * Checks offsets independently of optional span type and confidence metadata.
   *
   * @param consumer The document consumer.
   */
  @ParameterizedTest
  @EnumSource(Consumer.class)
  void testAcceptsMatchingOffsetsWithDifferentSpanMetadata(Consumer consumer) {
    final PiiMention mention = new PiiMention(new Span(7, 23, "inner", 0.5),
        PiiMention.TYPE_EMAIL, ADDRESS);
    final Document document = Document.of(TEXT).with(PiiAnnotator.PII,
        List.of(new Annotation<>(new Span(7, 23, "outer", 0.75), mention)));

    final Object result = consume(document, consumer);
    if (result instanceof PiiRewrite rewrite) {
      final String label = consumer == Consumer.TOKENIZER
          ? TOKENIZER.token(mention) : "EMAIL-1";
      Assertions.assertEquals("prefix " + label + " suffix", rewrite.text());
    } else {
      final PiiAuditReport report = (PiiAuditReport) result;
      Assertions.assertEquals(1, report.total());
      Assertions.assertEquals(List.of(TOKENIZER.token(mention)), report.samples(PiiMention.TYPE_EMAIL));
    }
  }

  /**
   * Checks both an empty PII layer and a matching layer supplied by the annotator.
   *
   * @param consumer The document consumer.
   */
  @ParameterizedTest
  @EnumSource(Consumer.class)
  void testAcceptsExtractorAndEmptyLayers(Consumer consumer) {
    final PiiAnnotator annotator = new PiiAnnotator(new CursorPiiExtractor());
    Assertions.assertDoesNotThrow(() -> consume(annotator.annotate(Document.of(TEXT)), consumer));
    Assertions.assertDoesNotThrow(() -> consume(
        Document.of(TEXT).with(PiiAnnotator.PII, List.of()), consumer));
  }

  /** Checks that mention-list overloads continue to use their explicit source offsets. */
  @Test
  void testListOverloadsUseMentionSpans() {
    final PiiMention mention = new PiiMention(ADDRESS_SPAN, PiiMention.TYPE_EMAIL, ADDRESS);
    Assertions.assertEquals("prefix EMAIL-1 suffix", PSEUDONYMIZER.rewrite(TEXT, List.of(mention)).text());
    Assertions.assertEquals("prefix " + TOKENIZER.token(mention) + " suffix",
        TOKENIZER.rewrite(TEXT, List.of(mention)).text());
    Assertions.assertEquals(1, PiiAuditReport.of(List.of(mention), TOKENIZER).total());
  }

  /**
   * Runs one document-taking PII consumer.
   *
   * @param document The document to consume.
   * @param consumer The selected consumer.
   * @return The rewrite or report.
   */
  private Object consume(Document document, Consumer consumer) {
    return switch (consumer) {
      case PSEUDONYMIZER -> PSEUDONYMIZER.rewrite(document);
      case TOKENIZER -> TOKENIZER.rewrite(document);
      case AUDIT -> PiiAuditReport.of(document, TOKENIZER);
    };
  }
}
