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

package opennlp.tools.noise;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import opennlp.tools.assets.AssetAnnotator;
import opennlp.tools.assets.EmbeddedAsset;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests document annotations and asset exclusions.
 */
public class NoiseAnnotatorTest {

  /** Checks the required and provided layers for both modes. */
  @Test
  void testModesDeclareTheirRequirements() {
    assertEquals(Set.of(AssetAnnotator.ASSETS), new NoiseAnnotator().requires());
    assertEquals(Set.of(),
        new NoiseAnnotator(new StructuralNoiseScorer(), false).requires());
    assertEquals("opennlp:noise", NoiseAnnotator.NOISE.id());
    assertEquals(Set.of(NoiseAnnotator.NOISE), new NoiseAnnotator().provides());
  }

  /** Excludes asset spans while retaining independent noise findings. */
  @Test
  void testDetectedAssetsAreNotReportedAsNoise() {
    final ByteArrayOutputStream png = new ByteArrayOutputStream();
    png.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
    png.writeBytes(new byte[] {0, 0, 0, 13});
    png.writeBytes(new byte[] {'I', 'H', 'D', 'R'});
    png.writeBytes(new byte[24]);
    final String encoded = Base64.getEncoder().encodeToString(png.toByteArray());
    final String text = "report zxkcvbnmsdfg here " + encoded;

    Document document = new AssetAnnotator().annotate(Document.of(text));
    document = new NoiseAnnotator().annotate(document);

    assertEquals(1, document.get(AssetAnnotator.ASSETS).size(),
        "the payload is explained by the asset layer");
    final List<Annotation<NoiseSpan>> noise = document.get(NoiseAnnotator.NOISE);
    assertEquals(1, noise.size(), "the asset span must not be double-reported");
    assertEquals(NoiseSpan.SEVERITY_GIBBERISH, noise.get(0).value().severity());
    assertEquals("zxkcvbnmsdfg",
        noise.get(0).span().getCoveredText(text).toString());
  }

  /** Scores all text when asset exclusion is disabled. */
  @Test
  void testStandaloneModeScoresTheWholeText() {
    final String text = "QWxhZGRpbjF2cGVuNHNlc2FtZQ here";
    final Document document = new NoiseAnnotator(new StructuralNoiseScorer(), false)
        .annotate(Document.of(text));
    final List<Annotation<NoiseSpan>> noise = document.get(NoiseAnnotator.NOISE);
    assertEquals(1, noise.size());
    assertEquals(NoiseSpan.SEVERITY_BINARYISH, noise.get(0).value().severity());
  }

  /**
   * Default mode requires an asset layer before invoking the scorer.
   */
  @Test
  void testDefaultModeRejectsADocumentWithoutTheAssetLayer() {
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> new NoiseAnnotator().annotate(Document.of("plain text")));
    assertTrue(e.getMessage().contains(AssetAnnotator.ASSETS.id()),
        "the message must name the missing layer");
  }

  /** Rejects null constructor and annotation arguments. */
  @Test
  void testRejectsContractViolations() {
    assertThrows(IllegalArgumentException.class, () -> new NoiseAnnotator(null, true));
    assertThrows(IllegalArgumentException.class,
        () -> new NoiseAnnotator().annotate(null));
  }

  /** Adds an empty result layer without changing the input document. */
  @Test
  void testEmptyInputAddsAnEmptyNoiseLayer() {
    final Document input = Document.of("").with(AssetAnnotator.ASSETS, List.of());
    final Document result = new NoiseAnnotator().annotate(input);
    assertTrue(result.layers().contains(NoiseAnnotator.NOISE));
    assertEquals(List.of(), result.get(NoiseAnnotator.NOISE));
    assertFalse(input.layers().contains(NoiseAnnotator.NOISE));
  }

  /** Passes exact asset spans to the scorer and retains existing layers. */
  @Test
  void testScorerReceivesFullAssetSpansAndPreservesInputLayers() {
    final String text = "abc bcdfg";
    final Span full = new Span(0, 3);
    final EmbeddedAsset asset = new EmbeddedAsset(full, new Span(1, 2),
        "raw", "application/octet-stream", 1, -1, -1);
    final Annotation<EmbeddedAsset> assetAnnotation = new Annotation<>(full, asset);
    final Document input = Document.of(text).with(AssetAnnotator.ASSETS, List.of(assetAnnotation));
    final NoiseSpan noise = new NoiseSpan(new Span(4, 9), NoiseSpan.SEVERITY_DAMAGED, 0.5);
    final int[] calls = {0};
    final NoiseScorer delegate = (source, exclude) -> {
      calls[0]++;
      assertEquals(text, source);
      assertEquals(List.of(full), exclude);
      return List.of(noise);
    };

    final Document result = new NoiseAnnotator(delegate, true).annotate(input);
    assertEquals(1, calls[0]);
    assertEquals(text, result.text());
    assertSame(assetAnnotation, result.get(AssetAnnotator.ASSETS).get(0));
    assertSame(noise, result.get(NoiseAnnotator.NOISE).get(0).value());
    assertEquals(noise.span(), result.get(NoiseAnnotator.NOISE).get(0).span());
    assertFalse(input.layers().contains(NoiseAnnotator.NOISE));

    final Document standalone = new NoiseAnnotator((source, exclude) -> {
      assertEquals(List.of(), exclude);
      return List.of();
    }, false).annotate(input);
    assertEquals(List.of(), standalone.get(NoiseAnnotator.NOISE));
    assertSame(assetAnnotation, standalone.get(AssetAnnotator.ASSETS).get(0));
  }
}
