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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import opennlp.tools.lemmatizer.DictionaryLemmatizer;
import opennlp.tools.util.StringUtil;

/**
 * Builds a {@link DictionaryLemmatizer} from a morphological dictionary laid out as one
 * tab-separated {@code surfaceForm\tlemma\ttag} row per entry.
 *
 * <p>This is the layout published by PoliMorf, the successor grammatical dictionary of Polish
 * (BSD 2-Clause), and emitted by exporting a morfologik dictionary to text; it is otherwise
 * language-agnostic. This reader groups distinct lemmas by form and tag in first-seen order.
 * A {@code #} in a lemma is retained, not treated as an alternative separator.
 * Columns after the tag are ignored.
 * Dictionary data is supplied by the caller; none is bundled with OpenNLP.</p>
 *
 * <p>Surface forms are lower-cased with {@link StringUtil#toLowerCase(String)} for both
 * stored entries and query tokens. Tags are unchanged and must match the part-of-speech
 * tags supplied for lookup. Invalid character data is rejected on load.</p>
 *
 * <p>This class is stateless, so its methods may be called concurrently.</p>
 */
public final class PoliMorfDictionaryReader {

  /** Separates columns in the source table. */
  private static final char FIELD_SEPARATOR = '\t';

  /** The number of columns a non-blank row must carry. */
  private static final int MIN_FIELDS = 3;

  /** Prevents utility-class instantiation. */
  private PoliMorfDictionaryReader() {
  }

  /**
   * Reads a UTF-8 {@code surfaceForm\tlemma\ttag} dictionary into a {@link DictionaryLemmatizer}.
   *
   * @param dictionary The dictionary referenced by an open {@link InputStream}. Must not be
   *                   {@code null}.
   * @return A {@link DictionaryLemmatizer} over the adapted entries.
   * @throws IllegalArgumentException Thrown if {@code dictionary} is {@code null}.
   * @throws IOException On IO errors, missing tab-separated fields or invalid character data.
   */
  public static DictionaryLemmatizer read(InputStream dictionary) throws IOException {
    return read(dictionary, StandardCharsets.UTF_8);
  }

  /**
   * Reads a {@code surfaceForm\tlemma\ttag} dictionary into a {@link DictionaryLemmatizer}.
   * Surface forms are folded to lower case with {@link StringUtil#toLowerCase(String)}, matching the
   * conversion applied to query tokens by the returned lemmatizer.
   *
   * @param dictionary The dictionary referenced by an open {@link InputStream}. Must not be
   *                   {@code null}.
   * @param charset    The character encoding of the dictionary. Must not be {@code null}.
   * @return A {@link DictionaryLemmatizer} over the adapted entries.
   * @throws IllegalArgumentException Thrown if {@code dictionary} or {@code charset} is
   *                                  {@code null}.
   * @throws IOException On IO errors, missing tab-separated fields or invalid character data.
   */
  public static DictionaryLemmatizer read(InputStream dictionary, Charset charset)
      throws IOException {
    if (dictionary == null) {
      throw new IllegalArgumentException("dictionary must not be null");
    }
    if (charset == null) {
      throw new IllegalArgumentException("charset must not be null");
    }

    final LemmatizerEntries entries = new LemmatizerEntries();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(dictionary, charset.newDecoder()))) {
      String line;
      int lineNumber = 0;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (isBlank(line)) {
          continue;
        }
        final int firstTab = line.indexOf(FIELD_SEPARATOR);
        final int secondTab = firstTab < 0 ? -1 : line.indexOf(FIELD_SEPARATOR, firstTab + 1);
        if (secondTab < 0) {
          throw new IOException("PoliMorf line " + lineNumber
              + " has fewer than " + MIN_FIELDS + " tab-separated fields: " + line);
        }
        final int thirdTab = line.indexOf(FIELD_SEPARATOR, secondTab + 1);
        final String form = line.substring(0, firstTab);
        final String lemma = line.substring(firstTab + 1, secondTab);
        final String tag = line.substring(secondTab + 1, thirdTab < 0 ? line.length() : thirdTab);
        entries.add(form, tag, lemma);
      }
    }

    return entries.toLemmatizer();
  }

  /**
   * Determines whether a line carries no content: empty, or made up entirely of code
   * points the toolkit treats as whitespace. This follows {@link StringUtil#isWhitespace(int)}
   * rather than {@link String#isBlank()}, which leaves out the no-break spaces, so a line
   * spelled entirely from them is skipped rather than parsed as an entry.
   *
   * @param line The line to examine. Must not be {@code null}.
   * @return {@code true} if {@code line} is empty or all whitespace.
   */
  private static boolean isBlank(CharSequence line) {
    for (int i = 0; i < line.length(); ) {
      final int codePoint = Character.codePointAt(line, i);
      if (!StringUtil.isWhitespace(codePoint)) {
        return false;
      }
      i += Character.charCount(codePoint);
    }
    return true;
  }
}
