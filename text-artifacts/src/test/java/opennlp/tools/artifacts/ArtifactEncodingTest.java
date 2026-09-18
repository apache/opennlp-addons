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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

import static opennlp.tools.artifacts.ArtifactTestSupport.cp;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks UTF-8 boundaries and shared-detector state. */
public class ArtifactEncodingTest {

  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

  private final ArtifactDetector mojibake = new CursorArtifactDetector(
      Set.of(TextArtifact.TYPE_MOJIBAKE));

  /**
   * Detects valid multibyte UTF-8 interpreted as Latin-1 at each encoding boundary.
   *
   * @param codePoint The original Unicode code point.
   */
  @ParameterizedTest
  @ValueSource(ints = {0x80, 0x7FF, 0x800, 0xD7FF, 0xE000, 0xFFFF, 0x10000, 0x10FFFF})
  void testValidUtf8Boundaries(int codePoint) {
    final String damage = new String(cp(codePoint).getBytes(StandardCharsets.UTF_8),
        StandardCharsets.ISO_8859_1);
    assertEquals(List.of(new TextArtifact(new Span(0, damage.length()),
        TextArtifact.TYPE_MOJIBAKE)), mojibake.detect(damage));
  }

  /**
   * Supplies the UTF-8 continuation byte range.
   *
   * @return Byte values from 0x80 through 0xBF.
   */
  private static IntStream continuationBytes() {
    return IntStream.rangeClosed(0x80, 0xBF);
  }

  /**
   * Checks reverse mappings using the JDK Windows-1252 decoder.
   * Undefined byte values become replacement characters, not mojibake.
   *
   * @param continuation The continuation byte following 0xC2.
   */
  @ParameterizedTest
  @MethodSource("continuationBytes")
  void testWindows1252Mappings(int continuation) {
    final String damage = new String(new byte[] {(byte) 0xC2, (byte) continuation},
        WINDOWS_1252);
    final List<TextArtifact> expected = damage.indexOf(0xFFFD) >= 0 ? List.of()
        : List.of(new TextArtifact(new Span(0, damage.length()), TextArtifact.TYPE_MOJIBAKE));

    assertEquals(expected, mojibake.detect(damage));
  }

  /**
   * Supplies invalid UTF-8 represented as Latin-1 source characters.
   *
   * @return Failure labels and source text.
   */
  private static Stream<Arguments> invalidUtf8() {
    return Stream.of(
        Arguments.of("overlong two-byte form", cp(0xC0, 0x80)),
        Arguments.of("invalid two-byte lead", cp(0xC1, 0xBF)),
        Arguments.of("overlong three-byte form", cp(0xE0, 0x9F, 0xBF)),
        Arguments.of("high surrogate", cp(0xED, 0xA0, 0x80)),
        Arguments.of("low surrogate", cp(0xED, 0xBF, 0xBF)),
        Arguments.of("overlong four-byte form", cp(0xF0, 0x8F, 0xBF, 0xBF)),
        Arguments.of("beyond Unicode maximum", cp(0xF4, 0x90, 0x80, 0x80)),
        Arguments.of("invalid four-byte lead", cp(0xF5, 0x80, 0x80, 0x80)),
        Arguments.of("invalid leading byte", cp(0xFF, 0x80)),
        Arguments.of("bare continuation", cp(0x80)),
        Arguments.of("truncated two-byte form", cp(0xC2)),
        Arguments.of("truncated three-byte form", cp(0xE2, 0x82)),
        Arguments.of("truncated four-byte form", cp(0xF0, 0x9F, 0x98)));
  }

  /**
   * Does not report invalid encodings as mojibake.
   *
   * @param reason The invalid encoding case.
   * @param damage The source characters.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidUtf8")
  void testInvalidUtf8Boundaries(String reason, String damage) {
    assertEquals(List.of(), mojibake.detect(damage), reason);
  }

  /**
   * Uses one configured detector concurrently without sharing cursor state.
   *
   * @throws Exception If a worker fails or does not finish within the timeout.
   */
  @Test
  void testConcurrentDetection() throws Exception {
    final ArtifactDetector detector = new CursorArtifactDetector();
    final String text = cp(0xC2, 0x80, 0, 0xFFFD);
    final List<TextArtifact> expected = List.of(
        new TextArtifact(new Span(0, 2), TextArtifact.TYPE_MOJIBAKE),
        new TextArtifact(new Span(2, 3), TextArtifact.TYPE_CONTROL),
        new TextArtifact(new Span(3, 4), TextArtifact.TYPE_REPLACEMENT));
    try (ExecutorService workers = Executors.newFixedThreadPool(4)) {
      final List<Future<List<TextArtifact>>> results = new ArrayList<>();
      for (int i = 0; i < 32; i++) {
        results.add(workers.submit(() -> detector.detect(text)));
      }
      for (Future<List<TextArtifact>> result : results) {
        assertEquals(expected, result.get(10, TimeUnit.SECONDS));
      }
    }
  }
}
