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

import java.util.Optional;

import opennlp.tools.commons.ThreadSafe;

/**
 * One WN-LMF lexicon reference: a {@code Requires} declaration or the {@code Extends} base of a
 * {@code LexiconExtension}. It identifies another lexicon and the required version, but does not
 * resolve or load that lexicon; resolution is the domain of {@link WnLmfResolver}.
 *
 * @param ref     The referenced lexicon id. Must not be {@code null} or empty.
 * @param version The referenced lexicon version. Must not be {@code null} or empty.
 * @param url     The optional {@code url} attribute the source declared as a retrieval hint.
 *                Must not be {@code null} and must not contain an empty value.
 * @since 3.0.0
 */
@ThreadSafe
public record WnLmfDependency(String ref, String version, Optional<String> url) {

  /**
   * Creates a dependency descriptor.
   *
   * @throws IllegalArgumentException Thrown if a component violates its documented constraint.
   */
  public WnLmfDependency {
    if (ref == null || ref.isEmpty()) {
      throw new IllegalArgumentException("ref must not be null or empty");
    }
    if (version == null || version.isEmpty()) {
      throw new IllegalArgumentException("version must not be null or empty");
    }
    if (url == null) {
      throw new IllegalArgumentException("url must not be null; use Optional.empty()");
    }
    if (url.isPresent() && url.get().isEmpty()) {
      throw new IllegalArgumentException("url must not contain an empty value");
    }
  }

  /**
   * Creates a dependency descriptor without a declared url.
   *
   * @param ref     The referenced lexicon id. Must not be {@code null} or empty.
   * @param version The referenced lexicon version. Must not be {@code null} or empty.
   * @throws IllegalArgumentException Thrown if a component is {@code null} or empty.
   */
  public WnLmfDependency(String ref, String version) {
    this(ref, version, Optional.empty());
  }
}
