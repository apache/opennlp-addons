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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the document adapter with built-in and custom detectors. */
public class ArtifactAnnotatorTest {

  /** Provides the namespaced artifacts layer without prerequisites. */
  @Test
  void testProvidesTheArtifactsLayer() {
    final ArtifactAnnotator annotator = new ArtifactAnnotator();
    assertEquals(Set.of(ArtifactAnnotator.ARTIFACTS), annotator.provides());
    assertEquals("opennlp:artifacts", ArtifactAnnotator.ARTIFACTS.id());
    assertTrue(annotator.requires().isEmpty());
  }

  /** Copies detector spans and values into document annotations. */
  @Test
  void testAnnotatesFindingsWithExactSpans() {
    final String mojibake = new String(new int[] {0x00C3, 0x00A9}, 0, 2);
    final Document document = new ArtifactAnnotator()
        .annotate(Document.of("caf" + mojibake + " time"));
    final List<Annotation<TextArtifact>> artifacts =
        document.get(ArtifactAnnotator.ARTIFACTS);
    assertEquals(1, artifacts.size());
    assertEquals(TextArtifact.TYPE_MOJIBAKE, artifacts.get(0).value().type());
    assertEquals(mojibake,
        artifacts.get(0).span().getCoveredText(document.text()).toString());
  }

  /** Adds an empty layer when no artifacts are detected. */
  @Test
  void testCleanDocumentGetsAnEmptyLayer() {
    final Document document = new ArtifactAnnotator().annotate(Document.of("all clean"));
    assertTrue(document.get(ArtifactAnnotator.ARTIFACTS).isEmpty());
  }

  /** Rejects null detector and document arguments. */
  @Test
  void testRejectsContractViolations() {
    assertThrows(IllegalArgumentException.class, () -> new ArtifactAnnotator(null));
    assertThrows(IllegalArgumentException.class,
        () -> new ArtifactAnnotator().annotate(null));
  }

  /** Supports application-defined detectors and artifact types. */
  @Test
  void testCustomDetector() {
    final ArtifactDetector detector = text -> List.of(
        new TextArtifact(new Span(4, 11), "custom-detector-type"));
    final Document original = Document.of("one example.");
    final Document annotated = new ArtifactAnnotator(detector).annotate(original);

    assertEquals(original.text(), annotated.text());
    assertEquals(List.of(new Annotation<>(new Span(4, 11),
        new TextArtifact(new Span(4, 11), "custom-detector-type"))),
        annotated.get(ArtifactAnnotator.ARTIFACTS));
  }
}
