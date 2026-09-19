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

import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

import static opennlp.tools.artifacts.ArtifactTestSupport.cp;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies the types and offsets in the manual's {@code artifacts.xml} examples. */
public class ArtifactsManualExampleTest {

  /** The chapter's damaged word ending: U+00E9 read back through a single-byte decoding. */
  private static final String DAMAGED_E_ACUTE = new String(new int[] {0x00C3, 0x00A9}, 0, 2);

  /** The replacement character in the chapter's example. */
  private static final String REPLACEMENT = new String(new int[] {0xFFFD}, 0, 1);

  /** The chapter's example text, constructed with code points. */
  private static final String TEXT = "caf" + DAMAGED_E_ACUTE + " costs " + REPLACEMENT + "8";

  /** The detection example: two findings with the printed types and offsets. */
  @Test
  void testDetectionExamplePrintsTheStatedLines() {
    final ArtifactDetector detector = new CursorArtifactDetector();
    final List<TextArtifact> artifacts = detector.detect(TEXT);
    assertEquals(2, artifacts.size());
    assertEquals("mojibake [3..5)",
        artifacts.get(0).type() + " " + artifacts.get(0).span());
    assertEquals("replacement [12..13)",
        artifacts.get(1).type() + " " + artifacts.get(1).span());
  }

  /** Accented text produces no artifacts. */
  @Test
  void testAccentedTextYieldsNothing() {
    final String dejaVu = "d" + new String(new int[] {0x00E9}, 0, 1) + "j"
        + new String(new int[] {0x00E0}, 0, 1) + " vu";
    assertEquals(List.of(), new CursorArtifactDetector().detect(dejaVu));
  }

  /** The selective-constructor example reports text damage but not replacement markers. */
  @Test
  void testSelectiveDetectorExample() {
    final ArtifactDetector textDamage = new CursorArtifactDetector(Set.of(
        TextArtifact.TYPE_MOJIBAKE,
        TextArtifact.TYPE_UNICODE_TAG));

    final List<TextArtifact> damage = textDamage.detect(TEXT);

    assertEquals(1, damage.size());
    assertEquals(TextArtifact.TYPE_MOJIBAKE, damage.get(0).type());
  }

  /** Masks reported characters without changing offsets. */
  @Test
  void testMaskingExampleProducesTheStatedString() {
    final Document document = new ArtifactAnnotator().annotate(Document.of(TEXT));
    final List<Annotation<TextArtifact>> artifacts =
        document.get(ArtifactAnnotator.ARTIFACTS);
    final StringBuilder masked = new StringBuilder(TEXT);
    for (Annotation<TextArtifact> artifact : artifacts) {
      for (int i = artifact.span().getStart(); i < artifact.span().getEnd(); i++) {
        masked.setCharAt(i, '#');
      }
    }
    assertEquals("caf## costs #8", masked.toString());
  }

  /** The layer example: two annotations, the first covering the damaged word part. */
  @Test
  void testLayerExampleCarriesTheStatedAnnotations() {
    final Document document = new ArtifactAnnotator().annotate(Document.of(TEXT));
    final List<Annotation<TextArtifact>> artifacts =
        document.get(ArtifactAnnotator.ARTIFACTS);
    assertEquals(2, artifacts.size());
    assertEquals(DAMAGED_E_ACUTE,
        artifacts.get(0).span().getCoveredText(TEXT).toString());
    assertEquals(TextArtifact.TYPE_MOJIBAKE, artifacts.get(0).value().type());
  }

  /** A mojibake finding does not absorb the following NUL in the document layer. */
  @Test
  void testAdjacentControlExample() {
    final String text = "value " + cp(0x00C2, 0x0080, 0x0000);
    final Document document = new ArtifactAnnotator().annotate(Document.of(text));
    final List<Annotation<TextArtifact>> artifacts = document.get(ArtifactAnnotator.ARTIFACTS);
    assertEquals(2, artifacts.size());
    assertEquals(new Span(6, 8), artifacts.get(0).span());
    assertEquals(TextArtifact.TYPE_MOJIBAKE, artifacts.get(0).value().type());
    assertEquals(new Span(8, 9), artifacts.get(1).span());
    assertEquals(TextArtifact.TYPE_CONTROL, artifacts.get(1).value().type());
    assertEquals(text, document.text().toString());
  }
}
