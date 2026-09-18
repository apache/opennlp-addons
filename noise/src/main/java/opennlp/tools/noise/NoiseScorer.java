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

import java.util.Collection;
import java.util.List;

import opennlp.tools.util.Span;

/**
 * Reports noisy regions as {@link NoiseSpan} values with original-text offsets.
 *
 * <p>Thread safety is implementation specific.</p>
 *
 * @since 3.0.0
 */
public interface NoiseScorer {

  /**
   * Scores text without changing it.
   *
   * @param text The text to scan. Must not be {@code null}.
   * @param exclude Regions omitted from scoring. The collection and entries must not
   *                be {@code null}. Spans must fit within {@code text}; they may overlap
   *                or be unordered. Empty spans are ignored.
   * @return A non-null list of noise spans in text order, without overlaps with
   *         {@code exclude}. An empty result means no noise was detected.
   * @throws IllegalArgumentException Thrown if an argument is {@code null} or
   *         {@code exclude} contains {@code null} or a span outside {@code text}.
   */
  List<NoiseSpan> score(CharSequence text, Collection<Span> exclude);
}
