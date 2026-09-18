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
 * The <a href="https://github.com/bitcoin/bips/blob/master/bip-0173.mediawiki">BIP-173</a>
 * bech32 and <a href="https://github.com/bitcoin/bips/blob/master/bip-0350.mediawiki">
 * BIP-350</a> bech32m encodings of Bitcoin segwit addresses. The final 6 characters
 * encode a checksum over the prefix and data. Witness version 0 uses bech32; versions
 * 1 through 16 use bech32m.
 */
final class Bech32 {

  /** The bech32 character set in value order. */
  private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

  private static final int ASCII_RANGE = 128;

  /** The value of each ASCII character in the charset, or {@code -1}. */
  private static final int[] VALUES = new int[ASCII_RANGE];

  /** The generator coefficients of the BCH code. */
  private static final int[] GENERATOR =
      {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};

  /** The checksum constant for a bech32 string. */
  private static final int BECH32_RESIDUE = 1;

  /** The checksum constant for a bech32m string. */
  private static final int BECH32M_RESIDUE = 0x2bc830a3;

  private static final int CHECKSUM_LENGTH = 6;
  private static final int MAX_WITNESS_VERSION = 16;
  private static final int BITS_PER_CHARACTER = 5;
  private static final int MIN_PROGRAM_BYTES = 2;
  private static final int MAX_PROGRAM_BYTES = 40;
  private static final int MASK = 0x1ffffff;

  static {
    for (int i = 0; i < ASCII_RANGE; i++) {
      VALUES[i] = -1;
    }
    for (int i = 0; i < CHARSET.length(); i++) {
      VALUES[CHARSET.charAt(i)] = i;
    }
  }

  /** Prevents construction of this utility class. */
  private Bech32() {
  }

  /**
   * Tests for a character of the bech32 charset in either case.
   *
   * @param c The character.
   * @return {@code true} if the character encodes a bech32 value.
   */
  static boolean isDataChar(char c) {
    return c < ASCII_RANGE && VALUES[Ascii.toLower(c)] >= 0;
  }

  /**
   * Checks witness version, program length, padding and the version-specific checksum.
   *
   * @param text The text being scanned.
   * @param start The first character of the data part, that is the character after the
   *              separator.
   * @param end The exclusive end of the data part.
   * @param prefix The human-readable prefix the checksum covers. Must be lowercase.
   * @return The witness version {@code 0} to {@link #MAX_WITNESS_VERSION}, or {@code -1} if
   *         the data part is not a valid address body under either encoding.
   */
  static int checkedWitnessVersion(CharSequence text, int start, int end, String prefix) {
    final int length = end - start;
    if (length <= CHECKSUM_LENGTH) {
      return -1;
    }
    final int[] values = new int[prefix.length() * 2 + 1 + length];
    int at = 0;
    for (int i = 0; i < prefix.length(); i++) {
      values[at++] = prefix.charAt(i) >>> BITS_PER_CHARACTER;
    }
    values[at++] = 0;
    for (int i = 0; i < prefix.length(); i++) {
      values[at++] = prefix.charAt(i) & 0x1F;
    }
    for (int i = start; i < end; i++) {
      final int value = VALUES[Ascii.toLower(text.charAt(i))];
      if (value < 0) {
        return -1;
      }
      values[at++] = value;
    }
    final int version = values[prefix.length() * 2 + 1];
    if (version > MAX_WITNESS_VERSION) {
      return -1;
    }
    int accumulator = 0;
    int bits = 0;
    int programBytes = 0;
    for (int i = start + 1; i < end - CHECKSUM_LENGTH; i++) {
      accumulator = accumulator << BITS_PER_CHARACTER
          | VALUES[Ascii.toLower(text.charAt(i))];
      bits += BITS_PER_CHARACTER;
      while (bits >= Byte.SIZE) {
        bits -= Byte.SIZE;
        programBytes++;
        accumulator &= (1 << bits) - 1;
      }
    }
    if (programBytes < MIN_PROGRAM_BYTES || programBytes > MAX_PROGRAM_BYTES) {
      return -1;
    }
    if (bits > BITS_PER_CHARACTER - 1 || accumulator != 0) {
      return -1;
    }
    if (version == 0 && programBytes != 20 && programBytes != 32) {
      return -1;
    }
    final int residue = version == 0 ? BECH32_RESIDUE : BECH32M_RESIDUE;
    return polymod(values) == residue ? version : -1;
  }

  /**
   * Computes the BCH checksum result for a value sequence.
   *
   * @param values The expanded prefix followed by the data values.
   * @return The checksum result.
   */
  private static int polymod(int[] values) {
    int checksum = 1;
    for (final int value : values) {
      final int top = checksum >>> 25;
      checksum = (checksum & MASK) << BITS_PER_CHARACTER ^ value;
      for (int i = 0; i < GENERATOR.length; i++) {
        if ((top >>> i & 1) != 0) {
          checksum ^= GENERATOR[i];
        }
      }
    }
    return checksum;
  }
}
