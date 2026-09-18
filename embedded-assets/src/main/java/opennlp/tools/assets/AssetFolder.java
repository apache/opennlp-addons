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

import java.util.function.Function;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.util.normalizer.AlignedText;
import opennlp.tools.util.normalizer.Alignment;

/**
 * Replaces embedded assets with text and returns an {@link AlignedText} mapping
 * the result's spans to the original document.
 *
 * <p>The replacement function can use {@link #caption()}, omit the asset, or
 * decode its payload for a {@link BinaryContentDescriber}.</p>
 *
 * @since 3.0.0
 */
public final class AssetFolder {

  /** Prevents construction of the folding utility. */
  private AssetFolder() {
  }

  /**
   * Folds the document's {@link AssetAnnotator#ASSETS} layer into replacement text.
   *
   * @param document The document; its assets layer must be present. Must not be
   *                 {@code null}.
   * @param replacement Produces the text that stands in for an asset. Must not be
   *                    {@code null} and must not return {@code null}.
   * @return The folded text with its alignment to the original. Never {@code null}.
   * @throws IllegalArgumentException Thrown if an argument is {@code null}, the assets
   *         layer is missing, the layer's spans overlap or are out of order, or
   *         {@code replacement} returns {@code null}.
   */
  public static AlignedText fold(Document document,
      Function<Annotation<EmbeddedAsset>, String> replacement) {
    if (document == null) {
      throw new IllegalArgumentException("document must not be null");
    }
    if (replacement == null) {
      throw new IllegalArgumentException("replacement must not be null");
    }
    if (!document.layers().contains(AssetAnnotator.ASSETS)) {
      throw new IllegalArgumentException("the document has no "
          + AssetAnnotator.ASSETS.id() + " layer; run an AssetAnnotator first");
    }
    final CharSequence text = document.text();
    final StringBuilder folded = new StringBuilder(text.length());
    final Alignment.Builder alignment = new Alignment.Builder();
    int cursor = 0;
    for (final Annotation<EmbeddedAsset> annotation
        : document.get(AssetAnnotator.ASSETS)) {
      final int start = annotation.span().getStart();
      final int end = annotation.span().getEnd();
      if (start < cursor) {
        throw new IllegalArgumentException(
            "asset spans must be in order and free of overlap");
      }
      final String stand = replacement.apply(annotation);
      if (stand == null) {
        throw new IllegalArgumentException("replacement must not return null");
      }
      folded.append(text, cursor, start);
      alignment.equal(start - cursor);
      folded.append(stand);
      alignment.replace(end - start, stand.length());
      cursor = end;
    }
    folded.append(text, cursor, text.length());
    alignment.equal(text.length() - cursor);
    return new AlignedText(text, folded.toString(), alignment.build(text.length()));
  }

  /**
   * The default replacement: a short bracketed description from the asset's own
   * metadata, for example {@code [png 640x480, 12345 bytes]} or
   * {@code [pdf, 51200 bytes]}.
   *
   * @return The caption function. Never {@code null}.
   */
  public static Function<Annotation<EmbeddedAsset>, String> caption() {
    return annotation -> {
      final EmbeddedAsset asset = annotation.value();
      final StringBuilder caption = new StringBuilder("[").append(asset.format());
      if (asset.width() >= 0 && asset.height() >= 0) {
        caption.append(' ').append(asset.width()).append('x').append(asset.height());
      }
      return caption.append(", ").append(asset.decodedLength()).append(" bytes]")
          .toString();
    };
  }
}
