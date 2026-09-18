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

package opennlp.tools.pii;

import java.util.List;

/**
 * Extracts personally identifiable information as {@link PiiMention} values with
 * original-text spans.
 *
 * <p>Thread safety is implementation specific.</p>
 *
 * @see PiiMention
 * @since 3.0.0
 */
public interface PiiExtractor {

  /**
   * Extracts all PII mentions from a text.
   *
   * @param text The text to scan. Must not be {@code null}.
   * @return Non-null mentions in text order and within the input text. Mentions may
   *         overlap. The list is non-null and empty when no PII mention is found.
   * @throws IllegalArgumentException Thrown if {@code text} is {@code null}.
   */
  List<PiiMention> extract(CharSequence text);
}
