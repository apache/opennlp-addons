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

package opennlp.tools.pii;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class BoundariesTest {

  @ParameterizedTest
  @CsvSource({
      "abc, 0, true",
      "'a bc', 2, true",
      "'a-bc', 2, true",
      "'(abc', 1, true",
      "abc, 1, false",
      "'1abc', 1, false",
      "'a.bc', 2, true"
  })
  void testWordStart(String text, int start, boolean expected) {
    Assertions.assertEquals(expected, Boundaries.onWordStart(text, start));
  }

  @ParameterizedTest
  @CsvSource({
      "'123', 0, true",
      "'a 123', 2, true",
      "'a123', 1, false",
      "'1.23', 2, false",
      "'1,23', 2, false",
      "'. 23', 2, true",
      "'.23', 1, true",
      "'a. 23', 3, true"
  })
  void testNumberStart(String text, int start, boolean expected) {
    Assertions.assertEquals(expected, Boundaries.onNumberStart(text, start));
  }

  @ParameterizedTest
  @CsvSource({
      "abc, 3, true",
      "'abc.', 3, true",
      "'abc ', 3, true",
      "abcd, 3, false",
      "'abc9', 3, false"
  })
  void testEnd(String text, int end, boolean expected) {
    Assertions.assertEquals(expected, Boundaries.onEnd(text, end));
  }

  @ParameterizedTest
  @CsvSource({
      "'1.2.3.4', 7, true",
      "'1.2.3.4.5', 7, false",
      "'1.2.3.4.', 7, true",
      "'1.2.3.4 and', 7, true"
  })
  void testEndBeforeSeparator(String text, int end, boolean expected) {
    Assertions.assertEquals(expected, Boundaries.onEndBefore(text, end, '.'));
  }

  /**
   * Verifies boundaries are judged on whole code points: a candidate that follows or
   * precedes a supplementary letter is rejected, not accepted because the adjacent
   * UTF-16 unit is a lone surrogate.
   */
  @Test
  void testJudgesSupplementaryCharactersAsWholeCodePoints() {
    final String letter = new String(Character.toChars(0x10400));
    Assertions.assertFalse(Boundaries.onWordStart(letter + "abc", letter.length()));
    Assertions.assertFalse(Boundaries.onNumberStart(letter + "123", letter.length()));
    Assertions.assertFalse(Boundaries.onEnd("abc" + letter, 3));

    final String symbol = new String(Character.toChars(0x1F600));
    Assertions.assertTrue(Boundaries.onWordStart(symbol + "abc", symbol.length()));
    Assertions.assertTrue(Boundaries.onEnd("abc" + symbol, 3));
  }

  @Test
  void testEmptyTextEndsAtZero() {
    Assertions.assertTrue(Boundaries.onEnd("", 0));
    Assertions.assertTrue(Boundaries.onWordStart("", 0));
    Assertions.assertTrue(Boundaries.onNumberStart("", 0));
  }
}
