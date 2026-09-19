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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.formats.MorfologikDictionaryReader.BaseFormEncoding;
import opennlp.tools.lemmatizer.DictionaryLemmatizer;

/** Checks metadata character encodings and invalid dictionary text. */
class MorfologikMetadataTest {

  /**
   * Supplies Unicode and escaped separator properties in supported single-byte encodings.
   *
   * @return Separator cases for both automaton formats.
   */
  private static Stream<Arguments> separators() {
    return Stream.of(
        Arguments.of("UTF-8", "+", "+"),
        Arguments.of("ISO-8859-1", "é", "é"),
        Arguments.of("ISO-8859-1", "é", "\\u00e9"),
        Arguments.of("windows-1252", "€", "€"),
        Arguments.of("windows-1252", "€", "\\u20ac"),
        Arguments.of("windows-1250", "Ł", "Ł"),
        Arguments.of("windows-1251", "Я", "Я"),
        Arguments.of("UTF-8", "\t", "\\t"),
        Arguments.of("UTF-8", "\n", "\\n"),
        Arguments.of("UTF-8", "\0", "\\u0000"),
        Arguments.of("UTF-8", "\\", "\\\\"))
        .flatMap(args -> Stream.of(false, true).map(cfsa ->
            Arguments.of(args.get()[0], args.get()[1], args.get()[2], cfsa)));
  }

  /**
   * UTF-8 metadata names a character encoded in the dictionary's charset.
   *
   * @param charsetName The dictionary charset.
   * @param separator The separator character.
   * @param property The Unicode or escaped property value.
   * @param cfsa Whether to use CFSA2 instead of FSA5.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @MethodSource("separators")
  void testSeparatorEncoding(String charsetName, String separator, String property, boolean cfsa)
      throws IOException {
    final Charset charset = Charset.forName(charsetName);
    final byte[] sequence = ("word" + separator + "lemma" + separator + "NN").getBytes(charset);
    final DictionaryLemmatizer dictionary = MorfologikDictionaryReader.read(
        automaton(sequence, cfsa), info(charsetName, property));
    Assertions.assertEquals(List.of(List.of("lemma")),
        dictionary.lemmatize(List.of("word"), List.of("NN")));
  }

  /**
   * Supplies separator definitions that cannot encode as a single byte.
   *
   * @return Invalid separator cases.
   */
  private static Stream<Arguments> invalidSeparators() {
    return Stream.of(
        Arguments.of("UTF-8", "\\u00e9"),
        Arguments.of("UTF-8", "\\u20ac"),
        Arguments.of("UTF-16", "+"),
        Arguments.of("UTF-16LE", "+"),
        Arguments.of("US-ASCII", "\\u20ac"),
        Arguments.of("ISO-8859-1", "\\u20ac"),
        Arguments.of("UTF-8", "\\uD800"),
        Arguments.of("UTF-8", "\\uDC00"),
        Arguments.of("UTF-8", "\\uD83D\\uDE00"),
        Arguments.of("UTF-8", "++"),
        Arguments.of("UTF-8", ""));
  }

  /**
   * Invalid metadata is rejected before the dictionary stream is read.
   *
   * @param charset The dictionary charset.
   * @param separator The separator property.
   */
  @ParameterizedTest
  @MethodSource("invalidSeparators")
  void testInvalidSeparators(String charset, String separator) {
    final InputStream unread = new InputStream() {
      /** {@inheritDoc} */
      @Override
      public int read() {
        throw new AssertionError("invalid metadata must not read the dictionary");
      }
    };
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> MorfologikDictionaryReader.read(unread, info(charset, separator)));
  }

  /**
   * Field bytes must be valid in the dictionary charset.
   *
   * @param field The field to corrupt.
   * @param cfsa Whether to use CFSA2 instead of FSA5.
   */
  @ParameterizedTest
  @CsvSource({"0,false", "1,false", "2,false", "0,true", "1,true", "2,true"})
  void testInvalidBinaryText(int field, boolean cfsa) {
    final byte[] sequence = {'x', '+', 'y', '+', 'z'};
    sequence[field * 2] = (byte) 0xff;
    Assertions.assertThrows(IOException.class,
        () -> MorfologikDictionaryReader.read(automaton(sequence, cfsa), (byte) '+',
            BaseFormEncoding.NONE, StandardCharsets.UTF_8));
  }

  /** A valid surface form can still decode to an incomplete UTF-8 lemma. */
  @Test
  void testInvalidDecodedLemma() {
    final byte[] sequence = {(byte) 0xc3, (byte) 0xa9, '+', 'B', '+', 'N'};
    Assertions.assertThrows(IOException.class,
        () -> MorfologikDictionaryReader.read(automaton(sequence, false), (byte) '+',
            BaseFormEncoding.SUFFIX, StandardCharsets.UTF_8));
  }

  /**
   * Invalid UTF-8 must not become a replacement character in a text dictionary.
   *
   * @param field The field to corrupt.
   */
  @ParameterizedTest
  @CsvSource({"0", "1", "2"})
  void testInvalidTextTable(int field) {
    final byte[] row = {'x', '\t', 'y', '\t', 'z'};
    row[field * 2] = (byte) 0xff;
    Assertions.assertThrows(IOException.class,
        () -> PoliMorfDictionaryReader.read(new ByteArrayInputStream(row)));
  }

  /** Invalid metadata bytes are reported even when they occur in a comment. */
  @Test
  void testInvalidMetadataUtf8() {
    final byte[] metadata = ("fsa.dict.separator=+\nfsa.dict.encoding=UTF-8\n"
        + "fsa.dict.encoder=NONE\n#x").getBytes(StandardCharsets.UTF_8);
    metadata[metadata.length - 1] = (byte) 0xff;
    Assertions.assertThrows(IOException.class, () -> MorfologikDictionaryReader.read(
        automaton("word+lemma+NN".getBytes(StandardCharsets.UTF_8), false),
        new ByteArrayInputStream(metadata)));
  }

  /**
   * Encoder names allow surrounding ASCII whitespace in properties files.
   *
   * @param encoder The encoder property value.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {"NONE ", " NONE ", "\\tNoNe\\t", "\\fnone\\f",
      "\\nnone\\r", "\\u0000NONE\\u0000"})
  void testEncoderWhitespace(String encoder) throws IOException {
    final String metadata = "fsa.dict.separator=+\nfsa.dict.encoding=UTF-8\n"
        + "fsa.dict.encoder=" + encoder + "\n";
    final DictionaryLemmatizer dictionary = MorfologikDictionaryReader.read(
        automaton("word+lemma+NN".getBytes(StandardCharsets.UTF_8), false),
        new ByteArrayInputStream(metadata.getBytes(StandardCharsets.UTF_8)));
    Assertions.assertEquals(List.of(List.of("lemma")),
        dictionary.lemmatize(List.of("word"), List.of("NN")));
  }

  /**
   * Whitespace handling does not allow unknown or internally separated encoder names.
   *
   * @param encoder The invalid encoder property.
   */
  @ParameterizedTest
  @ValueSource(strings = {"NO NE", "none\\u00a0", "\\u2003NONE", " NONE extra ", " ", "\\t\\n"})
  void testInvalidEncoderWhitespace(String encoder) {
    final String metadata = "fsa.dict.separator=+\nfsa.dict.encoding=UTF-8\n"
        + "fsa.dict.encoder=" + encoder + "\n";
    Assertions.assertThrows(IllegalArgumentException.class, () -> MorfologikDictionaryReader.read(
        InputStream.nullInputStream(),
        new ByteArrayInputStream(metadata.getBytes(StandardCharsets.UTF_8))));
  }

  /**
   * Unmappable bytes are rejected by both dictionary readers.
   *
   * @param field The field to corrupt.
   */
  @ParameterizedTest
  @CsvSource({"0", "1", "2"})
  void testUnmappableText(int field) {
    final Charset charset = Charset.forName("windows-1252");
    final byte[] row = {'x', '\t', 'y', '\t', 'z'};
    row[field * 2] = (byte) 0x81;
    Assertions.assertThrows(IOException.class,
        () -> PoliMorfDictionaryReader.read(new ByteArrayInputStream(row), charset));
    row[1] = '+';
    row[3] = '+';
    Assertions.assertThrows(IOException.class,
        () -> MorfologikDictionaryReader.read(automaton(row, true),
            (byte) '+', BaseFormEncoding.NONE, charset));
  }

  /**
   * Returns metadata encoded as UTF-8, independently of the dictionary encoding.
   *
   * @param charset The dictionary charset.
   * @param separator The separator property.
   * @return The metadata stream.
   */
  private InputStream info(String charset, String separator) {
    return new ByteArrayInputStream(("fsa.dict.separator=" + separator
        + "\nfsa.dict.encoding=" + charset + "\nfsa.dict.encoder=NONE\n")
        .getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Builds one accepted sequence in either supported automaton format.
   *
   * @param sequence The accepted bytes.
   * @param cfsa Whether to use CFSA2 instead of FSA5.
   * @return The dictionary stream.
   */
  private InputStream automaton(byte[] sequence, boolean cfsa) {
    return new ByteArrayInputStream(cfsa ? FsaTestData.singleCfsaSequence(sequence)
        : FsaTestData.singleSequence(sequence));
  }
}
