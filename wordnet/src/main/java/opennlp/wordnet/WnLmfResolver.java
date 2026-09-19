/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package opennlp.wordnet;

import java.io.IOException;

/**
 * Supplies documents referenced by WN-LMF {@code Extends} declarations.
 *
 * <p>A resolver must return a fresh {@link WnLmfSource} per call and must never return
 * {@code null}. If the document cannot be obtained, it must throw an {@link IOException}
 * naming the requested id and version. Ownership of a returned source transfers to
 * the requesting code.</p>
 *
 * <p>Thread safety is implementation specific.</p>
 *
 * @since 3.0.0
 */
@FunctionalInterface
public interface WnLmfResolver {

  /**
   * Opens the document containing the referenced lexicon.
   *
   * @param reference The {@code Extends} reference to resolve, carrying the id, exact version,
   *                  and, when the source declared one, a url hint. Never {@code null}.
   * @return A freshly opened source for the document that contains the referenced lexicon.
   *         Must not be {@code null} and must not have been returned before.
   * @throws IOException Thrown if the referenced document cannot be obtained.
   */
  WnLmfSource resolve(WnLmfDependency reference) throws IOException;
}
