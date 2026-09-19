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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.lemmatizer.DictionaryLemmatizer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks complete expansion of dictionaries with shared suffixes. */
class MorfologikDictionaryExpansionTest {

  private static final int FORM_BITS = 14;
  private static final int FORM_COUNT = 1 << FORM_BITS;
  private static final String NOUN = "NN";
  private static final String VERB = "VB";

  /*
   * Original entries serialized with Morfologik 2.2.0. Each 14-character combination of
   * x and y is a stem, with entries stem+s+B+NN, stem+s+B-alt+NN and stem+s+B+VB.
   * All four automata accept 49,152 sequences.
   */
  private enum Fixture {
    FSA5("XGZzYQVfKwIAAABeBnhQAHkGeHgAeQZ4oAB5BnjIAHkGePAAeQZ4GAF5BnhAAXkGeGgBeQZ4kAF5Bni4"
        + "AXkGeOABeQZ4CAJ5BngwAnkGeFgCeQZzBisGQgYrGAMtBmEGbAZ0BisGTgZOAwBOAANWBkIDAA=="),
    CFSA2("XGZzYcYABwwAdHNsYVYtQk4reXhAXgOLyovKi8qLyovKi8qLyovKi8qLyovKi8qLyovKwsnHCSzG"
        + "xMPBychoAAgqxWcA"),
    FSA5_NUMBERS("XGZzYQVfKyIAAAAAAAAAXgYAwHiAAHkGAGB4uAB5BgAwePAAeQYAGHgoAXkGAAx4YAF5BgAG"
        + "eJgBeQYAA3jQAXkGgAF4CAJ5BsAAeEACeQZgAHh4AnkGMAB4sAJ5BhgAeOgCeQYMAHggA3kGBgB4WAN5"
        + "BgMAcwYDACsGAwBCBgMAK7gELQYBAGEGAQBsBgEAdAYBACsGAQBOBgEATgMAAgBOkARWBgEAQgMA"),
    CFSA2_NUMBERS("XGZzYcYBBwwAdHNsYVYtQk4reXgAQF4EgIADi8qAwAGLyoBgi8qAMIvKgBiLyoAMi8qA"
        + "BovKgAOLysABi8pgi8owi8oYi8oMi8oGi8oDwgPJA8cDCVDGAcQBwwHBAckByAFoAAIITcUBZwA=");

    private final String encoded;

    /**
     * Stores a serialized test automaton.
     *
     * @param encoded The Base64 data.
     */
    Fixture(String encoded) {
      this.encoded = encoded;
    }
  }

  /** {@return each format through explicit settings and metadata with input conversion} */
  private static Stream<Arguments> dictionaries() {
    return Stream.of(Fixture.values()).flatMap(fixture ->
        Stream.of(false, true).map(metadata -> Arguments.of(fixture, metadata)));
  }

  /**
   * Shared automaton nodes are visited under every surface form.
   *
   * @param fixture The automaton format and node-number setting.
   * @param metadata Whether to load settings and substitutions from metadata.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @MethodSource("dictionaries")
  void testExpandedDictionary(Fixture fixture, boolean metadata) throws IOException {
    final byte[] data = Base64.getDecoder().decode(fixture.encoded);
    final DictionaryLemmatizer dictionary;
    try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
      if (metadata) {
        final String properties = "fsa.dict.separator=+\nfsa.dict.encoding=UTF-8\n"
            + "fsa.dict.encoder=SUFFIX\nfsa.dict.input-conversion=z x\n";
        try (ByteArrayInputStream info =
                 new ByteArrayInputStream(properties.getBytes(StandardCharsets.UTF_8))) {
          dictionary = MorfologikDictionaryReader.read(input, info);
        }
      } else {
        dictionary = MorfologikDictionaryReader.read(input, (byte) '+',
            MorfologikDictionaryReader.BaseFormEncoding.SUFFIX, StandardCharsets.UTF_8);
      }
    }

    assertEquals(FORM_COUNT * 2, dictionary.getDictMap().size());
    final List<String> tokens = new ArrayList<>(FORM_COUNT);
    final List<List<String>> nouns = new ArrayList<>(FORM_COUNT);
    final List<List<String>> verbs = new ArrayList<>(FORM_COUNT);
    final String[] firstLemmas = new String[FORM_COUNT];
    for (int index = 0; index < FORM_COUNT; index++) {
      final String base = stem(index);
      final String surface = base + "s";
      tokens.add(metadata ? surface.replace('x', 'z') : surface);
      nouns.add(List.of(base, base + "-alt"));
      verbs.add(List.of(base));
      firstLemmas[index] = base;
    }
    assertEquals(nouns, dictionary.lemmatize(tokens, Collections.nCopies(FORM_COUNT, NOUN)));
    assertEquals(verbs, dictionary.lemmatize(tokens, Collections.nCopies(FORM_COUNT, VERB)));
    final String[] tokenArray = tokens.toArray(String[]::new);
    final String[] nounTags = Collections.nCopies(FORM_COUNT, NOUN).toArray(String[]::new);
    final String[] verbTags = Collections.nCopies(FORM_COUNT, VERB).toArray(String[]::new);
    assertArrayEquals(firstLemmas, dictionary.lemmatize(tokenArray, nounTags));
    assertArrayEquals(firstLemmas, dictionary.lemmatize(tokenArray, verbTags));
    assertEquals(tokens, List.of(tokenArray));
    assertEquals(List.of(List.of("O"), List.of("O")),
        dictionary.lemmatize(List.of("xy", tokens.getFirst()), List.of(NOUN, "JJ")));
  }

  /**
   * Creates one of the 14-character x/y stems.
   *
   * @param index The stem index.
   * @return The stem without the ending s.
   */
  private String stem(int index) {
    final StringBuilder result = new StringBuilder(FORM_BITS);
    for (int bit = FORM_BITS - 1; bit >= 0; bit--) {
      result.append((index & (1 << bit)) == 0 ? 'x' : 'y');
    }
    return result.toString();
  }
}
