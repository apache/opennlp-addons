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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks odd/even digit lengths and the candidate-prefix length used by card scanning. */
class LuhnTest {

  /** Decimal digit sums after doubling an input digit. */
  private static final int[] DOUBLED = {0, 2, 4, 6, 8, 1, 3, 5, 7, 9};

  /**
   * Checks generated check digits, every changed check digit and trailing text exclusion.
   *
   * @param length The candidate length, including its check digit.
   */
  @ParameterizedTest
  @ValueSource(ints = {9, 13, 14, 15, 16, 17, 18, 19})
  void testCandidatePrefix(int length) {
    final StringBuilder value = new StringBuilder(length);
    int sum = 0;
    for (int index = 0; index < length - 1; index++) {
      final int digit = (index + 3) % 10;
      value.append((char) ('0' + digit));
      sum += (length - index) % 2 == 0 ? DOUBLED[digit] : digit;
    }
    final int check = (10 - sum % 10) % 10;
    value.append((char) ('0' + check));

    Assertions.assertTrue(Luhn.valid(value, length));
    Assertions.assertTrue(Luhn.valid(value + " ignored", length));
    for (int replacement = 0; replacement < 10; replacement++) {
      value.setCharAt(length - 1, (char) ('0' + replacement));
      Assertions.assertEquals(replacement == check, Luhn.valid(value, length));
    }
    Assertions.assertTrue(Luhn.valid("0".repeat(length), length));
  }
}
