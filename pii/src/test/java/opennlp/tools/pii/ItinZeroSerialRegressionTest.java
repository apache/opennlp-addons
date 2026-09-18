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
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Checks the ITIN zero-serial boundary and the SSN exclusion. */
class ItinZeroSerialRegressionTest {

  /**
   * Supplies assigned ITIN group values.
   *
   * @return The assigned group values.
   */
  private static IntStream itinGroups() {
    return IntStream.concat(
        IntStream.concat(IntStream.rangeClosed(50, 65), IntStream.rangeClosed(70, 88)),
        IntStream.concat(IntStream.rangeClosed(90, 92), IntStream.rangeClosed(94, 99)));
  }

  /**
   * Supplies zero-serial ITINs across assigned groups and area limits.
   *
   * @return The valid candidates.
   */
  private static Stream<String> zeroSerialItins() {
    final Stream<String> assignedGroups =
        itinGroups().mapToObj(group -> candidate(912, group, "0000"));
    final Stream<String> boundaryAreas = IntStream.of(900, 999).boxed().flatMap(area ->
        IntStream.of(50, 65, 70, 88, 90, 92, 94, 99)
            .mapToObj(group -> candidate(area, group, "0000")));
    return Stream.concat(assignedGroups, boundaryAreas);
  }

  /**
   * Supplies group values adjacent to the assigned ITIN ranges.
   *
   * @return The invalid candidates.
   */
  private static Stream<String> adjacentInvalidGroups() {
    return IntStream.of(900, 999).boxed().flatMap(area ->
        IntStream.of(49, 66, 69, 89, 93)
            .mapToObj(group -> candidate(area, group, "0000")));
  }

  /**
   * Supplies supported input implementations with a supplementary-code-point prefix.
   *
   * @return The input values.
   */
  private static Stream<Arguments> inputImplementations() {
    final String text = "😀 ITIN 900 50 0000.";
    return Stream.of(Arguments.of(text), Arguments.of(new StringBuilder(text)));
  }

  /**
   * Supplies public packs that include United States identity detection.
   *
   * @return The pack names and factories.
   */
  private static Stream<Arguments> identityPacks() {
    return Stream.of(
        Arguments.of("usIdentity", (Supplier<PiiExtractor>) PiiPacks::usIdentity),
        Arguments.of("allStructured", (Supplier<PiiExtractor>) PiiPacks::allStructured));
  }

  /**
   * Builds a grouped United States identifier.
   *
   * @param area The 3-digit area.
   * @param group The 2-digit group.
   * @param serial The 4-digit serial.
   * @return The grouped identifier.
   */
  private static String candidate(int area, int group, String serial) {
    return area + "-" + group + "-" + serial;
  }

  /**
   * Checks serial {@code 0000} throughout the assigned ITIN ranges.
   *
   * @param candidate The ITIN candidate.
   */
  @ParameterizedTest
  @MethodSource("zeroSerialItins")
  void testAcceptsZeroSerialThroughoutAssignedItinRanges(String candidate) {
    final PiiMention expected = new PiiMention(new Span(0, candidate.length()),
        PiiMention.TYPE_US_ITIN, candidate);

    Assertions.assertEquals(List.of(expected), new UsIdentityPiiExtractor().extract(candidate),
        candidate);
  }

  /**
   * Checks group values adjacent to the assigned ITIN ranges.
   *
   * @param candidate The invalid candidate.
   */
  @ParameterizedTest
  @MethodSource("adjacentInvalidGroups")
  void testRejectsGroupsAdjacentToAssignedItinRanges(String candidate) {
    Assertions.assertEquals(List.of(), new UsIdentityPiiExtractor().extract(candidate),
        candidate);
  }

  /**
   * Checks that serial {@code 0000} remains excluded from valid SSN ranges.
   *
   * @param candidate The SSN candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "001-01-0000",
      "123-45-0000",
      "665-99-0000",
      "899-99-0000"
  })
  void testRejectsZeroSerialForSocialSecurityNumbers(String candidate) {
    Assertions.assertEquals(List.of(), new UsIdentityPiiExtractor().extract(candidate),
        candidate);
  }

  /**
   * Checks input implementation, normalization, and exact UTF-16 offsets.
   *
   * @param text The input text.
   */
  @ParameterizedTest
  @MethodSource("inputImplementations")
  void testPreservesOffsetsAndNormalizationAcrossInputImplementations(CharSequence text) {
    final int start = "😀 ITIN ".length();
    final PiiMention expected = new PiiMention(new Span(start, start + "900 50 0000".length()),
        PiiMention.TYPE_US_ITIN, "900-50-0000");

    Assertions.assertEquals(List.of(expected), new UsIdentityPiiExtractor().extract(text));
  }

  /** Checks type selection without altering recognition rules. */
  @Test
  void testTypeSelectionRetainsOnlyTheRequestedIdentifier() {
    final String text = "SSN 123-45-6789; ITIN 900-50-0000.";
    final PiiMention ssn = new PiiMention(new Span(4, 15), PiiMention.TYPE_US_SSN,
        "123-45-6789");
    final PiiMention itin = new PiiMention(new Span(22, 33), PiiMention.TYPE_US_ITIN,
        "900-50-0000");

    Assertions.assertEquals(List.of(ssn),
        new UsIdentityPiiExtractor(Set.of(PiiMention.TYPE_US_SSN)).extract(text));
    Assertions.assertEquals(List.of(itin),
        new UsIdentityPiiExtractor(Set.of(PiiMention.TYPE_US_ITIN)).extract(text));
  }

  /**
   * Checks pack extraction, annotation, masking, and public replacement paths.
   *
   * @param name The pack name.
   * @param pack The pack factory.
   */
  @ParameterizedTest
  @MethodSource("identityPacks")
  void testPublicDocumentPathsRetainTheItin(String name, Supplier<PiiExtractor> pack) {
    final String text = "Tax ID 900-50-0000.";
    final PiiMention mention = new PiiMention(new Span(7, 18), PiiMention.TYPE_US_ITIN,
        "900-50-0000");
    final Document document = new PiiAnnotator(pack.get()).annotate(Document.of(text));

    Assertions.assertEquals(List.of(new Annotation<>(mention.span(), mention)),
        document.get(PiiAnnotator.PII), name);
    Assertions.assertEquals("Tax ID ***********.",
        Masker.mask(document, PiiAnnotator.PII, '*'), name);
    Assertions.assertEquals("Tax ID US-ITIN-1.",
        new Pseudonymizer().rewrite(document).text(), name);

    final HmacTokenizer tokenizer = new HmacTokenizer(new byte[32]);
    Assertions.assertEquals("Tax ID " + tokenizer.token(mention) + ".",
        tokenizer.rewrite(document).text(), name);
  }
}
