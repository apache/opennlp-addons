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
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Checks the Gambia and Zimbabwe lengths in the bundled phone metadata. */
class PhoneMetadataLengthsTest {

  private static final String PREFIX = "😀 Call: ";
  private static final String SUFFIX = ".";

  private final PiiExtractor extractor = new CursorPiiExtractor(Set.of(PiiMention.TYPE_PHONE));

  /**
   * Provides lengths through the international limit, with and without a code separator.
   *
   * @return The calling code, national length, separator and expected acceptance.
   */
  private static Stream<Arguments> nationalLengths() {
    return Stream.of("220", "263").flatMap(code -> IntStream.rangeClosed(1, 12)
        .boxed().flatMap(length -> Stream.of("", " ").map(separator ->
            Arguments.of(code, length, separator,
                (code.equals("220") ? Set.of(7, 9) : Set.of(7, 9, 10)).contains(length)))));
  }

  /**
   * Checks exact spans, normalized digits and masking for synthetic national numbers.
   *
   * @param code The calling code.
   * @param length The count of national digits.
   * @param separator The text between the calling code and national digits.
   * @param accepted Whether the metadata assigns this national length.
   */
  @ParameterizedTest
  @MethodSource("nationalLengths")
  void testNationalLength(String code, int length, String separator, boolean accepted) {
    final String national = "2".repeat(length);
    final String phone = "+" + code + separator + national;
    final String text = PREFIX + phone + SUFFIX;
    final List<PiiMention> expected = accepted
        ? List.of(new PiiMention(new Span(PREFIX.length(), PREFIX.length() + phone.length()),
            PiiMention.TYPE_PHONE, "+" + code + national)) : List.of();

    Assertions.assertEquals(accepted, PhoneNumberLengths.plausibleInternational(code + national));
    Assertions.assertEquals(expected, extractor.extract(text));
    Assertions.assertEquals(expected, extractor.extract(new StringBuilder(text)));
    final Document document = new PiiAnnotator(extractor).annotate(Document.of(text));
    Assertions.assertEquals(accepted ? PREFIX + "*".repeat(phone.length()) + SUFFIX : text,
        Masker.mask(document, PiiAnnotator.PII, '*'));
  }
}
