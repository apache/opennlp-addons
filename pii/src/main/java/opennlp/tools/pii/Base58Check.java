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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Checks the <a href="https://en.bitcoin.it/wiki/Base58Check_encoding">Base58Check</a>
 * encoding of legacy Bitcoin addresses. The decoded bytes contain a version byte,
 * a 20-byte hash and the initial 4 bytes of their double SHA-256 checksum.
 */
final class Base58Check {

  /** The base 58 alphabet excludes zero, uppercase O/I and lowercase l. */
  private static final String ALPHABET =
      "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

  private static final int RADIX = 58;
  private static final int ASCII_RANGE = 128;

  /** The value of each ASCII character in the alphabet, or {@code -1}. */
  private static final int[] VALUES = new int[ASCII_RANGE];

  /** The payload of an address: a version byte, a 20-byte hash, and 4 check bytes. */
  static final int PAYLOAD_LENGTH = 25;

  private static final int CHECKSUM_LENGTH = 4;

  private static final int SHA256_LENGTH = 32;

  static {
    for (int i = 0; i < ASCII_RANGE; i++) {
      VALUES[i] = -1;
    }
    for (int i = 0; i < ALPHABET.length(); i++) {
      VALUES[ALPHABET.charAt(i)] = i;
    }
  }

  /** Prevents construction of this utility class. */
  private Base58Check() {
  }

  /**
   * Tests for a character of the base 58 alphabet.
   *
   * @param c The character.
   * @return {@code true} if the character encodes a base 58 digit.
   */
  static boolean isBase58Char(char c) {
    return c < ASCII_RANGE && VALUES[c] >= 0;
  }

  /**
   * Gets the version byte of a Base58Check payload with a valid checksum.
   *
   * @param text The text being scanned.
   * @param start The first character of the candidate.
   * @param end The exclusive end of the candidate.
   * @return The version byte as an unsigned value, or {@code -1} if the candidate does not
   *         decode to a {@link #PAYLOAD_LENGTH}-byte payload with a valid checksum.
   */
  static int checkedVersion(CharSequence text, int start, int end) {
    final byte[] payload = decode(text, start, end);
    if (payload == null || payload.length != PAYLOAD_LENGTH) {
      return -1;
    }
    final byte[] digest =
        sha256(sha256(payload, PAYLOAD_LENGTH - CHECKSUM_LENGTH), SHA256_LENGTH);
    for (int i = 0; i < CHECKSUM_LENGTH; i++) {
      if (digest[i] != payload[PAYLOAD_LENGTH - CHECKSUM_LENGTH + i]) {
        return -1;
      }
    }
    return payload[0] & 0xFF;
  }

  /**
   * Converts base 58 characters to bytes, most significant byte first.
   *
   * @param text The text being scanned.
   * @param start The first character to decode.
   * @param end The exclusive end of the characters to decode.
   * @return The decoded bytes, or {@code null} if a character is not a base 58 digit.
   */
  private static byte[] decode(CharSequence text, int start, int end) {
    // A single output byte is sufficient per base 58 character.
    final byte[] reversed = new byte[end - start + 1];
    int length = 0;
    for (int i = start; i < end; i++) {
      final char c = text.charAt(i);
      if (!isBase58Char(c)) {
        return null;
      }
      int carry = VALUES[c];
      for (int j = 0; j < length; j++) {
        carry += (reversed[j] & 0xFF) * RADIX;
        reversed[j] = (byte) carry;
        carry >>>= 8;
      }
      while (carry > 0) {
        reversed[length++] = (byte) carry;
        carry >>>= 8;
      }
    }
    // Each leading character 1 represents a zero byte.
    for (int i = start; i < end && text.charAt(i) == ALPHABET.charAt(0); i++) {
      reversed[length++] = 0;
    }
    final byte[] decoded = new byte[length];
    for (int i = 0; i < length; i++) {
      decoded[i] = reversed[length - 1 - i];
    }
    return decoded;
  }

  /**
   * Hashes the leading bytes of an array with SHA-256.
   *
   * @param data The bytes to hash.
   * @param length The number of leading bytes to hash.
   * @return The digest.
   */
  private static byte[] sha256(byte[] data, int length) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(data, 0, length);
      return digest.digest();
    } catch (NoSuchAlgorithmException e) {
      // Conforming Java implementations provide SHA-256.
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
