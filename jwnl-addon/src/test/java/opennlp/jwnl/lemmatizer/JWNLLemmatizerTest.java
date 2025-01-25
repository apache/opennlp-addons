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

package opennlp.jwnl.lemmatizer;

import java.util.List;
import java.util.stream.Stream;

import net.sf.extjwnl.JWNLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JWNLLemmatizerTest {

  // SUT
  private JWNLLemmatizer lemmatizer;

  @BeforeEach
  public void setUp() throws JWNLException {
    lemmatizer = new JWNLLemmatizer();
  }

  @ParameterizedTest
  @MethodSource("provideData")
  public void testLemmatize(String word, String posTag, String expectedLemma) {
    String lemma = lemmatizer.lemmatize(word, posTag);
    assertNotNull(lemma);
    assertEquals(expectedLemma, lemma);
  }

  @ParameterizedTest
  @MethodSource("provideData")
  public void testLemmatizeArray(String word, String posTag, String expectedLemma) {
    String[] lemma = lemmatizer.lemmatize(new String[]{word}, new String[]{posTag});
    assertNotNull(lemma);
    assertEquals(1, lemma.length);
    assertEquals(expectedLemma, lemma[0]);
  }

  @Test
  public void testLemmatizeList() {
    assertThrows(UnsupportedOperationException.class, () ->
            lemmatizer.lemmatize(List.of("mouse"), List.of("NN")));
  }

  private static Stream<Arguments> provideData() {
    return Stream.of(
        Arguments.of("the", "DT", "the"),
        Arguments.of("cats", "NN", "cat"),
        Arguments.of("saw", "VB", "see"),
        Arguments.of("best", "JJS", "good"),
        Arguments.of("upside", "RB", "upside")
    );
  }
}
