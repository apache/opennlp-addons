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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.formats.MorfologikDictionaryReader.BaseFormEncoding;
import opennlp.tools.lemmatizer.DictionaryLemmatizer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks unsigned controls, replacement markers and encoded field boundaries. */
class MorfologikBaseEncodingTest {

  private static final String FORM = "abc".repeat(256);
  private static final String APPEND = "xyz";
  private static final int REPLACE_FORM = 255;
  private static final byte SEPARATOR = '+';

  /** {@return the ordinary unsigned control values} */
  private static Stream<Integer> controls() {
    return IntStream.range(0, REPLACE_FORM).boxed();
  }

  /**
   * Control values are decoded modulo 256 after subtracting the character offset.
   *
   * @param count The trim count or infix position.
   */
  @ParameterizedTest
  @MethodSource("controls")
  void testUnsignedControls(int count) {
    assertAll(
        () -> check(FORM.substring(0, FORM.length() - count) + APPEND,
            FORM, BaseFormEncoding.SUFFIX, count),
        () -> check(FORM.substring(count) + APPEND,
            FORM, BaseFormEncoding.PREFIX, count, 0),
        () -> check(FORM.substring(0, FORM.length() - count) + APPEND,
            FORM, BaseFormEncoding.PREFIX, 0, count),
        () -> check(FORM.substring(0, count) + FORM.substring(count + 1) + APPEND,
            FORM, BaseFormEncoding.INFIX, count, 1, 0),
        () -> check(FORM.substring(0, 1) + FORM.substring(1 + count) + APPEND,
            FORM, BaseFormEncoding.INFIX, 1, count, 0),
        () -> check(FORM.substring(0, 1) + FORM.substring(2, FORM.length() - count) + APPEND,
            FORM, BaseFormEncoding.INFIX, 1, 1, count));
  }

  /** {@return source lengths for full replacement} */
  private static Stream<Integer> replacementLengths() {
    return Stream.of(0, 1, 3, 254, 255, 300, 600);
  }

  /**
   * The reserved removal value replaces the source independently of source length.
   *
   * @param length The source length.
   */
  @ParameterizedTest
  @MethodSource("replacementLengths")
  void testFullReplacement(int length) {
    final String form = FORM.substring(0, length);
    assertAll(
        () -> check(APPEND, form, BaseFormEncoding.SUFFIX, REPLACE_FORM),
        () -> check(APPEND, form, BaseFormEncoding.PREFIX, REPLACE_FORM, 254),
        () -> check(APPEND, form, BaseFormEncoding.PREFIX, 254, REPLACE_FORM),
        () -> check(APPEND, form, BaseFormEncoding.PREFIX, REPLACE_FORM, REPLACE_FORM),
        () -> check(APPEND, form, BaseFormEncoding.INFIX, 254, REPLACE_FORM, 1),
        () -> check(APPEND, form, BaseFormEncoding.INFIX, 254, 1, REPLACE_FORM),
        () -> check(APPEND, form, BaseFormEncoding.INFIX, REPLACE_FORM, REPLACE_FORM, REPLACE_FORM));
  }

  /** An infix position of 255 does not request full replacement. */
  @Test
  void testInfixAtPosition255() {
    check(FORM.substring(0, 255) + FORM.substring(256) + APPEND,
        FORM, BaseFormEncoding.INFIX, 255, 1, 0);
  }

  /**
   * Full replacement may produce an empty base form.
   *
   * @param encoding The encoder.
   */
  @ParameterizedTest
  @EnumSource(BaseFormEncoding.class)
  void testEmptyReplacement(BaseFormEncoding encoding) {
    final byte[] encoded = switch (encoding) {
      case NONE -> new byte[0];
      case SUFFIX -> new byte[] {'@'};
      case PREFIX -> new byte[] {'@', 'A'};
      case INFIX -> new byte[] {'A', '@', 'A'};
    };
    assertArrayEquals(new byte[0], MorfologikDictionaryReader.decodeBaseForm(
        bytes(FORM), encoded, encoding));
  }

  /**
   * Removal counts refer to encoded bytes, including UTF-8 multibyte characters.
   *
   * @param form The source form.
   * @param count The suffix removal count.
   * @param retained The source text after removal.
   */
  @ParameterizedTest
  @CsvSource({"żółwiami,4,żółw", "a😀s,1,a😀", "a😀,4,a"})
  void testUtf8Removal(String form, int count, String retained) {
    check(retained + APPEND, form, BaseFormEncoding.SUFFIX, count);
  }

  /**
   * Individually valid controls cannot remove overlapping source ranges.
   *
   * @param encoding The encoder.
   * @param first The first control.
   * @param middle The next control.
   * @param last The final control for INFIX.
   */
  @ParameterizedTest
  @CsvSource({"PREFIX,4,3,0", "PREFIX,6,1,0", "INFIX,2,3,2", "INFIX,5,1,1", "INFIX,6,0,1"})
  void testOverlappingRemovalsAreRejected(BaseFormEncoding encoding, int first, int middle, int last) {
    final byte[] encoded = encoding == BaseFormEncoding.PREFIX
        ? encoded(first, middle) : encoded(first, middle, last);
    assertThrows(IllegalArgumentException.class, () ->
        MorfologikDictionaryReader.decodeBaseForm(bytes("abcdef"), encoded, encoding));
  }

  /**
   * Adjacent removal ranges are valid, including an empty retained suffix.
   *
   * @param encoding The encoder.
   * @param first The first control.
   * @param middle The next control.
   * @param last The final control for INFIX.
   * @param expected The expected base form.
   */
  @ParameterizedTest
  @CsvSource({
      "SUFFIX,6,0,0,xyz", "PREFIX,0,6,0,xyz", "PREFIX,3,3,0,xyz", "PREFIX,6,0,0,xyz",
      "INFIX,0,6,0,xyz", "INFIX,2,3,1,abxyz", "INFIX,6,0,0,abcdefxyz", "INFIX,0,0,6,xyz"
  })
  void testAdjacentRemovals(BaseFormEncoding encoding, int first, int middle, int last, String expected) {
    final int[] controls = switch (encoding) {
      case NONE -> new int[0];
      case SUFFIX -> new int[] {first};
      case PREFIX -> new int[] {first, middle};
      case INFIX -> new int[] {first, middle, last};
    };
    check(expected, "abcdef", encoding, controls);
  }

  /** {@return control-byte collisions with the '+' separator} */
  private static Stream<Arguments> separatorControls() {
    return Stream.of(
        Arguments.of(BaseFormEncoding.SUFFIX, new int[] {234}, "a".repeat(66) + APPEND),
        Arguments.of(BaseFormEncoding.PREFIX, new int[] {234, 0}, "a".repeat(66) + APPEND),
        Arguments.of(BaseFormEncoding.PREFIX, new int[] {0, 234}, "a".repeat(66) + APPEND),
        Arguments.of(BaseFormEncoding.INFIX, new int[] {234, 1, 0}, "a".repeat(299) + APPEND),
        Arguments.of(BaseFormEncoding.INFIX, new int[] {0, 234, 0}, "a".repeat(66) + APPEND),
        Arguments.of(BaseFormEncoding.INFIX, new int[] {0, 0, 234}, "a".repeat(66) + APPEND));
  }

  /**
   * Control bytes are not parsed as separators between the encoded lemma and tag.
   *
   * @param encoding The encoder.
   * @param controls The control values.
   * @param expected The expected lemma.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @MethodSource("separatorControls")
  void testSeparatorInControls(BaseFormEncoding encoding, int[] controls, String expected)
      throws IOException {
    final String form = "a".repeat(300);
    final byte[] sequence = sequence(form, encoded(controls), "NN");
    final DictionaryLemmatizer dictionary = MorfologikDictionaryReader.read(
        new ByteArrayInputStream(FsaTestData.singleSequence(sequence)),
        SEPARATOR, encoding, StandardCharsets.UTF_8);

    assertArrayEquals(new String[] {expected},
        dictionary.lemmatize(new String[] {form}, new String[] {"NN"}));
  }

  /**
   * A separator control does not introduce a tag in a tagless dictionary.
   *
   * @param encoding The encoder.
   * @param controls The control values.
   * @param expected The expected lemma.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @MethodSource("separatorControls")
  void testSeparatorInTaglessControls(BaseFormEncoding encoding, int[] controls, String expected)
      throws IOException {
    final String form = "a".repeat(300);
    final byte[] sequence = sequence(form, encoded(controls), "");
    final DictionaryLemmatizer dictionary = MorfologikDictionaryReader.read(
        new ByteArrayInputStream(FsaTestData.singleSequence(Arrays.copyOf(sequence, sequence.length - 1))),
        SEPARATOR, encoding, StandardCharsets.UTF_8);
    assertArrayEquals(new String[] {expected},
        dictionary.lemmatize(new String[] {form}, new String[] {""}));
  }

  /**
   * Generated Morfologik 2.2.0 dictionaries use 'B' as the field separator.
   * Their source entries are cats/cat/NN, dogs/dog/NN and mice/mouse/NN.
   *
   * @param encoding The encoder.
   * @param serializer The serializer used to generate the fixture.
   * @param data The Base64 dictionary bytes.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
      "SUFFIX,FSA5Serializer,"
          + "XGZzYQVfKwIAAABeBmOIAWQwAW0GaQZjBmUGQgZEBm8GdQZzBmUGQgZOBk4DAG8GZwZzBkIGQvoAYQZ0UgE=",
      "SUFFIX,CFSA2Serializer,XGZzYcYABw8AdXRtaWdkYURzb2VjTkJAXgMMGwYVw8TMy87IysHJy87NbQDKxcnOThHHQhc=",
      "PREFIX,FSA5Serializer,"
          + "XGZzYQVfKwIAAABeBmOoAWRAAW0GaQZjBmUGQgZBBkQGbwZ1BnMGZQZCBk4GTgMAbwZnBnMGQgZBBkIKAWEGdGIB",
      "PREFIX,CFSA2Serializer,XGZzYcYABxAAdXRtaWdkYURzb2VjTkFCQF4DDB0GFsPEzMvPzsjKwcnLz81tAMrFyc/OTxLHQhg=",
      "INFIX,FSA5Serializer,"
          + "XGZzYQVfKwIAAABeBmPIAWRQAW0GaQZjBmUGQgZBBkEGRAZvBnUGcwZlBkIGTgZOAwBvBmcGcwZCB"
          + "kEGQQZCGgFhBnRyAQ==",
      "INFIX,CFSA2Serializer,"
          + "XGZzYcYABxAAdXRtaWdkYURzb2VjTkJBQF4DDB8GF8PEzMvOz8/IysHJy87NbQDKxcnOz89OE8dCGQ==",
      "NONE,FSA5Serializer,"
          + "XGZzYQVfKwIAAABeBmOoAWQwAW0GaQZjBmUGQgZtBm8GdQZzBmUGQgZOBk4DAG8GZwZzBkIGZAZvB"
          + "mf6AGEGdAZzBkIGYwZhBnT6AA==",
      "NONE,CFSA2Serializer,XGZzYcYABw4AdWl0bWdlZGFOc29jQkBeAwwdBxXEwszGzcTLwcrGzclpAMvFys3Hy0URyMPKzczIQxE="
  })
  void testGeneratedDictionary(BaseFormEncoding encoding, String serializer, String data)
      throws IOException {
    final DictionaryLemmatizer dictionary = MorfologikDictionaryReader.read(
        new ByteArrayInputStream(Base64.getDecoder().decode(data)),
        (byte) 'B', encoding, StandardCharsets.UTF_8);
    assertArrayEquals(new String[] {"cat", "dog", "mouse"}, dictionary.lemmatize(
        new String[] {"cats", "dogs", "mice"}, new String[] {"NN", "NN", "NN"}), serializer);
  }

  /**
   * Invalid encoded entries are reported as input errors by the public reader.
   *
   * @param encoding The encoder.
   * @param encoded The incomplete controls or overlapping removals.
   */
  @ParameterizedTest
  @CsvSource({"SUFFIX,''", "PREFIX,A", "INFIX,AA", "PREFIX,EDxyz", "INFIX,CDCxyz"})
  void testMalformedDictionary(BaseFormEncoding encoding, String encoded) {
    final byte[] sequence = bytes("abcdef+" + encoded);
    final IOException error = assertThrows(IOException.class, () -> MorfologikDictionaryReader.read(
        new ByteArrayInputStream(FsaTestData.singleSequence(sequence)),
        SEPARATOR, encoding, StandardCharsets.UTF_8));
    assertEquals(IllegalArgumentException.class, error.getCause().getClass());
  }

  /**
   * Checks decoding and input preservation.
   *
   * @param expected The expected lemma.
   * @param form The source form.
   * @param encoding The encoder.
   * @param controls The control values.
   */
  private void check(String expected, String form, BaseFormEncoding encoding, int... controls) {
    final byte[] source = bytes(form);
    final byte[] encoded = encoded(controls);
    final byte[] original = encoded.clone();
    assertArrayEquals(bytes(expected),
        MorfologikDictionaryReader.decodeBaseForm(source, encoded, encoding));
    assertArrayEquals(original, encoded);
    assertEquals(form, new String(source, StandardCharsets.UTF_8));
  }

  /**
   * Encodes control values and appends the test suffix.
   *
   * @param controls The unsigned control values.
   * @return The encoded bytes.
   */
  private byte[] encoded(int... controls) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (final int control : controls) {
      out.write(control + 'A');
    }
    out.writeBytes(bytes(APPEND));
    return out.toByteArray();
  }

  /**
   * Combines a surface form, encoded lemma and tag.
   *
   * @param form The surface form.
   * @param encoded The encoded lemma.
   * @param tag The part-of-speech tag.
   * @return The accepted automaton sequence.
   */
  private byte[] sequence(String form, byte[] encoded, String tag) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(bytes(form));
    out.write(SEPARATOR);
    out.writeBytes(encoded);
    out.write(SEPARATOR);
    out.writeBytes(bytes(tag));
    return out.toByteArray();
  }

  /**
   * Converts test text to UTF-8.
   *
   * @param text The test text.
   * @return The encoded text.
   */
  private byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
