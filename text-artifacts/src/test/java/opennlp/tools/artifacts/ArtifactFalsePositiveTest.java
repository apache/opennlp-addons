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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks ordinary multilingual text and punctuation for false positives. */
public class ArtifactFalsePositiveTest {

  private final CursorArtifactDetector detector = new CursorArtifactDetector();

  private static final String[] CLEAN_TEXT = {
      "OpenNLP 3.0 processes ordinary ASCII and version 3.0.0.",
      "Déjà vu à Paris, café, naïve, and João.",
      "Typography uses ‘quotes’, an ellipsis…, an euro sign €, and an en dash.",
      "Καλημέρα κόσμε",
      "Привет, мир",
      "東京で自然言語処理を試す",
      "مرحبا بالعالم",
      "हैलो दुनिया",
      "emoji 😀 and a family 👩‍👩‍👦 are ordinary text",
      "HTML tags like <script> and template braces {{value}} are visible text"
  };

  /** @return Clean fixtures, one per parameterized invocation. */
  private static List<String> cleanText() {
    return List.of(CLEAN_TEXT);
  }

  /**
   * Each clean fixture remains unmatched in isolation.
   *
   * @param text The clean text to scan.
   */
  @ParameterizedTest
  @MethodSource("cleanText")
  void testCleanTextYieldsNothing(String text) {
    assertEquals(List.of(), detector.detect(text), text);
  }

  /** Joining the examples with punctuation produces no artifacts. */
  @Test
  void testJoinedCorpusYieldsNothing() {
    assertEquals(List.of(), detector.detect(String.join(" | ", CLEAN_TEXT)));
  }

  /** Reports encoding damage and hidden tags in a positive control. */
  @Test
  void testControlTextStillFindsDamageAndHiddenTags() {
    final String hidden = new String(new int[] {0xE0073, 0xE0065, 0xE0063, 0xE007F}, 0, 4);
    final String damaged = new String(new int[] {0x00E2, 0x0082, 0x00AC}, 0, 3);

    assertEquals(2, detector.detect("price " + damaged + " " + hidden).size());
  }
}
