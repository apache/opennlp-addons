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

/**
 * Luhn checksum calculation for validated ASCII digit sequences, as used by
 * <a href="https://imeidb.gsma.com/imei/resources/documents/TS.06-v22.0.pdf">GSMA TS.06</a>.
 */
final class Luhn {

  private static final int MODULUS = 10;

  /** Prevents construction of this utility class. */
  private Luhn() {
  }

  /**
   * Applies the Luhn check to the specified prefix of a digit sequence.
   *
   * @param digits The non-null ASCII digits, already validated by the scanner.
   * @param length The number of leading digits to check, at most the sequence length.
   * @return {@code true} if the checksum passes, including zero-only values.
   */
  static boolean valid(CharSequence digits, int length) {
    int sum = 0;
    boolean doubled = false;
    for (int i = length - 1; i >= 0; i--) {
      int digit = digits.charAt(i) - '0';
      if (doubled) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      sum += digit;
      doubled = !doubled;
    }
    return sum % MODULUS == 0;
  }
}
