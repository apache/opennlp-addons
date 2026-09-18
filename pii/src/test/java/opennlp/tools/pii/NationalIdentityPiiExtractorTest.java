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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Checks grouped national identifiers, numeric ranges and original-text spans. */
class NationalIdentityPiiExtractorTest {

  private enum Family {
    SSN(PiiMention.TYPE_US_SSN, "123456789", "123-45-6789", List.of(3, 2, 4)),
    ITIN(PiiMention.TYPE_US_ITIN, "900701234", "900-70-1234", List.of(3, 2, 4)),
    NHS(PiiMention.TYPE_UK_NHS, "9434765919", "9434765919", List.of(3, 3, 4)),
    TAX(PiiMention.TYPE_DE_STEUER_ID, "65929970489", "65929970489", List.of(2, 3, 3, 3));

    private final String type;
    private final String digits;
    private final String normalized;
    private final List<Integer> groups;
    private final PiiExtractor extractor;

    /**
     * Configures a detector restricted to one identifier type.
     *
     * @param type The output type.
     * @param digits The compact sample.
     * @param normalized The expected normalized form.
     * @param groups The digit counts of each group.
     */
    Family(String type, String digits, String normalized, List<Integer> groups) {
      this.type = type;
      this.digits = digits;
      this.normalized = normalized;
      this.groups = groups;
      this.extractor = type.equals(PiiMention.TYPE_US_SSN) || type.equals(PiiMention.TYPE_US_ITIN)
          ? new UsIdentityPiiExtractor(Set.of(type)) : new EuIdentityPiiExtractor(Set.of(type));
    }
  }

  /**
   * Provides all ASCII separators and selected non-ASCII characters.
   *
   * @return The identifier family and delimiter code point.
   */
  private static Stream<Arguments> separators() {
    return Stream.of(Family.values()).flatMap(family -> IntStream.concat(IntStream.range(0, 128),
        IntStream.of(0xa0, 0x2007, 0x202f, 0x2010, 0x2212, 0x1f600, 0x10400))
        .mapToObj(codePoint -> Arguments.of(family, codePoint)));
  }

  /**
   * Provides every combination of NUL, space and hyphen between numeric groups.
   *
   * @return The family, separator sequence and expected acceptance.
   */
  private static Stream<Arguments> mixedSeparators() {
    final List<Arguments> cases = new ArrayList<>();
    final char[] alphabet = {0, ' ', '-'};
    for (final Family family : Family.values()) {
      final int count = family.groups.size() - 1;
      final int combinations = count == 2 ? 9 : 27;
      for (int code = 0; code < combinations; code++) {
        final StringBuilder separators = new StringBuilder(count);
        int remaining = code;
        for (int position = 0; position < count; position++) {
          separators.append(alphabet[remaining % alphabet.length]);
          remaining /= alphabet.length;
        }
        final String value = separators.toString();
        cases.add(Arguments.of(family, value,
            value.equals(" ".repeat(count)) || value.equals("-".repeat(count))));
      }
    }
    return cases.stream();
  }

  /**
   * Provides word and punctuation boundaries for each grouped form.
   *
   * @return The family, preceding text, following text and expected acceptance.
   */
  private static Stream<Arguments> boundaries() {
    final List<Arguments> cases = new ArrayList<>();
    for (final Family family : Family.values()) {
      for (final String word : List.of("a", "9", "é", "中", "𐐀", "١", "𝟙")) {
        cases.add(Arguments.of(family, word, "", false));
        cases.add(Arguments.of(family, "", word, false));
      }
      for (final String prefix : List.of("", " ", "😀", "(", "é ")) {
        cases.add(Arguments.of(family, prefix, ".", true));
      }
      for (final String continuation : List.of(".1", ",1", "-1")) {
        cases.add(Arguments.of(family, "", continuation, false));
      }
    }
    return cases.stream();
  }

  /**
   * Checks that only a single ASCII space or hyphen separates numeric groups.
   *
   * @param family The identifier family.
   * @param codePoint The proposed delimiter.
   */
  @ParameterizedTest
  @MethodSource("separators")
  void testSeparator(Family family, int codePoint) {
    final String delimiter = new String(Character.toChars(codePoint));
    final String number = grouped(family, delimiter);
    assertForm(family, number, codePoint == ' ' || codePoint == '-', "😀 ", ".");
  }

  /**
   * Checks delimiter consistency, including a NUL before a permitted delimiter.
   *
   * @param family The identifier family.
   * @param separators The delimiter at each group boundary.
   * @param accepted Whether every delimiter is the same permitted character.
   */
  @ParameterizedTest
  @MethodSource("mixedSeparators")
  void testMixedSeparators(Family family, String separators, boolean accepted) {
    assertForm(family, grouped(family,
        separators.chars().mapToObj(c -> Character.toString((char) c)).toList()),
        accepted, "", "");
  }

  /**
   * Checks Unicode word boundaries, numeric continuations and exact UTF-16 offsets.
   *
   * @param family The identifier family.
   * @param prefix The preceding text.
   * @param suffix The following text.
   * @param accepted Whether the candidate is isolated.
   */
  @ParameterizedTest
  @MethodSource("boundaries")
  void testBoundaries(Family family, String prefix, String suffix, boolean accepted) {
    assertForm(family, grouped(family, "-"), accepted, prefix, suffix);
  }

  /**
   * Checks compact acceptance and recovery after a malformed grouped candidate.
   *
   * @param family The identifier family.
   */
  @ParameterizedTest
  @EnumSource(Family.class)
  void testCompactAndRecovery(Family family) {
    final boolean compact = family == Family.NHS || family == Family.TAX;
    assertForm(family, family.digits, compact, "", "");
    final String valid = grouped(family, " ");
    final String invalid = grouped(family, "\t");
    assertForm(family, valid, true, invalid + "; 😀 ", ".");
  }

  /**
   * Provides every two-digit US group at the SSN/ITIN area boundary.
   *
   * @return The area and group values.
   */
  private static Stream<Arguments> usGroups() {
    return IntStream.of(899, 900, 999).boxed().flatMap(area ->
        IntStream.range(0, 100).mapToObj(group -> Arguments.of(area, group)));
  }

  /**
   * Checks all middle-group values without conflating SSNs and ITINs.
   *
   * @param area The leading three digits.
   * @param group The middle two digits.
   */
  @ParameterizedTest
  @MethodSource("usGroups")
  void testUsGroups(int area, int group) {
    final String text = area + "-" + (group < 10 ? "0" : "") + group + "-1234";
    final boolean itinGroup = group >= 50 && group <= 65 || group >= 70 && group <= 88
        || group >= 90 && group <= 92 || group >= 94;
    final String type = area < 900 && group > 0 ? Family.SSN.type
        : area >= 900 && itinGroup ? Family.ITIN.type : null;
    final List<PiiMention> expected = type == null ? List.of()
        : List.of(new PiiMention(new Span(0, text.length()), type, text));
    Assertions.assertEquals(expected, new UsIdentityPiiExtractor().extract(text));
  }

  /** Checks the manual's opt-in document example and masks the original grouped spans. */
  @Test
  void testDocumentExample() {
    final Document result = new PiiAnnotator(PiiPacks.euIdentity())
        .annotate(Document.of("NHS: 943 476 5919; IdNr: 65-929-970-489."));
    Assertions.assertEquals(List.of(Family.NHS.type, Family.TAX.type),
        result.get(PiiAnnotator.PII).stream().map(a -> a.value().type()).toList());
    Assertions.assertEquals("NHS: ************; IdNr: **************.",
        Masker.mask(result, PiiAnnotator.PII, '*'));
  }

  /**
   * Checks shared detectors using different input offsets from concurrent requests.
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
            assertForm(family, grouped(family, "-"), true, prefix, ".");
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
   * Builds a grouped form using the same delimiter at every boundary.
   *
   * @param family The identifier family.
   * @param separator The repeated delimiter.
   * @return The grouped text.
   */
  private String grouped(Family family, String separator) {
    return grouped(family, Collections.nCopies(family.groups.size() - 1, separator));
  }

  /**
   * Builds a grouped form from an identifier's digits.
   *
   * @param family The identifier family.
   * @param separators One delimiter per group boundary.
   * @return The grouped text.
   */
  private String grouped(Family family, List<String> separators) {
    final StringBuilder text = new StringBuilder();
    int offset = 0;
    for (int group = 0; group < family.groups.size(); group++) {
      if (group > 0) {
        text.append(separators.get(group - 1));
      }
      final int end = offset + family.groups.get(group);
      text.append(family.digits, offset, end);
      offset = end;
    }
    return text.toString();
  }

  /**
   * Checks recognition, normalization and exact original-text offsets.
   *
   * @param family The identifier family.
   * @param number The candidate text.
   * @param accepted Whether the candidate should match.
   * @param prefix The preceding text.
   * @param suffix The following text.
   */
  private void assertForm(Family family, String number, boolean accepted, String prefix, String suffix) {
    final List<PiiMention> expected = accepted ? List.of(new PiiMention(
        new Span(prefix.length(), prefix.length() + number.length()), family.type, family.normalized))
        : List.of();
    Assertions.assertEquals(expected, family.extractor.extract(prefix + number + suffix));
  }
}
