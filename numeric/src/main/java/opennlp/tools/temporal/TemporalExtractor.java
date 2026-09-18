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

package opennlp.tools.temporal;

import java.time.LocalDate;
import java.util.List;

/**
 * The interface for temporal extractors, which find calendar mentions in a text and
 * report each as a {@link TemporalExpression} with its span in the original text and
 * whether its value was stated directly or resolved from relative text.
 *
 * @see TemporalExpression
 * @since 3.0.0
 */
public interface TemporalExtractor {

  /**
   * Extracts all temporal mentions from a text.
   *
   * @param text The text to scan. Must not be {@code null}.
   * @return A non-null list of non-null mentions in text order, with non-overlapping
   *         spans within the original text; empty if there are no temporal mentions.
   * @throws IllegalArgumentException Thrown if {@code text} is {@code null}.
   */
  List<TemporalExpression> extract(CharSequence text);

  /**
   * Extracts all temporal mentions from a text, resolving relative expressions such
   * as {@code yesterday} or {@code next month} against a reference date. The default
   * method returns the absolute mentions from {@link #extract(CharSequence)}. A result
   * resolved from relative text has {@link TemporalExpression.Origin#RELATIVE} origin.
   *
   * @param text The text to scan. Must not be {@code null}.
   * @param reference The date relative expressions resolve against, typically the
   *                  document date. Must not be {@code null}.
   * @return A non-null list of non-null mentions in text order, with non-overlapping
   *         spans within the original text; empty if there are no temporal mentions.
   * @throws IllegalArgumentException Thrown if a parameter is {@code null}.
   */
  default List<TemporalExpression> extract(CharSequence text, LocalDate reference) {
    if (reference == null) {
      throw new IllegalArgumentException("reference must not be null");
    }
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    return extract(text);
  }
}
