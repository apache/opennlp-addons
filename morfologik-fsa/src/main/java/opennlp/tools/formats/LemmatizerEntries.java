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

package opennlp.tools.formats;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import opennlp.tools.lemmatizer.DictionaryLemmatizer;
import opennlp.tools.util.StringUtil;

/** Collects distinct lemmas without interpreting characters in dictionary fields. */
final class LemmatizerEntries {

  private final Map<List<String>, LinkedHashSet<String>> entries = new LinkedHashMap<>();

  /** Creates an empty entry collection. */
  LemmatizerEntries() {
  }

  /**
   * Adds a lemma, preserving first-seen order for each form and tag.
   *
   * @param form The surface form.
   * @param tag The part-of-speech tag.
   * @param lemma The lemma, including any delimiter characters.
   */
  void add(String form, String tag, String lemma) {
    entries.computeIfAbsent(List.of(lookupForm(form), tag), key -> new LinkedHashSet<>()).add(lemma);
  }

  /**
   * Builds a lemmatizer with separate form, tag and lemma fields.
   *
   * @return A lemmatizer containing a copy of the collected entries.
   * @throws IOException If the empty dictionary cannot be initialized.
   */
  DictionaryLemmatizer toLemmatizer() throws IOException {
    final DictionaryLemmatizer dictionary =
        MorfologikInputConversion.NONE.newLemmatizer();
    copyTo(dictionary);
    return dictionary;
  }

  /**
   * Copies collected entries into a lemmatizer.
   *
   * @param dictionary The destination dictionary.
   */
  void copyTo(DictionaryLemmatizer dictionary) {
    for (final Map.Entry<List<String>, LinkedHashSet<String>> entry : entries.entrySet()) {
      dictionary.getDictMap().put(entry.getKey(), new ArrayList<>(entry.getValue()));
    }
  }

  /**
   * Applies locale-independent code-point lowercase conversion.
   *
   * @param form The surface form.
   * @return The lookup key.
   */
  static String lookupForm(String form) {
    return StringUtil.toLowerCase(form);
  }
}
