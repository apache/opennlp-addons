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

package opennlp.tools.glossary;

import java.util.List;

/**
 * The interface for glossary matchers, which find registered terms, including multiword
 * terms, in a text and report each hit as a {@link GlossaryMatch} with its span in the
 * original text.
 *
 * <p>Thread safety is implementation specific.</p>
 *
 * @see GlossaryEntry
 * @see GlossaryMatch
 * @since 3.0.0
 */
public interface GlossaryMatcher {

  /**
   * Finds all glossary hits in a text.
   *
   * @param text The text to scan. Must not be {@code null}.
   * @return The hits in text order, non-overlapping according to
   *         {@link opennlp.tools.util.Span#intersects(opennlp.tools.util.Span)}.
   *         Never {@code null}; empty when no registered term occurs in the text.
   * @throws IllegalArgumentException Thrown if {@code text} is {@code null}.
   */
  List<GlossaryMatch> match(CharSequence text);
}
