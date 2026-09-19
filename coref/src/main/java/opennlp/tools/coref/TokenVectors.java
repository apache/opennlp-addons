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
 * Supplies one vector per token of a sentence, computed in the context of the whole
 * sentence, so a coreference model can compare mentions by meaning in context. The
 * caller supplies the tokens and receives their vectors in the same order. One provider
 * must use the same vector length for all calls.
 *
 * <p>Thread safety is implementation specific.</p>
 *
 * @since 3.0.0
 */
public interface TokenVectors {

  /**
   * Returns the length of every token vector produced by this provider.
   *
   * @return A positive vector dimension.
   */
  int dimension();

  /**
   * Encodes the tokens of one sentence.
   *
   * @param tokens The tokens in order. Must not be {@code null}, empty, or contain
   *               {@code null}.
   * @return One non-empty vector per token, in the same order and all of the same
   *         length. That length must equal {@link #dimension()}. The returned array and
   *         its vectors must not be {@code null}, and all values must be finite.
   * @throws IllegalArgumentException Thrown if {@code tokens} violates the input
   *         requirements.
   */
  float[][] vectors(String[] tokens);
}
