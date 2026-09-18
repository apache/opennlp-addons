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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Chooses an annotator according to a condition over the document. The two-argument
 * form adds the true branch's provided layers empty when the condition does not hold.
 * The three-argument form runs an explicit false branch with the same output layers.
 *
 * <p>This annotator is safe for concurrent use when its condition and delegates are.</p>
 *
 * @since 3.0.0
 */
public final class ConditionalAnnotator implements DocumentAnnotator {

  private final Predicate<Document> condition;
  private final DocumentAnnotator whenTrue;
  private final DocumentAnnotator whenFalse;
  private final Set<LayerKey<?>> required;
  private final Set<LayerKey<?>> provided;
  private final LayerKey<?>[] requiredLayers;

  /**
   * Creates a conditional annotator with an empty false branch.
   *
   * @param condition The condition selecting whether the delegate runs. Must not be
   *                  {@code null}.
   * @param delegate The annotator to run when the condition is true. Must not be
   *                 {@code null}.
   * @throws IllegalArgumentException Thrown if a parameter is {@code null}.
   */
  public ConditionalAnnotator(Predicate<Document> condition, DocumentAnnotator delegate) {
    if (condition == null) {
      throw new IllegalArgumentException("condition must not be null");
    }
    if (delegate == null) {
      throw new IllegalArgumentException("delegate must not be null");
    }
    this.condition = condition;
    this.whenTrue = delegate;
    this.whenFalse = null;
    required = Set.copyOf(delegate.requires());
    provided = Set.copyOf(delegate.provides());
    requiredLayers = required.toArray(LayerKey<?>[]::new);
  }

  /**
   * Creates a conditional annotator with explicit true and false branches. Both
   * branches must provide the same layers so downstream pipeline contracts do not
   * depend on the condition. The wrapper requires the union of their input layers.
   *
   * @param condition The condition selecting the branch. Must not be {@code null}.
   * @param whenTrue The annotator to run when the condition is true. Must not be
   *                 {@code null}.
   * @param whenFalse The annotator to run when the condition does not hold. Must not be
   *                  {@code null}.
   * @throws IllegalArgumentException Thrown if a parameter is {@code null} or the
   *         branches provide different layers.
   */
  public ConditionalAnnotator(Predicate<Document> condition, DocumentAnnotator whenTrue,
      DocumentAnnotator whenFalse) {
    if (condition == null) {
      throw new IllegalArgumentException("condition must not be null");
    }
    if (whenTrue == null) {
      throw new IllegalArgumentException("whenTrue must not be null");
    }
    if (whenFalse == null) {
      throw new IllegalArgumentException("whenFalse must not be null");
    }
    final Set<LayerKey<?>> trueProvided = Set.copyOf(whenTrue.provides());
    final Set<LayerKey<?>> falseProvided = Set.copyOf(whenFalse.provides());
    if (!trueProvided.equals(falseProvided)) {
      throw new IllegalArgumentException("branches must provide the same layers");
    }
    final Set<LayerKey<?>> branchRequirements = new HashSet<>(whenTrue.requires());
    branchRequirements.addAll(whenFalse.requires());
    this.condition = condition;
    this.whenTrue = whenTrue;
    this.whenFalse = whenFalse;
    required = Set.copyOf(branchRequirements);
    provided = trueProvided;
    requiredLayers = required.toArray(LayerKey<?>[]::new);
  }

  /**
   * {@inheritDoc}
   *
   * Requires all branch inputs before testing the condition, adding empty output layers
   * when the implicit false branch is selected.
   */
  @Override
  public Document annotate(Document document) {
    DocumentAnnotators.requireLayers(document, requiredLayers);
    if (condition.test(document)) {
      return whenTrue.annotate(document);
    }
    if (whenFalse != null) {
      return whenFalse.annotate(document);
    }
    Document result = document;
    for (final LayerKey<?> layer : provided) {
      result = withEmpty(result, layer);
    }
    return result;
  }

  /**
   * Adds an empty output layer for a skipped annotation stage.
   *
   * @param document The input document.
   * @param layer The output layer key.
   * @param <T> The annotation value type.
   * @return The document with an empty output layer.
   */
  private <T> Document withEmpty(Document document, LayerKey<T> layer) {
    return document.with(layer, List.of());
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> requires() {
    return required;
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> provides() {
    return provided;
  }
}
