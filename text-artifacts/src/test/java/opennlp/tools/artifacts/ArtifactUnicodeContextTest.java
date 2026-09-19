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

package opennlp.tools.artifacts;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

import static opennlp.tools.artifacts.ArtifactTestSupport.cp;
import static opennlp.tools.artifacts.ArtifactTestSupport.tags;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks Unicode tag syntax and combining marks next to joining controls. */
public class ArtifactUnicodeContextTest {

  /**
   * Supplies invalid tag specifications, including overlong hidden text.
   *
   * @return Tag text that cannot have flag syntax.
   */
  private static Stream<String> invalidFlagTags() {
    return Stream.of("gb", "g1eng", "1ab", "12a", "GBENG", "gb-eng", "gb12345",
        "00112345", "gb" + "a".repeat(29), "gbeng" + "secret".repeat(1000));
  }

  /**
   * Reports the complete tag run when a black flag precedes invalid tag syntax.
   *
   * @param specification The invalid tag specification.
   */
  @ParameterizedTest
  @MethodSource("invalidFlagTags")
  void testInvalidFlagTagsRemainVisibleToTheDetector(String specification) {
    final String hidden = tags(specification, true);
    final String text = cp(0x1F3F4) + hidden;
    assertEquals(List.of(new TextArtifact(new Span(2, text.length()),
        TextArtifact.TYPE_UNICODE_TAG)), new CursorArtifactDetector().detect(text));
  }

  /**
   * Leaves terminated flags with region or subdivision syntax unreported.
   *
   * @param specification The tag specification.
   */
  @ParameterizedTest
  @ValueSource(strings = {"gbeng", "gbsct", "gbwls", "usca", "001", "419", "gb1234", "001abcd"})
  void testFlagTagSyntaxIsPreserved(String specification) {
    assertEquals(List.of(), new CursorArtifactDetector().detect(
        cp(0x1F3F4) + tags(specification, true)));
  }

  /**
   * Supplies letter contexts with combining marks next to a joining control.
   *
   * @return Script labels and source text.
   */
  private static Stream<Arguments> letterContexts() {
    return Stream.of(
        Arguments.of("Malayalam ZWNJ", cp(0x0D26, 0x0D43, 0x0D15, 0x0D4D, 0x200C,
            0x0D38, 0x0D3E, 0x0D15, 0x0D4D, 0x0D37, 0x0D3F)),
        Arguments.of("Sinhala ZWJ", cp(0x0DC1, 0x0DCA, 0x200D, 0x0DBB, 0x0DD3,
            0x20, 0x0DBD, 0x0D82, 0x0D9A, 0x0DCF)),
        Arguments.of("marks before and after", cp(0x0628, 0x064E, 0x200C, 0x0650, 0x0628)),
        Arguments.of("decomposed accent", cp('e', 0x0301, 0x200B, 'x')),
        Arguments.of("supplementary letters", cp(0x10400, 0x0301, 0x200C, 0x10401)));
  }

  /**
   * Keeps a single joining control between letters when marks intervene.
   *
   * @param script The script or context label.
   * @param text The source text.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("letterContexts")
  void testMarksDoNotBreakLetterContext(String script, String text) {
    assertEquals(List.of(), new CursorArtifactDetector(
        Set.of(TextArtifact.TYPE_ZERO_WIDTH)).detect(text), script);
  }

  /**
   * Supplies combining-mark contexts without letters on both sides.
   *
   * @return Code points surrounding a zero-width character.
   */
  private static Stream<String> nonLetterContexts() {
    return Stream.of(cp('.', 0x0301, 0x200C, 'x'), cp('x', 0x200C, 0x0301, '.'),
        cp(0x0301, 0x200C, 'x'), cp('x', 0x200C, 0x0301),
        cp('a', '.', 0x0301, 0x200B, 'b'), cp('a', 0x0301, 0x200B, 0x200B, 'b'));
  }

  /**
   * Does not treat punctuation, missing letters, or repeated joiners as letter context.
   *
   * @param text The source text.
   */
  @ParameterizedTest
  @MethodSource("nonLetterContexts")
  void testMarksDoNotHideIsolatedZeroWidthCharacters(String text) {
    final var artifacts = new CursorArtifactDetector(
        Set.of(TextArtifact.TYPE_ZERO_WIDTH)).detect(text);
    assertEquals(1, artifacts.size());
    assertEquals(TextArtifact.TYPE_ZERO_WIDTH, artifacts.get(0).type());
  }
}
