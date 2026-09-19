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

package opennlp.tools.coref;

/**
 * Supplies a vector per word for the ranking features that compare mention heads by
 * meaning, so {@code the firm} can find {@code Microsoft}. Any static word embedding
 * serves; a text embedder adapts as {@code word -> embedder.embed(word)}.
 *
 * <p>Thread safety is implementation specific.</p>
 *
 * @since 3.0.0
 */
public interface WordVectors {

  /**
   * Returns the length of every word vector supplied by this provider.
   *
   * @return A positive vector dimension.
   */
  int dimension();

  /**
   * Looks up a word.
   *
   * @param word The lowercased word.
   * @return A vector of {@link #dimension()} finite values, or {@code null} for a word
   *         without one.
   */
  float[] vector(String word);
}
