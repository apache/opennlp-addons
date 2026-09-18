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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import opennlp.tools.lemmatizer.DictionaryLemmatizer;

/**
 * Applies ordered substitutions from Morfologik input-conversion metadata.
 * See the
 * <a href="https://github.com/morfologik/morfologik-stemming/blob/2.2.0/morfologik-stemming/src/main/java/morfologik/stemming/DictionaryAttribute.java">metadata syntax</a>.
 */
final class MorfologikInputConversion {

  static final MorfologikInputConversion NONE = new MorfologikInputConversion(List.of());

  static final String PROPERTY = "fsa.dict.input-conversion";
  private static final char PAIR_SEPARATOR = ',';
  private static final char WORD_SEPARATOR = ' ';

  private final List<Replacement> replacements;

  /**
   * Stores immutable replacement rules in metadata order.
   *
   * @param replacements The ordered rules.
   */
  private MorfologikInputConversion(List<Replacement> replacements) {
    this.replacements = List.copyOf(replacements);
  }

  /**
   * Parses comma-separated pairs containing a single space between strings.
   *
   * @param value The property value after Java properties escaping.
   * @return The ordered substitutions.
   * @throws IllegalArgumentException If a substitution is malformed or repeats an input string.
   */
  static MorfologikInputConversion parse(String value) {
    final List<String> pairs = new ArrayList<>();
    int start = 0;
    boolean separated = false;
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) == PAIR_SEPARATOR) {
        pairs.add(value.substring(start, i));
        separated = true;
        start = i + 1;
        while (start < value.length() && isSeparatorWhitespace(value.charAt(start))) {
          start++;
        }
        i = start - 1;
      }
    }
    pairs.add(value.substring(start));
    if (separated) {
      while (!pairs.isEmpty() && pairs.getLast().isEmpty()) {
        pairs.removeLast();
      }
    }

    final List<Replacement> replacements = new ArrayList<>();
    final Set<String> inputs = new HashSet<>();
    for (String pair : pairs) {
      final String trimmed = pair.trim();
      final int space = trimmed.indexOf(WORD_SEPARATOR);
      if (space <= 0 || space == trimmed.length() - 1
          || trimmed.indexOf(WORD_SEPARATOR, space + 1) >= 0) {
        throw new IllegalArgumentException(PROPERTY + " requires space-separated string pairs");
      }
      final String from = trimmed.substring(0, space);
      final String to = trimmed.substring(space + 1);
      if (!inputs.add(from)) {
        throw new IllegalArgumentException(PROPERTY + " repeats input string: " + from);
      }
      if (!from.equals(to)) {
        replacements.add(new Replacement(from, to));
      }
    }
    return new MorfologikInputConversion(replacements);
  }

  /**
   * Tests the ASCII whitespace allowed after a comma.
   *
   * @param value The character to test.
   * @return Whether the character is separator whitespace.
   */
  private static boolean isSeparatorWhitespace(char value) {
    return value == WORD_SEPARATOR || value == '\t' || value == '\n' || value == '\r'
        || value == '\f' || value == '\u000b';
  }

  /**
   * Tests whether the metadata has any substitutions.
   *
   * @return Whether the rule list is empty.
   */
  boolean isEmpty() {
    return replacements.isEmpty();
  }

  /**
   * Creates an empty lemmatizer that applies these substitutions to query tokens.
   *
   * @return The lemmatizer.
   * @throws IOException If initialization fails.
   */
  DictionaryLemmatizer newLemmatizer() throws IOException {
    return new ConvertedLemmatizer();
  }

  /**
   * Replaces non-overlapping matches for each rule in metadata order.
   *
   * @param token The query token.
   * @return The substituted token, or the input object when no rule matches.
   */
  private String apply(String token) {
    StringBuilder result = null;
    for (Replacement replacement : replacements) {
      int index = result == null ? token.indexOf(replacement.from())
          : result.indexOf(replacement.from());
      if (index < 0) {
        continue;
      }
      if (result == null) {
        result = new StringBuilder(token);
      }
      while (index >= 0) {
        result.replace(index, index + replacement.from().length(), replacement.to());
        index = result.indexOf(replacement.from(), index + replacement.to().length());
      }
    }
    return result == null ? token : result.toString();
  }

  /**
   * Lower-cases the query with the dictionary's lookup conversion before substitution.
   *
   * @param token The query token.
   * @return The converted token.
   */
  private String convert(String token) {
    return LemmatizerEntries.lookupForm(apply(LemmatizerEntries.lookupForm(token)));
  }

  /**
   * An ordered string substitution.
   *
   * @param from The input string.
   * @param to The replacement string.
   */
  private record Replacement(String from, String to) {
  }

  /** Adds token conversion without changing the DictionaryLemmatizer return type. */
  private final class ConvertedLemmatizer extends DictionaryLemmatizer {

    /**
     * Initializes an empty dictionary.
     *
     * @throws IOException If initialization fails.
     */
    private ConvertedLemmatizer() throws IOException {
      super(InputStream.nullInputStream());
    }

    /** {@inheritDoc} */
    @Override
    public String[] lemmatize(String[] tokens, String[] tags) {
      final String[] converted = new String[tokens.length];
      for (int i = 0; i < tokens.length; i++) {
        converted[i] = convert(tokens[i]);
      }
      return super.lemmatize(converted, tags);
    }

    /** {@inheritDoc} */
    @Override
    public List<List<String>> lemmatize(List<String> tokens, List<String> tags) {
      final List<String> converted = new ArrayList<>(tokens.size());
      for (String token : tokens) {
        converted.add(convert(token));
      }
      return super.lemmatize(converted, tags);
    }
  }
}
