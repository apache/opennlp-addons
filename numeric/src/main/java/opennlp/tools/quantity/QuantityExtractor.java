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

package opennlp.tools.quantity;

import java.util.List;

/**
 * The interface for quantity extractors, which find measured or percentage quantities in
 * a text and report each as a {@link Quantity} with its span in the original text.
 *
 * @see Quantity
 * @since 3.0.0
 */
public interface QuantityExtractor {

  /**
   * Extracts all quantity mentions from a text.
   *
   * @param text The text to scan. Must not be {@code null}.
   * @return A non-null list of non-null mentions in text order, with non-overlapping
   *         spans within the original text; empty if there are no quantity mentions.
   * @throws IllegalArgumentException Thrown if {@code text} is {@code null}.
   */
  List<Quantity> extract(CharSequence text);
}
