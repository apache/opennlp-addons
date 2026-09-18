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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.lemmatizer.DictionaryLemmatizer;

/**
 * Runs the manual's PoliMorf reader examples (docbkx {@code lemmatizer.xml}) over a small
 * illustrative dictionary in PoliMorf's {@code surfaceForm\tlemma\ttag} format. It is a
 * hand-built fixture, not the PoliMorf distribution; every value the chapter states is asserted
 * here, so a change breaking this test breaks the manual.
 */
public class PoliMorfDictionaryReaderUsageExampleTest {

  /** U+00A0, whitespace under Unicode but not under {@link String#isBlank()}. */
  private static final String NO_BREAK_SPACE = "\u00A0";

  /** U+202F, likewise whitespace under Unicode but not under {@link String#isBlank()}. */
  private static final String NARROW_NO_BREAK_SPACE = "\u202F";

  private static final String[] ROWS = {
      "pies\tpies\tsubst:sg:nom:m2",
      "psa\tpies\tsubst:sg:gen:m2",
      "psy\tpies\tsubst:pl:nom:m2",
      "kota\tkot\tsubst:sg:gen:m2",
      // Same (form, tag) listed with two lemmas: the reader merges them into one entry.
      "formy\tforma\tsubst:pl:nom:f",
      "formy\tform\tsubst:pl:nom:f",
  };

  private static InputStream dictionary(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  private static DictionaryLemmatizer fixture() throws IOException {
    return PoliMorfDictionaryReader.read(dictionary(String.join("\n", ROWS) + "\n"));
  }

  /** A form and its tag resolve to the base form; a homograph tag or unknown form yields "O". */
  @Test
  void testResolvesFormAndTagToLemma() throws IOException {
    final DictionaryLemmatizer lemmatizer = fixture();

    Assertions.assertArrayEquals(new String[] {"pies"},
        lemmatizer.lemmatize(new String[] {"psa"}, new String[] {"subst:sg:gen:m2"}));
    Assertions.assertArrayEquals(new String[] {"kot"},
        lemmatizer.lemmatize(new String[] {"kota"}, new String[] {"subst:sg:gen:m2"}));
    // A known form under a tag it never carries is a miss.
    Assertions.assertArrayEquals(new String[] {"O"},
        lemmatizer.lemmatize(new String[] {"psa"}, new String[] {"adj:sg:nom:m2:pos"}));
    // An unknown form is a miss.
    Assertions.assertArrayEquals(new String[] {"O"},
        lemmatizer.lemmatize(new String[] {"kanapa"}, new String[] {"subst:sg:nom:f"}));
  }

  /** Lookup is case-insensitive because forms are lower-cased on load. */
  @Test
  void testLookupIsCaseInsensitive() throws IOException {
    Assertions.assertArrayEquals(new String[] {"pies"},
        fixture().lemmatize(new String[] {"Psa"}, new String[] {"subst:sg:gen:m2"}));
  }

  /**
   * The load-time fold must not depend on the JVM's default locale. Under a Turkish default
   * locale, {@link String#toLowerCase()} folds {@code 'I'} to the dotless {@code 'ı'}, storing
   * a key that a lookup for {@code "islandia"} can never reach, and the lemma silently degrades
   * to the {@code "O"} sentinel.
   */
  @Test
  void testLookupIsIndependentOfDefaultLocale() throws IOException {
    final Locale defaultLocale = Locale.getDefault();
    Locale.setDefault(Locale.of("tr", "TR"));
    try {
      final DictionaryLemmatizer lemmatizer = PoliMorfDictionaryReader.read(
          dictionary("Islandia\tIslandia\tsubst:sg:nom:f\n"));

      Assertions.assertArrayEquals(new String[] {"Islandia"},
          lemmatizer.lemmatize(new String[] {"islandia"}, new String[] {"subst:sg:nom:f"}));
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }

  /** Every lemma listed for a form and tag is kept, in first-seen order. */
  @Test
  void testAlternativeLemmasAreMerged() throws IOException {
    final List<List<String>> lemmas =
        fixture().lemmatize(List.of("formy"), List.of("subst:pl:nom:f"));

    Assertions.assertEquals(List.of(List.of("forma", "form")), lemmas);
  }

  /** Blank lines are skipped rather than treated as entries. */
  @Test
  void testBlankLinesAreSkipped() throws IOException {
    final DictionaryLemmatizer lemmatizer = PoliMorfDictionaryReader.read(
        dictionary("\npsa\tpies\tsubst:sg:gen:m2\n   \n"));

    Assertions.assertArrayEquals(new String[] {"pies"},
        lemmatizer.lemmatize(new String[] {"psa"}, new String[] {"subst:sg:gen:m2"}));
  }

  /**
   * A line spelled entirely from no-break spaces counts as blank too, which is what separates the
   * reader's whitespace test from {@link String#isBlank()}.
   */
  @Test
  void testNoBreakSpaceOnlyLineIsSkipped() throws IOException {
    final DictionaryLemmatizer lemmatizer = PoliMorfDictionaryReader.read(
        dictionary(NO_BREAK_SPACE + NARROW_NO_BREAK_SPACE + "\npsa\tpies\tsubst:sg:gen:m2\n"));

    Assertions.assertArrayEquals(new String[] {"pies"},
        lemmatizer.lemmatize(new String[] {"psa"}, new String[] {"subst:sg:gen:m2"}));
  }

  /** A non-blank line with fewer than three fields fails loudly. */
  @Test
  void testTooFewFieldsThrows() {
    Assertions.assertThrows(IOException.class,
        () -> PoliMorfDictionaryReader.read(dictionary("psa\tpies\n")));
  }

  /** A dictionary in a charset other than UTF-8 is read under the charset the caller names. */
  @Test
  void testReadsUnderExplicitCharset() throws IOException {
    // "caf\u00e9" is one byte per character in ISO-8859-1 and two in UTF-8, so reading it
    // under the wrong charset would not round-trip.
    final String row = "caf\u00e9\tcaf\u00e9\tsubst:sg:nom:m2\n";
    final DictionaryLemmatizer lemmatizer = PoliMorfDictionaryReader.read(
        new ByteArrayInputStream(row.getBytes(StandardCharsets.ISO_8859_1)),
        StandardCharsets.ISO_8859_1);

    Assertions.assertArrayEquals(new String[] {"caf\u00e9"},
        lemmatizer.lemmatize(new String[] {"caf\u00e9"}, new String[] {"subst:sg:nom:m2"}));
  }

  /** Null arguments are rejected at the boundary. */
  @Test
  void testNullArgumentsRejected() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PoliMorfDictionaryReader.read(null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PoliMorfDictionaryReader.read(null, StandardCharsets.UTF_8));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PoliMorfDictionaryReader.read(dictionary("psa\tpies\tsubst\n"), null));
  }
}
