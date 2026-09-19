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
package opennlp.tools.util.normalizer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.CharBuffer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests exact symbol mappings, input preservation, and serialization. */
public class SymbolJoinerCharSequenceNormalizerTest {

  /** Shared normalizer under test. */
  private static final SymbolJoinerCharSequenceNormalizer NORMALIZER =
      SymbolJoinerCharSequenceNormalizer.getInstance();

  /**
   * Applies each supported mapping to immutable strings, mutable text, and buffer views.
   *
   * @param symbol The supported symbol.
   * @param word The English replacement.
   */
  @ParameterizedTest
  @CsvSource({
      "&, and",
      "+, plus",
      "@, at",
      "%, percent",
      "§, section",
      "¶, paragraph",
      "°, degree",
      "©, copyright",
      "®, registered",
      "™, trademark"})
  void testWholeTokenSymbolsSpellOut(String symbol, String word) {
    assertEquals(word, NORMALIZER.normalize(symbol).toString());
    assertEquals(word, NORMALIZER.normalize(new StringBuilder(symbol)).toString());
    final CharBuffer buffer = CharBuffer.wrap("x" + symbol + "y");
    buffer.position(1).limit(2);
    assertEquals(word, NORMALIZER.normalize(buffer).toString());
    assertEquals(1, buffer.position());
    assertEquals(2, buffer.limit());
    assertSame(word, NORMALIZER.normalize(word));
  }

  /**
   * Does not expand embedded symbols, padded input, variants, or unsupported forms.
   *
   * @param token The input to preserve.
   */
  @ParameterizedTest
  @ValueSource(strings = {"R&D", "AT&T", "TSR®", "&&", "& ", " &", "(R)", "(TM)", "(C)",
      "©\uFE0F", "®\uFE0F", "\uFF06", "€", "$", "£", "&\u0301", "™ ", "\u00A0&"})
  void testEmbeddedSymbolsAreLeftAlone(String token) {
    assertSame(token, NORMALIZER.normalize(token));
    final CharBuffer buffer = CharBuffer.wrap(token);
    assertSame(buffer, NORMALIZER.normalize(buffer));
    assertEquals(0, buffer.position());
    assertEquals(token.length(), buffer.limit());
  }

  /**
   * Returns the supplied object when there is no mapping.
   *
   * @param text The input to preserve.
   */
  @ParameterizedTest
  @ValueSource(strings = {"and", "court", "", "😀"})
  void testNonSymbolTextsReturnUnchangedWithoutCopying(String text) {
    assertSame(text, NORMALIZER.normalize(text));
    final StringBuilder mutable = new StringBuilder(text);
    assertSame(mutable, NORMALIZER.normalize(mutable));
  }

  /**
   * Checks symbols without converting the supplied CharSequence to a String.
   *
   * @param text The source text.
   */
  @ParameterizedTest
  @ValueSource(strings = {"&", "+", "§", "™", "R&D", " &", "& ", "", "😀"})
  void testDoesNotMaterializeInput(String text) {
    final ConversionCountingText input = new ConversionCountingText(text);
    NORMALIZER.normalize(input);
    assertEquals(0, input.conversions);
  }

  /**
   * Deserialization returns the shared normalizer instance.
   *
   * @throws IOException Thrown if serialization fails.
   * @throws ClassNotFoundException Thrown if the serialized type cannot be loaded.
   */
  @Test
  void testSerializationPreservesSharedInstance() throws IOException, ClassNotFoundException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeObject(NORMALIZER);
    }
    try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      assertSame(NORMALIZER, input.readObject());
    }
  }

  /** Rejects missing input using the public normalizer exception convention. */
  @Test
  void testNullIsRejected() {
    final IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> NORMALIZER.normalize(null));
    assertEquals("text must not be null", error.getMessage());
  }

  /** CharSequence that counts valid conversions to an immutable String. */
  private static final class ConversionCountingText implements CharSequence {

    private final String text;
    private int conversions;

    /**
     * Wraps source text for conversion counting.
     *
     * @param text The source text.
     */
    private ConversionCountingText(String text) {
      this.text = text;
    }

    /** {@inheritDoc} */
    @Override
    public int length() {
      return text.length();
    }

    /** {@inheritDoc} */
    @Override
    public char charAt(int index) {
      return text.charAt(index);
    }

    /** {@inheritDoc} */
    @Override
    public CharSequence subSequence(int start, int end) {
      return text.subSequence(start, end);
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
      conversions++;
      return text;
    }
  }
}
