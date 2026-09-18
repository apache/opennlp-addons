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
import java.util.Set;
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
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Tests complete phone spans through annotation, masking and label replacement. */
class PhoneSpanTest {

  /**
   * A formatted phone number and its expected normalized value.
   *
   * @param text The complete original number.
   * @param normalized The digits, including an international plus sign if present.
   */
  private record PhoneCase(String text, String normalized) {
  }

  private static final List<PhoneCase> FORMATS = List.of(
      new PhoneCase("(415) 555-0123", "4155550123"),
      new PhoneCase("+1 (415) 555-0123", "+14155550123"),
      new PhoneCase("415 (5550123)", "4155550123"),
      new PhoneCase("+1 (4155550123)", "+14155550123"),
      new PhoneCase("(415 555 0123)", "4155550123"),
      new PhoneCase("+44 (20 7946 0958)", "+442079460958"),
      new PhoneCase("+683 (4002)", "+6834002"),
      new PhoneCase("1(4155550123)", "14155550123"),
      new PhoneCase("415555(0123)", "4155550123"),
      new PhoneCase("1 (4155550123)", "14155550123"));

  private static final String NEXT_PHONE = "(415) 555-0124";

  private final PiiExtractor extractor = new CursorPiiExtractor(Set.of(PiiMention.TYPE_PHONE));

  /**
   * Provides formatted numbers before and after ordinary and Unicode text.
   *
   * @return The number and surrounding text.
   */
  private static Stream<Arguments> surroundedNumbers() {
    return FORMATS.stream().flatMap(phone -> Stream.of("", "Call: ", "😀 ", "e\u0301 ")
        .flatMap(prefix -> Stream.of("", ".", "]", " 😀", ")")
            .map(suffix -> Arguments.of(phone, prefix, suffix))));
  }

  /**
   * Provides the complete phone examples.
   *
   * @return The formatted numbers and their normalized values.
   */
  private static Stream<PhoneCase> formats() {
    return FORMATS.stream();
  }

  /**
   * Checks exact spans, normalization and redaction through a public annotator.
   *
   * @param phone The complete formatted number.
   * @param prefix Text before the number.
   * @param suffix Text after the number.
   */
  @ParameterizedTest
  @MethodSource("surroundedNumbers")
  void testCompletePhoneSpan(PhoneCase phone, String prefix, String suffix) {
    final String text = prefix + phone.text() + suffix;
    final List<PiiMention> expected = List.of(mention(phone, prefix.length()));
    Assertions.assertEquals(expected, extractor.extract(text));
    Assertions.assertEquals(expected, extractor.extract(new StringBuilder(text)));

    final Document document = new PiiAnnotator(extractor).annotate(Document.of(text));
    Assertions.assertEquals(expected, document.get(PiiAnnotator.PII).stream()
        .map(annotation -> annotation.value()).toList());
    Assertions.assertEquals(prefix + "*".repeat(phone.text().length()) + suffix,
        Masker.mask(document, PiiAnnotator.PII, '*'));
    Assertions.assertEquals(prefix + "PHONE-1" + suffix,
        new Pseudonymizer().rewrite(document).text());
  }

  /**
   * Checks that a complete phone ends at the opening parenthesis of the next phone.
   *
   * @param phone The first number.
   */
  @ParameterizedTest
  @MethodSource("formats")
  void testAdjacentPhones(PhoneCase phone) {
    final String text = phone.text() + NEXT_PHONE;
    Assertions.assertEquals(List.of(mention(phone, 0),
        new PiiMention(new Span(phone.text().length(), text.length()),
            PiiMention.TYPE_PHONE, "4155550124")), extractor.extract(text));
  }

  /**
   * Checks that a rejected trailing digit group does not remove the closing parenthesis.
   *
   * @param phone The valid prefix number.
   */
  @ParameterizedTest
  @MethodSource("formats")
  void testTrailingGroupKeepsCompletePrefix(PhoneCase phone) {
    Assertions.assertEquals(List.of(mention(phone, 0)),
        extractor.extract(phone.text() + " 123456789012345678901234"));
  }

  /**
   * Checks that span handling does not bypass number-length and formatting checks.
   *
   * @param text The rejected candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {"+123", "415-555-012", "4155550123", "+999 (1234)",
      "123(456)", "+65 (123456)"})
  void testRejectedNumbers(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty());
  }

  /** Checks the manual example with a closing parenthesis at the end of the number. */
  @Test
  void testManualExample() {
    final String phone = "+1 (4155550123)";
    final Document document = new PiiAnnotator(PiiPacks.contact())
        .annotate(Document.of("Call " + phone + "."));

    Assertions.assertEquals(new Span(5, 5 + phone.length()),
        document.get(PiiAnnotator.PII).getFirst().span());
    Assertions.assertEquals("+14155550123",
        document.get(PiiAnnotator.PII).getFirst().value().normalized());
    Assertions.assertEquals("Call " + "*".repeat(phone.length()) + ".",
        Masker.mask(document, PiiAnnotator.PII, '*'));
    Assertions.assertEquals("Call PHONE-1.", new Pseudonymizer().rewrite(document).text());
  }

  /**
   * Checks one shared extractor with different phone forms and offsets in each worker.
   *
   * @throws Exception If a worker fails or exceeds the time limit.
   */
  @Test
  @Timeout(60)
  void testConcurrentSpans() throws Exception {
    final CountDownLatch ready = new CountDownLatch(8);
    final List<Callable<Void>> calls = new ArrayList<>();
    for (int worker = 0; worker < 8; worker++) {
      final String prefix = "😀 " + " ".repeat(worker);
      calls.add(() -> {
        ready.countDown();
        Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
        for (int run = 0; run < 16; run++) {
          for (final PhoneCase phone : FORMATS) {
            Assertions.assertEquals(List.of(mention(phone, prefix.length())),
                extractor.extract(prefix + phone.text() + "."));
          }
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

  /**
   * Builds the expected mention without using scanner output.
   *
   * @param phone The original number and expected normalized value.
   * @param start The first offset of the number.
   * @return The complete phone mention.
   */
  private PiiMention mention(PhoneCase phone, int start) {
    return new PiiMention(new Span(start, start + phone.text().length()),
        PiiMention.TYPE_PHONE, phone.normalized());
  }
}
