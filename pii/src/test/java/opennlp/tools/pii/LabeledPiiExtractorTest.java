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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Tests label isolation, numeric grouping and exact offsets for SIN and IMEI extraction. */
class LabeledPiiExtractorTest {

  private enum Family {
    SIN(new CaIdentityPiiExtractor(), PiiMention.TYPE_CA_SIN, "046454286",
        List.of("SIN", "Social Insurance Number", "Social Insurance No.")),
    IMEI(new DevicePiiExtractor(), PiiMention.TYPE_IMEI, "490154203237518", List.of("IMEI"));

    private final PiiExtractor extractor;
    private final String type;
    private final String digits;
    private final List<String> labels;

    /**
     * Configures a detector and its accepted label samples.
     *
     * @param extractor The shared detector.
     * @param type The expected mention type.
     * @param digits A checksum-valid sample.
     * @param labels The accepted labels.
     */
    Family(PiiExtractor extractor, String type, String digits, List<String> labels) {
      this.extractor = extractor;
      this.type = type;
      this.digits = digits;
      this.labels = labels;
    }
  }

  /**
   * Provides word characters and valid separators before each label.
   *
   * @return The detector family, label, preceding text and expected acceptance.
   */
  private static Stream<Arguments> labelBoundaries() {
    final List<Arguments> cases = new ArrayList<>();
    for (final Family family : Family.values()) {
      for (final String label : family.labels) {
        for (final String prefix : List.of("x", "9", "é", "中", "𐐀", "𝕒", "١", "𝟙")) {
          cases.add(Arguments.of(family, label, prefix, false));
        }
        for (final String prefix : List.of("", " ", "(", "😀", "é ", "𝕒 ")) {
          cases.add(Arguments.of(family, label, prefix, true));
        }
      }
    }
    return cases.stream();
  }

  /**
   * Provides compact SINs, every one- or two-separator placement and invalid separators.
   *
   * @return The number spelling and whether it has the documented grouping.
   */
  private static Stream<Arguments> sinGrouping() {
    final List<Arguments> cases = new ArrayList<>();
    final String value = Family.SIN.digits;
    cases.add(Arguments.of(value, true));
    for (final String separator : List.of(" ", "-")) {
      for (int first = 1; first < value.length(); first++) {
        cases.add(Arguments.of(value.substring(0, first) + separator + value.substring(first), false));
        for (int second = first + 1; second < value.length(); second++) {
          cases.add(Arguments.of(value.substring(0, first) + separator
              + value.substring(first, second) + separator + value.substring(second),
              first == 3 && second == 6));
        }
      }
    }
    for (final String valueWithInvalidSeparators : List.of("046 454-286", "046-454 286",
        "046  454 286", "046--454-286", "046\t454\t286", "046\u00a0454\u00a0286")) {
      cases.add(Arguments.of(valueWithInvalidSeparators, false));
    }
    return cases.stream();
  }

  /**
   * Provides IMEIs with a single separator at each possible position.
   *
   * @return The formatted IMEI.
   */
  private static Stream<String> imeiGrouping() {
    final List<String> cases = new ArrayList<>();
    final String value = Family.IMEI.digits;
    cases.add(value);
    cases.add("49-015420-3237518");
    cases.add("49015 42032 37518");
    for (final String separator : List.of(" ", "-")) {
      for (int position = 1; position < value.length(); position++) {
        cases.add(value.substring(0, position) + separator + value.substring(position));
      }
    }
    return cases.stream();
  }

  /**
   * Rejects labels inside Unicode words while retaining valid offsets after separators.
   *
   * @param family The detector family.
   * @param label The label text.
   * @param prefix The text immediately before the label.
   * @param accepted Whether the label is isolated.
   */
  @ParameterizedTest
  @MethodSource("labelBoundaries")
  void testLabelBoundary(Family family, String label, String prefix, boolean accepted) {
    final String beforeNumber = prefix + label + ": ";
    final String text = beforeNumber + family.digits + ".";
    final List<PiiMention> expected = accepted
        ? List.of(new PiiMention(new Span(beforeNumber.length(), text.length() - 1),
            family.type, family.digits)) : List.of();

    Assertions.assertEquals(expected, family.extractor.extract(text));
  }

  /**
   * Checks compact SINs and exactly three equal groups with a consistent separator.
   *
   * @param number The numeric text.
   * @param accepted Whether the numeric form is supported.
   */
  @ParameterizedTest
  @MethodSource("sinGrouping")
  void testSinGrouping(String number, boolean accepted) {
    assertNumericForm(Family.SIN, number, accepted);
  }

  /**
   * Keeps the IMEI detector's variable group lengths.
   *
   * @param number The formatted IMEI.
   */
  @ParameterizedTest
  @MethodSource("imeiGrouping")
  void testImeiGrouping(String number) {
    assertNumericForm(Family.IMEI, number, true);
  }

  /**
   * Rejects mixed, repeated and non-space whitespace separators inside an IMEI.
   *
   * @param number The invalid numeric form.
   */
  @ParameterizedTest
  @ValueSource(strings = {"49-015420 3237518", "49 015420-3237518", "49  015420 3237518",
      "49--015420-3237518", "49\t015420\t3237518", "49\u00a0015420\u00a03237518"})
  void testInvalidImeiGrouping(String number) {
    assertNumericForm(Family.IMEI, number, false);
  }

  /**
   * Checks a numeric form, its normalized digits and its exact source span.
   *
   * @param family The detector family.
   * @param number The numeric form.
   * @param accepted Whether the detector should accept it.
   */
  private void assertNumericForm(Family family, String number, boolean accepted) {
    final String prefix = family.labels.getFirst() + ": ";
    final List<PiiMention> expected = accepted
        ? List.of(new PiiMention(new Span(prefix.length(), prefix.length() + number.length()),
            family.type, family.digits)) : List.of();
    Assertions.assertEquals(expected, family.extractor.extract(prefix + number + "."));
  }

  /**
   * Checks every one-digit increment and retains checksum-only treatment of zero values.
   *
   * @param family The detector family.
   */
  @ParameterizedTest
  @EnumSource(Family.class)
  void testChecksum(Family family) {
    assertNumericForm(family, family.digits, true);
    for (int index = 0; index < family.digits.length(); index++) {
      final StringBuilder changed = new StringBuilder(family.digits);
      changed.setCharAt(index, (char) ('0' + (changed.charAt(index) - '0' + 1) % 10));
      assertNumericForm(family, changed.toString(), false);
    }
    final String zeros = "0".repeat(family.digits.length());
    final List<PiiMention> mentions = family.extractor.extract(family.labels.getFirst() + ": " + zeros);
    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(zeros, mentions.getFirst().normalized());
  }

  /** Checks the manual's document example and original-text masking. */
  @Test
  void testDocumentExample() {
    final PiiExtractor combined = new CompositePiiExtractor(PiiPacks.caIdentity(), PiiPacks.device());
    final Document result = new PiiAnnotator(combined)
        .annotate(Document.of("SIN: 046 454 286; IMEI: 490154203237518."));

    Assertions.assertEquals(List.of(Family.SIN.type, Family.IMEI.type),
        result.get(PiiAnnotator.PII).stream().map(annotation -> annotation.value().type()).toList());
    Assertions.assertEquals("SIN: ***********; IMEI: ***************.",
        Masker.mask(result, PiiAnnotator.PII, '*'));
  }

  /** Checks ASCII-only case comparison after a supplementary character. */
  @Test
  void testAsciiLabelComparison() {
    Assertions.assertTrue(Ascii.equalsIgnoreCase("😀sIn", 2, "SIN"));
    Assertions.assertTrue(Ascii.equalsIgnoreCase("iMeI", 0, "imei"));
    Assertions.assertFalse(Ascii.equalsIgnoreCase("sİn", 0, "sin"));
    Assertions.assertFalse(Ascii.equalsIgnoreCase("ımei", 0, "imei"));
  }

  /**
   * Checks shared detectors with different offsets in concurrent requests.
   *
   * @throws Exception If a worker fails or does not finish in time.
   */
  @Test
  @Timeout(60)
  void testConcurrentExtraction() throws Exception {
    final CountDownLatch ready = new CountDownLatch(8);
    final List<Callable<Void>> calls = new ArrayList<>();
    for (int worker = 0; worker < 8; worker++) {
      final String prefix = "😀".repeat(worker) + " ";
      calls.add(() -> {
        ready.countDown();
        Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
        for (int run = 0; run < 32; run++) {
          for (final Family family : Family.values()) {
            final String beforeNumber = prefix + family.labels.getFirst() + ": ";
            Assertions.assertEquals(List.of(new PiiMention(new Span(beforeNumber.length(),
                beforeNumber.length() + family.digits.length()), family.type, family.digits)),
                family.extractor.extract(beforeNumber + family.digits));
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
}
