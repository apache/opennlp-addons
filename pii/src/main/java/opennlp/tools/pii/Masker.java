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

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.LayerKey;

/**
 * Masks annotated spans from positional document layers, including
 * {@link PiiAnnotator#PII}, entities and custom layers.
 *
 * <p>Masking preserves UTF-16 length and other layer offsets. The {@code char}
 * overloads mask complete spans. A {@link MaskPolicy} can preserve separators or
 * trailing letters and digits.</p>
 *
 * @since 3.0.0
 */
public final class Masker {

  private static final String POSITIONAL_LAYER_REQUIRED = "layer must be positional: ";

  /** Prevents construction. */
  private Masker() {
  }

  /**
   * Masks the spans of one layer.
   *
   * @param document The document to redact. Must not be {@code null}.
   * @param layer The positional layer to mask. Must not be {@code null} and must be
   *              present on the document.
   * @param mask The replacement character. Must not be a surrogate.
   * @return The non-null output, with the same UTF-16 length as the document text.
   * @throws IllegalArgumentException Thrown if {@code document} or {@code layer} is
   *         {@code null}, {@code mask} is a surrogate, or the layer is not present on
   *         the document or is document-scoped.
   */
  public static String mask(Document document, LayerKey<?> layer, char mask) {
    return mask(document, layer, MaskPolicy.of(mask));
  }

  /**
   * Masks the spans of one layer under a policy.
   *
   * @param document The document to redact. Must not be {@code null}.
   * @param layer The positional layer to mask. Must not be {@code null} and must be
   *              present on the document.
   * @param policy The masking policy. Must not be {@code null}.
   * @return The non-null output, with the same UTF-16 length as the document text.
   * @throws IllegalArgumentException Thrown if {@code document}, {@code layer}, or
   *         {@code policy} is {@code null}, or the layer is not present on the
   *         document or is document-scoped.
   */
  public static String mask(Document document, LayerKey<?> layer, MaskPolicy policy) {
    if (layer == null) {
      throw new IllegalArgumentException("layer must not be null");
    }
    return mask(document, List.of(layer), policy);
  }

  /**
   * Masks the spans of one layer, choosing the policy per annotation.
   *
   * <p>Pass {@link MaskPolicies#byType()} to retain the final 4 card digits and mask
   * access tokens completely. Each policy uses the original span, and retained characters
   * do not restore prior redactions. If different mask characters cover the same position,
   * the last applied mask character is used.</p>
   *
   * @param document The document to redact. Must not be {@code null}.
   * @param layer The positional layer to mask. Must not be {@code null} and must be
   *              present on the document.
   * @param policies Chooses the policy from an annotation's value. Must not be
   *                 {@code null} and must not return {@code null}.
   * @param <T> The annotation value type of the layer.
   * @return The non-null output, with the same UTF-16 length as the document text.
   * @throws IllegalArgumentException Thrown if {@code document}, {@code layer}, or
   *         {@code policies} is {@code null}, the layer is missing or document-scoped, or
   *         {@code policies} returns {@code null} for an annotation.
   */
  public static <T> String mask(Document document, LayerKey<T> layer,
      Function<? super T, MaskPolicy> policies) {
    if (document == null) {
      throw new IllegalArgumentException("document must not be null");
    }
    if (layer == null) {
      throw new IllegalArgumentException("layer must not be null");
    }
    if (policies == null) {
      throw new IllegalArgumentException("policies must not be null");
    }
    if (!document.layers().contains(layer)) {
      throw new IllegalArgumentException("layer is not present on the document: " + layer);
    }
    if (layer.scope() != LayerKey.Scope.POSITIONAL) {
      throw new IllegalArgumentException(POSITIONAL_LAYER_REQUIRED + layer);
    }
    final String text = document.text().toString();
    final StringBuilder masked = new StringBuilder(text);
    for (final Annotation<T> annotation : document.get(layer)) {
      final MaskPolicy policy = policies.apply(annotation.value());
      if (policy == null) {
        throw new IllegalArgumentException("policies must not return null");
      }
      final int start = annotation.span().getStart();
      final int end = annotation.span().getEnd();
      policy.apply(text.substring(start, end), masked, start);
    }
    return masked.toString();
  }

  /**
   * Masks the spans of several layers at once.
   *
   * @param document The document to redact. Must not be {@code null}.
   * @param layers The positional layers to mask. Must not be {@code null} or empty,
   *               and all layers must be non-null and present on the document.
   * @param mask The replacement character. Must not be a surrogate.
   * @return The non-null output, with the same UTF-16 length as the document text.
   * @throws IllegalArgumentException Thrown if {@code document} or {@code layers} is
   *         {@code null}, {@code layers} is empty or contains {@code null},
   *         {@code mask} is a surrogate, or a layer is missing or document-scoped.
   */
  public static String mask(Document document, Collection<LayerKey<?>> layers, char mask) {
    return mask(document, layers, MaskPolicy.of(mask));
  }

  /**
   * Masks the spans of several layers at once under a policy.
   *
   * <p>Each span selects positions to redact from the original text. The result masks
   * all selected positions, regardless of layer order. Retaining a trailing character
   * or separator in one span does not restore a redaction made by another span.</p>
   *
   * @param document The document to redact. Must not be {@code null}.
   * @param layers The positional layers to mask. Must not be {@code null} or empty,
   *               and all layers must be non-null and present on the document.
   * @param policy The masking policy. Must not be {@code null}.
   * @return The non-null output, with the same UTF-16 length as the document text.
   * @throws IllegalArgumentException Thrown if {@code document} or {@code layers} is
   *         {@code null}, {@code layers} is empty or contains {@code null},
   *         {@code policy} is {@code null}, or a layer is missing or document-scoped.
   */
  public static String mask(Document document, Collection<LayerKey<?>> layers,
      MaskPolicy policy) {
    if (document == null) {
      throw new IllegalArgumentException("document must not be null");
    }
    if (layers == null || layers.isEmpty()) {
      throw new IllegalArgumentException("layers must not be null or empty");
    }
    if (policy == null) {
      throw new IllegalArgumentException("policy must not be null");
    }
    for (final LayerKey<?> layer : layers) {
      if (layer == null) {
        throw new IllegalArgumentException("layers must not contain null");
      }
      if (!document.layers().contains(layer)) {
        throw new IllegalArgumentException("layer is not present on the document: " + layer);
      }
      if (layer.scope() != LayerKey.Scope.POSITIONAL) {
        throw new IllegalArgumentException(POSITIONAL_LAYER_REQUIRED + layer);
      }
    }
    final String text = document.text().toString();
    final StringBuilder masked = new StringBuilder(text);
    for (final LayerKey<?> layer : layers) {
      for (final Annotation<?> annotation : document.get(layer)) {
        final int start = annotation.span().getStart();
        final int end = annotation.span().getEnd();
        policy.apply(text.substring(start, end), masked, start);
      }
    }
    return masked.toString();
  }
}
