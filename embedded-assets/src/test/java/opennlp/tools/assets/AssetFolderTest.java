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

package opennlp.tools.assets;

import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.util.Span;
import opennlp.tools.util.normalizer.AlignedText;

import static opennlp.tools.assets.AssetTestSupport.png;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the fold: replacement text stands in for each asset, the surrounding text is
 * unchanged, and the alignment maps every folded span back to the exact original
 * offsets.
 */
public class AssetFolderTest {

  /**
   * Detects a PNG header fixture between two sentences.
   *
   * @return The annotated document.
   */
  private Document annotated() {
    final String encoded = Base64.getEncoder().encodeToString(png(5, 7));
    return new AssetAnnotator().annotate(
        Document.of("Before the image. " + encoded + " After the image."));
  }

  /**
   * Builds a text embedding two distinct PNG payloads, so a fold has to advance its
   * cursor across more than one asset.
   *
   * @return The text with a 5x7 and a 2x3 PNG payload between words.
   */
  private String twoAssetText() {
    final String first = Base64.getEncoder().encodeToString(png(5, 7));
    final String second = Base64.getEncoder().encodeToString(png(2, 3));
    return "One " + first + " two " + second + " three.";
  }

  @Test
  void testCaptionFoldKeepsSurroundingTextAndMapsOffsets() {
    final Document document = annotated();
    final AlignedText folded = AssetFolder.fold(document, AssetFolder.caption());
    final String result = folded.normalizedString();
    assertEquals("Before the image. [png 5x7, 45 bytes] After the image.", result);

    // The folded caption maps back to the asset's exact original span.
    final Annotation<EmbeddedAsset> asset = document.get(AssetAnnotator.ASSETS).get(0);
    final int captionStart = result.indexOf('[');
    final int captionEnd = result.indexOf(']') + 1;
    final Span original = folded.toOriginalSpan(captionStart, captionEnd);
    assertEquals(asset.span().getStart(), original.getStart());
    assertEquals(asset.span().getEnd(), original.getEnd());

    // Text after the fold maps back to itself, shifted by the length difference.
    final int afterStart = result.indexOf("After");
    final Span after = folded.toOriginalSpan(afterStart, afterStart + 5);
    assertEquals("After", after.getCoveredText(document.text()).toString());
  }

  @Test
  void testDescriberComposesThroughTheReplacementFunction() {
    final Document document = annotated();
    final BinaryContentDescriber describer = (content, mediaType) -> {
      assertEquals("image/png", mediaType);
      return "a " + content.length + " byte " + mediaType + " asset";
    };
    final AlignedText folded = AssetFolder.fold(document, annotation ->
        describer.describe(annotation.value().decode(document.text()),
            annotation.value().mediaType()));
    assertEquals("Before the image. a 45 byte image/png asset After the image.",
        folded.normalizedString());
  }

  @Test
  void testDocumentWithoutTheLayerFailsLoud() {
    assertThrows(IllegalArgumentException.class,
        () -> AssetFolder.fold(Document.of("no layer here"), AssetFolder.caption()));
  }

  @Test
  void testEmptyLayerFoldsToTheIdenticalText() {
    final Document document = new AssetAnnotator().annotate(Document.of("clean text"));
    final AlignedText folded = AssetFolder.fold(document, AssetFolder.caption());
    assertEquals("clean text", folded.normalizedString());
    assertEquals(List.of(), document.get(AssetAnnotator.ASSETS));
  }

  @Test
  void testTwoAssetFoldKeepsInterveningTextAndMapsBothSpans() {
    final String text = twoAssetText();
    final Document document = new AssetAnnotator().annotate(Document.of(text));
    final AlignedText folded = AssetFolder.fold(document, AssetFolder.caption());
    final String result = folded.normalizedString();
    assertEquals("One [png 5x7, 45 bytes] two [png 2x3, 45 bytes] three.", result);

    // Each folded caption maps back to its own asset's exact original span.
    final List<Annotation<EmbeddedAsset>> assets = document.get(AssetAnnotator.ASSETS);
    assertEquals(2, assets.size());
    final int firstStart = result.indexOf('[');
    final int firstEnd = result.indexOf(']') + 1;
    final Span firstOriginal = folded.toOriginalSpan(firstStart, firstEnd);
    assertEquals(assets.get(0).span().getStart(), firstOriginal.getStart());
    assertEquals(assets.get(0).span().getEnd(), firstOriginal.getEnd());
    final int secondStart = result.indexOf('[', firstEnd);
    final int secondEnd = result.indexOf(']', secondStart) + 1;
    final Span secondOriginal = folded.toOriginalSpan(secondStart, secondEnd);
    assertEquals(assets.get(1).span().getStart(), secondOriginal.getStart());
    assertEquals(assets.get(1).span().getEnd(), secondOriginal.getEnd());

    // Text behind the second fold still maps back to itself.
    final int tailStart = result.indexOf("three");
    final Span tail = folded.toOriginalSpan(tailStart, tailStart + 5);
    assertEquals("three", tail.getCoveredText(text).toString());
  }

  @Test
  void testOutOfOrderAssetSpansAreRejected() {
    final String text = twoAssetText();
    final List<EmbeddedAsset> detected = new CursorAssetDetector().detect(text);
    assertEquals(2, detected.size());
    final Document document = Document.of(text).with(AssetAnnotator.ASSETS,
        List.of(new Annotation<>(detected.get(1).span(), detected.get(1)),
            new Annotation<>(detected.get(0).span(), detected.get(0))));
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> AssetFolder.fold(document, AssetFolder.caption()));
    assertEquals("asset spans must be in order and free of overlap", e.getMessage());
  }

  @Test
  void testRejectsContractViolations() {
    final Document document = annotated();
    assertThrows(IllegalArgumentException.class,
        () -> AssetFolder.fold(null, AssetFolder.caption()));
    assertThrows(IllegalArgumentException.class,
        () -> AssetFolder.fold(document, null));
    assertThrows(IllegalArgumentException.class,
        () -> AssetFolder.fold(document, annotation -> null));
  }
}
