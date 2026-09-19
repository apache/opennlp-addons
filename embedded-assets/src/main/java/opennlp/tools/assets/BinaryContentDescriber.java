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

/**
 * Describes binary content as text, for example an image caption or extracted document text.
 *
 * <p>Thread safety is implementation specific.</p>
 *
 * @since 3.0.0
 */
public interface BinaryContentDescriber {

  /**
   * Describes binary content as text.
   *
   * @param content The decoded bytes. Must not be {@code null}.
   * @param mediaType The media type of {@code content}. Must not be {@code null} or
   *                  blank.
   * @return The description; implementations decide its form and length. Never
   *         {@code null}.
   * @throws IllegalArgumentException Thrown if {@code content} is {@code null} or
   *         {@code mediaType} is {@code null} or blank.
   */
  String describe(byte[] content, String mediaType);
}
