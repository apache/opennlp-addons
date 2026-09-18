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

package opennlp.tools.artifacts;

import java.util.List;

/**
 * Detects damaged or suspicious character sequences in a text and reports them as
 * {@link TextArtifact} spans over the original, unmodified text.
 *
 * <p>Thread safety is implementation specific.</p>
 *
 * @since 3.0.0
 */
public interface ArtifactDetector {

  /**
   * Detects the artifacts of a text.
   *
   * @param text The text to scan. Must not be {@code null}.
   * @return The artifacts in order of appearance; empty when none are detected. Never
   *         {@code null}.
   * @throws IllegalArgumentException Thrown if {@code text} is {@code null}.
   */
  List<TextArtifact> detect(CharSequence text);
}
