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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.lemmatizer.DictionaryLemmatizer;

/** Checks metadata substitutions before lemmatizer lookup. */
class MorfologikInputConversionTest {

  /**
   * Supplies ordered substitutions, tokens and dictionary forms.
   *
   * @return Valid substitution cases.
   */
  private static Stream<Arguments> conversions() {
    return Stream.of(
        Arguments.of("æ ae", "cæsar", "caesar"),
        Arguments.of("æ ae", "CÆSAR", "caesar"),
        Arguments.of("ĳ ij", "Ĳssel", "ijssel"),
        Arguments.of("ﬃ ffi", "oﬃce", "office"),
        Arguments.of("’ '", "can’t", "can't"),
        Arguments.of("a aa", "a", "aa"),
        Arguments.of("a aa", "aaa", "aaaaaa"),
        Arguments.of("aa a", "aaa", "aa"),
        Arguments.of("aa a", "aaaa", "aa"),
        Arguments.of("ab b", "abab", "bb"),
        Arguments.of("a b,b c", "a", "c"),
        Arguments.of("b c,a b", "a", "b"),
        Arguments.of("ab x,a y", "aba", "xy"),
        Arguments.of("a b,b a", "a", "a"),
        Arguments.of("a a", "aaa", "aaa"),
        Arguments.of("🦊 fox", "🦊", "fox"),
        Arguments.of("a 🦊", "a", "🦊"),
        Arguments.of("c# csharp", "C#", "csharp"),
        Arguments.of("x Y", "x", "y"),
        Arguments.of("οσ os", "ΟΣ", "os"),
        Arguments.of("ος os", "ΟΣ", "οσ"),
        Arguments.of("i dot", "İ", "dot"),
        Arguments.of("i\u0307 dot", "İ", "i"),
        Arguments.of("æ ae", "plain", "plain"),
        Arguments.of("a b,", "a", "b"),
        Arguments.of("a b,,,", "a", "b"),
        Arguments.of("a b,  ", "a", "b"),
        Arguments.of(" a b , c d ", "ac", "bd"),
        Arguments.of("a b,\\tc d", "ac", "bd"),
        Arguments.of(",,,", "a", "a"))
        .flatMap(args -> Stream.of(false, true).map(cfsa ->
            Arguments.of(args.get()[0], args.get()[1], args.get()[2], cfsa)));
  }

  /**
   * Substitutions apply in order to tokens, without changing tags or lemmas.
   *
   * @param rules The conversion property.
   * @param token The input token.
   * @param form The stored dictionary form.
   * @param cfsa Whether to use CFSA2 instead of FSA5.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @MethodSource("conversions")
  void testConversions(String rules, String token, String form, boolean cfsa) throws IOException {
    final DictionaryLemmatizer dictionary = dictionary(rules, form, cfsa);
    Assertions.assertEquals(List.of(List.of("lemma#unchanged")),
        dictionary.lemmatize(List.of(token), List.of("tag")));
    Assertions.assertArrayEquals(new String[] {"lemma#unchanged"},
        dictionary.lemmatize(new String[] {token}, new String[] {"tag"}));
    final String[] tokens = {token};
    final String[] tags = {"tag"};
    dictionary.lemmatize(tokens, tags);
    Assertions.assertArrayEquals(new String[] {token}, tokens);
    Assertions.assertArrayEquals(new String[] {"tag"}, tags);
    Assertions.assertEquals(List.of(List.of("O")),
        dictionary.lemmatize(List.of(token), List.of("wrong-tag")));
    Assertions.assertEquals(List.of(List.of("O")),
        dictionary.lemmatize(List.of("unlisted"), List.of("tag")));
  }

  /**
   * Invalid conversion definitions are rejected before reading the automaton.
   *
   * @param rules The invalid conversion property.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", " ", "a", "a ", " a", "a  b", "a b c", "a\\tb",
      "a b,a c", "a b,a b", ",a b", "a b,,c d", "a b,\\u0000"})
  void testInvalidConversions(String rules) {
    final InputStream unread = new InputStream() {
      /** {@inheritDoc} */
      @Override
      public int read() {
        throw new AssertionError("invalid metadata must not read the dictionary");
      }
    };
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> MorfologikDictionaryReader.read(unread, metadata(rules)));
  }

  /**
   * Empty batches retain the base lemmatizer's behavior.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testEmptyQueries() throws IOException {
    final DictionaryLemmatizer dictionary = dictionary("æ ae", "caesar", false);
    Assertions.assertArrayEquals(new String[0], dictionary.lemmatize(new String[0], new String[0]));
    Assertions.assertEquals(List.of(), dictionary.lemmatize(List.of(), List.of()));
  }

  /**
   * The existing dictionary map remains the source of lookup results.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testDictionaryMap() throws IOException {
    final DictionaryLemmatizer dictionary = dictionary("æ ae", "caesar", false);
    dictionary.getDictMap().put(List.of("caesar", "tag"), List.of("updated"));
    Assertions.assertArrayEquals(new String[] {"updated"},
        dictionary.lemmatize(new String[] {"cæsar"}, new String[] {"tag"}));
  }

  /**
   * Shared dictionaries use independent substitution buffers for concurrent lookups.
   *
   * @throws Exception If loading or a worker fails.
   */
  @Test
  void testConcurrentQueries() throws Exception {
    final DictionaryLemmatizer dictionary = dictionary("ﬃ ffi", "office", true);
    final List<Callable<List<List<String>>>> queries = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      queries.add(() -> dictionary.lemmatize(List.of("oﬃce", "OFFICE"), List.of("tag", "tag")));
    }
    try (var executor = Executors.newFixedThreadPool(4)) {
      for (var result : executor.invokeAll(queries)) {
        Assertions.assertEquals(List.of(List.of("lemma#unchanged"), List.of("lemma#unchanged")),
            result.get());
      }
    }
  }

  /**
   * Constructs an original dictionary with a hash in the lemma.
   *
   * @param rules The conversion property.
   * @param form The stored form.
   * @param cfsa Whether to use CFSA2 instead of FSA5.
   * @return The loaded dictionary.
   * @throws IOException If loading fails.
   */
  private DictionaryLemmatizer dictionary(String rules, String form, boolean cfsa)
      throws IOException {
    final byte[] sequence = (form + "+lemma#unchanged+tag").getBytes(StandardCharsets.UTF_8);
    final byte[] data = cfsa ? FsaTestData.singleCfsaSequence(sequence)
        : FsaTestData.singleSequence(sequence);
    return MorfologikDictionaryReader.read(new ByteArrayInputStream(data), metadata(rules));
  }

  /**
   * Supplies UTF-8 metadata with ordered input substitutions.
   *
   * @param rules The conversion property.
   * @return The metadata stream.
   */
  private InputStream metadata(String rules) {
    return new ByteArrayInputStream(("fsa.dict.separator=+\nfsa.dict.encoding=UTF-8\n"
        + "fsa.dict.encoder=NONE\nfsa.dict.input-conversion=" + rules + "\n")
        .getBytes(StandardCharsets.UTF_8));
  }
}
