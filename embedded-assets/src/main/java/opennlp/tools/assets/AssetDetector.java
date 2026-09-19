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

import java.util.List;

/**
 * Detects binary assets embedded in a text as encoded characters and reports them as
 * {@link EmbeddedAsset} spans over the original, unmodified text.
 *
 * <p>Thread safety is implementation specific.</p>
 *
 * @since 3.0.0
 */
public interface AssetDetector {

  /**
   * Detects the embedded assets of a text.
   *
   * @param text The text to scan. Must not be {@code null}.
   * @return The assets in order of appearance; empty when there are none. Never
   *         {@code null}.
   * @throws IllegalArgumentException Thrown if {@code text} is {@code null}.
   */
  List<EmbeddedAsset> detect(CharSequence text);
}
