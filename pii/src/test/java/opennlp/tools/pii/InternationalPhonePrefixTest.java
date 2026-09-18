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

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Tests calling-code boundaries without changing national number-length rules. */
class InternationalPhonePrefixTest {

  /**
   * A calling code and synthetic national digits accepted by the length table.
   *
   * @param code The canonical calling code.
   * @param national The national part, without formatting.
   */
  private record NumberCase(String code, String national) {
  }

  private static final List<NumberCase> NUMBERS = List.of(
      new NumberCase("1", "4155550123"),
      new NumberCase("7", "1234567890"),
      new NumberCase("20", "1234567890"),
      new NumberCase("44", "2079460958"),
      new NumberCase("49", "1234567890"),
      new NumberCase("65", "12345678"),
      new NumberCase("299", "123456"),
      new NumberCase("683", "4002"));

  private final PiiExtractor extractor = new CursorPiiExtractor(Set.of(PiiMention.TYPE_PHONE));

  /**
   * Provides canonical and zero-prefixed numbers in compact and spaced forms.
   *
   * @return The number, added zero count, internal separator and surrounding text.
   */
  private static Stream<Arguments> examples() {
    return NUMBERS.stream().flatMap(number -> Stream.of(0, 1, 2).flatMap(zeros ->
        Stream.of("", " ").flatMap(separator -> Stream.of("", "😀 Call: ").flatMap(prefix ->
            Stream.of("", ".").map(suffix ->
                Arguments.of(number, zeros, separator, prefix, suffix))))));
  }

  /**
   * Checks that extraction and masking require the code's original decimal digits.
   *
   * @param number The code and national digits.
   * @param zeros The count of zeros prepended to the code.
   * @param separator The text between the code and national digits.
   * @param prefix The text before the number.
   * @param suffix The text after the number.
   */
  @ParameterizedTest
  @MethodSource("examples")
  void testCallingCodePrefix(NumberCase number, int zeros, String separator,
      String prefix, String suffix) {
    final String phone = "+" + "0".repeat(zeros) + number.code() + separator + number.national();
    final String text = prefix + phone + suffix;
    final List<PiiMention> expected = zeros == 0
        ? List.of(new PiiMention(new Span(prefix.length(), prefix.length() + phone.length()),
            PiiMention.TYPE_PHONE, "+" + number.code() + number.national())) : List.of();

    Assertions.assertEquals(expected, extractor.extract(text));
    Assertions.assertEquals(expected, extractor.extract(new StringBuilder(text)));
    final Document document = new PiiAnnotator(extractor).annotate(Document.of(text));
    Assertions.assertEquals(zeros == 0 ? prefix + "*".repeat(phone.length()) + suffix : text,
        Masker.mask(document, PiiAnnotator.PII, '*'));
  }

  /**
   * Checks zero prefixes for every code and national length within the 15-digit limit.
   *
   * @param zeros The count of prepended zeros.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void testZeroPrefixCannotReuseAnAssignedCode(int zeros) {
    int accepted = 0;
    for (int code = 1; code < 1000; code++) {
      final String prefix = Integer.toString(code);
      for (int length = 1; length <= 15 - prefix.length(); length++) {
        final String digits = prefix + "2".repeat(length);
        if (PhoneNumberLengths.plausibleInternational(digits)) {
          accepted++;
          final String padded = "0".repeat(zeros) + digits;
          Assertions.assertFalse(PhoneNumberLengths.plausibleInternational(padded), padded);
        }
      }
    }
    Assertions.assertTrue(accepted > 200, "the comparison must include assigned codes");
  }

  /**
   * Checks that zeros after the calling code retain their original position.
   *
   * @param digits The complete digits of the length-plausible number.
   */
  @ParameterizedTest
  @ValueSource(strings = {"390212345678", "441020304050", "10000000000"})
  void testNationalZerosArePreserved(String digits) {
    Assertions.assertTrue(PhoneNumberLengths.plausibleInternational(digits));
    Assertions.assertEquals(List.of(new PiiMention(new Span(0, digits.length() + 1),
        PiiMention.TYPE_PHONE, "+" + digits)), extractor.extract("+" + digits));
  }

  /** Checks the manual example with a rejected prefix and a separate accepted number. */
  @Test
  void testManualExample() {
    final Document phones = new PiiAnnotator(PiiPacks.contact())
        .annotate(Document.of("Invalid +014155550123; call +14155550123."));

    Assertions.assertEquals(1, phones.get(PiiAnnotator.PII).size());
    Assertions.assertEquals("+14155550123", phones.get(PiiAnnotator.PII).getFirst().value().normalized());
    Assertions.assertEquals("Invalid +014155550123; call ************.",
        Masker.mask(phones, PiiAnnotator.PII, '*'));
  }
}
