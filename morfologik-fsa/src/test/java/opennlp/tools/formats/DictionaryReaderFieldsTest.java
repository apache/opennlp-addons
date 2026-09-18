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
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.formats.MorfologikDictionaryReader.BaseFormEncoding;
import opennlp.tools.lemmatizer.DictionaryLemmatizer;

/** Checks preservation of dictionary field contents. */
class DictionaryReaderFieldsTest {

  static Stream<Arguments> codePointCasePairs() {
    return Stream.of(new String[] {"İ", "i"}, new String[] {"ΟΣ", "οσ"},
            new String[] {"𐐀", "𐐨"})
        .flatMap(pair -> Stream.of("text", "binary", "metadata")
            .map(reader -> Arguments.of(reader, pair[0], pair[1])));
  }

  /**
   * Stored forms and queries use the same code-point case conversion.
   *
   * @param reader The dictionary input format.
   * @param upper The uppercase surface form.
   * @param lower The code-point lowercase form.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @MethodSource("codePointCasePairs")
  void testCodePointCaseLookup(String reader, String upper, String lower) throws IOException {
    final DictionaryLemmatizer dictionary;
    if (reader.equals("text")) {
      dictionary = PoliMorfDictionaryReader.read(text(upper + "\tLemma\tNN\n"));
    } else {
      final byte[] automaton = FsaTestData.singleSequence(
          (upper + "+Lemma+NN").getBytes(StandardCharsets.UTF_8));
      if (reader.equals("metadata")) {
        dictionary = MorfologikDictionaryReader.read(new ByteArrayInputStream(automaton),
            text("fsa.dict.separator=+\nfsa.dict.encoding=UTF-8\nfsa.dict.encoder=NONE\n"
                + "fsa.dict.input-conversion=alias " + upper + "\n"));
      } else {
        dictionary = MorfologikDictionaryReader.read(new ByteArrayInputStream(automaton),
            (byte) '+', BaseFormEncoding.NONE, StandardCharsets.UTF_8);
      }
    }
    final List<String> queries = reader.equals("metadata")
        ? List.of(upper, lower, "alias") : List.of(upper, lower);
    for (String query : queries) {
      Assertions.assertArrayEquals(new String[] {"Lemma"},
          dictionary.lemmatize(new String[] {query}, new String[] {"NN"}), query);
      Assertions.assertEquals(List.of(List.of("Lemma")),
          dictionary.lemmatize(List.of(query), List.of("NN")), query);
    }
    Assertions.assertArrayEquals(new String[] {"O"},
        dictionary.lemmatize(new String[] {lower}, new String[] {"nn"}));
  }


  /**
   * Supplies field contents, encoders and both automaton formats.
   *
   * @return The field cases.
   */
  private static Stream<Arguments> binaryFields() {
    return Stream.of(false, true).flatMap(cfsa ->
        Stream.of(BaseFormEncoding.values()).flatMap(encoding ->
            Stream.of("", "#", "C#", "#stem#", "\t", "\r", "\n", "a\tb\nc\rd",
                "żółć", "𐐀", "\u0000", "\ufffd")
                .flatMap(value -> Stream.of(0, 1, 2)
                    .map(field -> Arguments.of(cfsa, encoding, field, value)))));
  }

  /**
   * Dictionary field contents are preserved during loading.
   *
   * @param cfsa Whether to use CFSA2 instead of FSA5.
   * @param encoding The base-form encoder.
   * @param field The field to vary.
   * @param value The field contents.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @MethodSource("binaryFields")
  void testBinaryFields(boolean cfsa, BaseFormEncoding encoding, int field, String value)
      throws IOException {
    final String[] fields = {"Token", "lemma", "NN"};
    fields[field] = value;
    final String controls = switch (encoding) {
      case NONE -> "";
      case SUFFIX -> "@";
      case PREFIX -> "@A";
      case INFIX -> "A@A";
    };
    final byte[] bytes = (fields[0] + "+" + controls + fields[1] + "+" + fields[2])
        .getBytes(StandardCharsets.UTF_8);
    final byte[] automaton = cfsa ? FsaTestData.singleCfsaSequence(bytes)
        : FsaTestData.singleSequence(bytes);
    final DictionaryLemmatizer dictionary = MorfologikDictionaryReader.read(
        new ByteArrayInputStream(automaton), (byte) '+', encoding, StandardCharsets.UTF_8);

    Assertions.assertEquals(List.of(List.of(fields[1])),
        dictionary.lemmatize(List.of(fields[0]), List.of(fields[2])));
    Assertions.assertArrayEquals(new String[] {fields[1]},
        dictionary.lemmatize(new String[] {fields[0]}, new String[] {fields[2]}));
    Assertions.assertEquals(1, dictionary.getDictMap().size());
  }

  /**
   * The PoliMorf format treats a hash as part of a lemma, including at either end.
   *
   * @param lemma The lemma field.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "#", "C#", "#stem#", "stem#", "żółć", "𐐀"})
  void testTextLemmas(String lemma) throws IOException {
    final DictionaryLemmatizer dictionary =
        PoliMorfDictionaryReader.read(text("Token\t" + lemma + "\tNN\n"));
    Assertions.assertEquals(List.of(List.of(lemma)),
        dictionary.lemmatize(List.of("TOKEN"), List.of("NN")));
  }

  /**
   * Repeated entries do not duplicate alternatives or split a hash.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testTextAlternativeOrder() throws IOException {
    final DictionaryLemmatizer dictionary = PoliMorfDictionaryReader.read(
        text("symbols\tC#\tNN\nsymbols\tC\tNN\nsymbols\tC#\tNN\nsymbols\t\tNN\n"));
    Assertions.assertEquals(List.of(List.of("C#", "C", "")),
        dictionary.lemmatize(List.of("symbols"), List.of("NN")));
    Assertions.assertArrayEquals(new String[] {"C#"},
        dictionary.lemmatize(new String[] {"symbols"}, new String[] {"NN"}));
  }

  /**
   * Empty tags are retained; extra columns are ignored.
   *
   * @param row The input line.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {"Token\tlemma\t", "Token\tlemma\t\textra",
      "Token\tlemma\t\textra\t", "Token\tlemma\t\t\t"})
  void testTextEmptyTags(String row) throws IOException {
    final DictionaryLemmatizer dictionary = PoliMorfDictionaryReader.read(text(row));
    Assertions.assertEquals(List.of(List.of("lemma")),
        dictionary.lemmatize(List.of("Token"), List.of("")));
  }

  /**
   * A malformed line reports the line number, including skipped blank lines.
   *
   * @param row The malformed line.
   */
  @ParameterizedTest
  @ValueSource(strings = {"Token", "Token\tlemma", "Token\t"})
  void testTextMissingFields(String row) {
    final IOException error = Assertions.assertThrows(IOException.class,
        () -> PoliMorfDictionaryReader.read(text("\nToken\tlemma\tNN\n" + row)));
    Assertions.assertTrue(error.getMessage().contains("line 3"));
  }

  /**
   * Load and lookup use the same lowercase conversion.
   *
   * @param token The token to load and query.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {"ΟΣ", "İ", "𐐀"})
  void testTextCaseMapping(String token) throws IOException {
    final DictionaryLemmatizer dictionary =
        PoliMorfDictionaryReader.read(text(token + "\tlemma\tNN"));
    Assertions.assertArrayEquals(new String[] {"lemma"},
        dictionary.lemmatize(new String[] {token}, new String[] {"NN"}));
  }

  /**
   * Distinct form/tag pairs remain separate when either field contains a tab.
   * Fixtures were generated from original entries with Morfologik 2.2.0.
   *
   * @param fixture The serialized FSA5 or CFSA2 dictionary.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "XGZzYQVfKwEAAF4GYQYJ8CsGbAZlBm0GbQZhBjIGKwZiBgkGYwNiBisGbAZlBm0GbQZhBiMGMQYr4g==",
      "XGZzYcYABwwAYzIxI2xlYglhbStAXgPJCBLLxcbKysnCy8fIYQDHy8XGysrJxMNLEA=="
  })
  void testDistinctBinaryKeys(String fixture) throws IOException {
    final DictionaryLemmatizer dictionary = MorfologikDictionaryReader.read(
        new ByteArrayInputStream(Base64.getDecoder().decode(fixture)),
        (byte) '+', BaseFormEncoding.NONE, StandardCharsets.UTF_8);
    Assertions.assertEquals(List.of(List.of("lemma#1"), List.of("lemma2")),
        dictionary.lemmatize(List.of("a\tb", "a"), List.of("c", "b\tc")));
    Assertions.assertEquals(2, dictionary.getDictMap().size());
  }

  /**
   * Returns a UTF-8 table stream.
   *
   * @param value The table.
   * @return The table stream.
   */
  private InputStream text(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }
}
