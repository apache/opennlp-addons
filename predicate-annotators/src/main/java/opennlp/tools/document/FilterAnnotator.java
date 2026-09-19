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

package opennlp.tools.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Writes the annotations of one layer that pass a predicate to a second layer. A
 * predicate may inspect an annotation, or both the annotation and its document. The
 * source layer is unchanged.
 *
 * <p>This annotator is safe for concurrent use when its predicate is.</p>
 *
 * @param <T> The value type of the filtered layer.
 *
 * @since 3.0.0
 */
public final class FilterAnnotator<T> implements DocumentAnnotator {

  private final LayerKey<T> source;
  private final LayerKey<T> target;
  private final BiPredicate<Document, Annotation<T>> keep;

  /**
   * Creates a filter with a predicate that inspects each annotation.
   *
   * @param source The layer to read. Must not be {@code null}.
   * @param target The layer to write matching annotations to. Must not be {@code null} and must
   *               differ from {@code source}, because a document rejects a duplicate
   *               layer.
   * @param keep The predicate an annotation must pass. Must not be
   *             {@code null}.
   * @throws IllegalArgumentException Thrown if a parameter is {@code null} or the two
   *         layers are equal.
   */
  public FilterAnnotator(LayerKey<T> source, LayerKey<T> target,
      Predicate<Annotation<T>> keep) {
    this(source, target, documentAware(keep));
  }

  /**
   * Creates a filter with a predicate that can inspect the document and each annotation.
   *
   * @param source The layer to read. Must not be {@code null}.
   * @param target The layer to write matching annotations to. Must not be {@code null} and must
   *               differ from {@code source}.
   * @param keep The predicate an annotation must pass. Must not be
   *             {@code null}.
   * @throws IllegalArgumentException Thrown if a parameter is {@code null} or the two
   *         layers are equal.
   */
  public FilterAnnotator(LayerKey<T> source, LayerKey<T> target,
      BiPredicate<Document, Annotation<T>> keep) {
    if (source == null) {
      throw new IllegalArgumentException("source must not be null");
    }
    if (target == null) {
      throw new IllegalArgumentException("target must not be null");
    }
    if (source.equals(target)) {
      throw new IllegalArgumentException("target must differ from source");
    }
    if (keep == null) {
      throw new IllegalArgumentException("keep must not be null");
    }
    this.source = source;
    this.target = target;
    this.keep = keep;
  }

  /**
   * Adapts the annotation-only constructor argument.
   *
   * @param keep The annotation filter.
   * @param <T> The annotation value type.
   * @return A filter that ignores the document argument.
   * @throws IllegalArgumentException If {@code keep} is {@code null}.
   */
  private static <T> BiPredicate<Document, Annotation<T>> documentAware(
      Predicate<Annotation<T>> keep) {
    if (keep == null) {
      throw new IllegalArgumentException("keep must not be null");
    }
    return (document, annotation) -> keep.test(annotation);
  }

  /**
   * {@inheritDoc}
   *
   * Adds the target layer with matching source annotations in source order.
   */
  @Override
  public Document annotate(Document document) {
    DocumentAnnotators.requireLayers(document, source);
    final List<Annotation<T>> matches = new ArrayList<>();
    for (final Annotation<T> annotation : document.get(source)) {
      if (keep.test(document, annotation)) {
        matches.add(annotation);
      }
    }
    return document.with(target, matches);
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> requires() {
    return Set.of(source);
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(target);
  }
}
