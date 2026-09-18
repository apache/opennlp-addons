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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.util.Span;

import static opennlp.tools.artifacts.ArtifactTestSupport.cp;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks type validation and overlapping classification boundaries. */
public class ArtifactBoundaryTest {

  /** Rejects a null type at the constructor boundary. */
  @Test
  void testNullTypeInSelectedSet() {
    final Set<String> types = new HashSet<>();
    types.add(TextArtifact.TYPE_CONTROL);
    types.add(null);
    assertEquals("types must not contain null",
        assertThrows(IllegalArgumentException.class,
            () -> new CursorArtifactDetector(types)).getMessage());
  }

  /**
   * Supplies damaged UTF-8 followed by controls outside the damaged span.
   *
   * @return Damage strings and adjacent controls.
   */
  private static Stream<Arguments> adjacentControls() {
    return Stream.of(cp(0xC2, 0x80), cp(0xE2, 0x80, 0x99), cp(0xF0, 0x9F, 0x98, 0x80))
        .flatMap(damage -> Stream.of(cp(0), cp(1, 2), cp(0x7F))
            .map(controls -> Arguments.of(damage, controls)));
  }

  /**
   * Keeps the part of a control run that extends beyond a mojibake finding.
   *
   * @param damage The Latin-1 image of valid UTF-8.
   * @param controls The controls following that image.
   */
  @ParameterizedTest
  @MethodSource("adjacentControls")
  void testMojibakeDoesNotDropFollowingControls(String damage, String controls) {
    final String prefix = cp(0x1F642) + " ";
    final String text = prefix + damage + controls + " end";
    final int start = prefix.length();
    final int end = start + damage.length();
    assertEquals(List.of(
        new TextArtifact(new Span(start, end), TextArtifact.TYPE_MOJIBAKE),
        new TextArtifact(new Span(end, end + controls.length()), TextArtifact.TYPE_CONTROL)),
        new CursorArtifactDetector().detect(text));
  }

  /** Preserves control spans between and after consecutive damaged runs. */
  @Test
  void testControlsBetweenMojibakeRuns() {
    final String damage = cp(0xC2, 0x80);
    final String text = damage + cp(0) + damage + cp(1) + cp(0xFFFD);
    assertEquals(List.of(
        new TextArtifact(new Span(0, 2), TextArtifact.TYPE_MOJIBAKE),
        new TextArtifact(new Span(2, 3), TextArtifact.TYPE_CONTROL),
        new TextArtifact(new Span(3, 5), TextArtifact.TYPE_MOJIBAKE),
        new TextArtifact(new Span(5, 6), TextArtifact.TYPE_CONTROL),
        new TextArtifact(new Span(6, 7), TextArtifact.TYPE_REPLACEMENT)),
        new CursorArtifactDetector().detect(text));
  }

  /** Omits control findings only when that type was not requested. */
  @Test
  void testSelectedTypesKeepTheirOwnSpans() {
    final String text = cp(0xC2, 0x80, 0);
    assertEquals(List.of(new TextArtifact(new Span(0, 2), TextArtifact.TYPE_MOJIBAKE)),
        new CursorArtifactDetector(Set.of(TextArtifact.TYPE_MOJIBAKE)).detect(text));
    assertEquals(List.of(new TextArtifact(new Span(1, 3), TextArtifact.TYPE_CONTROL)),
        new CursorArtifactDetector(Set.of(TextArtifact.TYPE_CONTROL)).detect(text));
  }
}
