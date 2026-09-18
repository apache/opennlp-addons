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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

public class Keccak256Test {

  /**
   * Hashes the UTF-8 bytes of a string and formats the result as hexadecimal.
   *
   * @param message The message to hash.
   * @return The hexadecimal digest.
   */
  private String digestOf(String message) {
    return HexFormat.of().formatHex(Keccak256.digest(message.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Checks known digest values for short messages.
   *
   * @param message The message to hash.
   * @param expected The expected hexadecimal digest.
   */
  @ParameterizedTest
  @CsvSource({
      "'', c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
      "abc, 4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45",
      "'The quick brown fox jumps over the lazy dog', "
          + "4d741b6f1eb29cb2a9b9911c82f56fa8d73b04959d3d9d222895df6c0b28aa15"
  })
  void testKnownDigests(String message, String expected) {
    Assertions.assertEquals(expected, digestOf(message));
  }

  /**
   * Checks the fixed output length for empty and multi-block input.
   *
   * @param length The input length in bytes.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1000})
  void testDigestLength(int length) {
    Assertions.assertEquals(Keccak256.DIGEST_LENGTH,
        Keccak256.digest(new byte[length]).length);
  }

  /**
   * Checks exact digests around the 136-byte rate and for the 40-byte address input.
   * The expected digests were independently checked with Bouncy Castle's Keccak-256.
   *
   * @param length The number of bytes, with each byte equal to its index modulo 256.
   * @param expected The expected hexadecimal digest.
   */
  @ParameterizedTest
  @CsvSource({
      "0, c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
      "1, bc36789e7a1e281436464229828f817d6612f7b477d66591ff96a9e064bcc98a",
      "40, da227097c39b25f51ebbb255c17b0ee624bc34f0cea142cd9a811b96d3d41f32",
      "135, cbdfd9dee5faad3818d6b06f95a219fd290b0e1706f6a82e5a595b9ce9faca62",
      "136, 7ce759f1ab7f9ce437719970c26b0a66ff11fe3e38e17df89cf5d29c7d7f807e",
      "137, ac73d4fae68b8453f764007c1a20ce95994187861f0c3227a3a8e99a73a3b1db",
      "271, 7c974895b2a88303ff2dc6b58f438ceb0b298cac91099ac0539cc0f477506191",
      "272, fdf2ec49e749960d3c8521a0219af8d03e30e2b3bf19bd16150ee0eaf133d66e",
      "273, 4f707289a9c3ccd0c4a51f2f17339f5dd171d371c04ff7783b735b5b22682eaf",
      "1000, aca79e4146e30eb1c733f6d6060d72471c36ea4e01ebf45d7f4916249c2bbd82"
  })
  void testMessagesAroundTheBlockBoundaryHash(int length, String expected) {
    final byte[] message = new byte[length];
    for (int i = 0; i < length; i++) {
      message[i] = (byte) i;
    }
    final byte[] original = message.clone();
    final byte[] digest = Keccak256.digest(message);
    Assertions.assertEquals(expected, HexFormat.of().formatHex(digest));
    Assertions.assertArrayEquals(original, message);
  }

  /** Checks repeated input is stable and one changed input byte changes the digest. */
  @Test
  void testRepeatedAndChangedMessage() {
    final byte[] first = Keccak256.digest("opennlp".getBytes(StandardCharsets.UTF_8));
    final byte[] again = Keccak256.digest("opennlp".getBytes(StandardCharsets.UTF_8));
    final byte[] other = Keccak256.digest("opennlq".getBytes(StandardCharsets.UTF_8));

    Assertions.assertArrayEquals(first, again);
    Assertions.assertFalse(Arrays.equals(first, other));
  }

  /**
   * Checks the known distinction between Keccak-256 and SHA3-256 for this message.
   *
   * @throws NoSuchAlgorithmException If SHA3-256 is unavailable.
   */
  @Test
  void testDiffersFromSha3() throws NoSuchAlgorithmException {
    final MessageDigest sha3 = MessageDigest.getInstance("SHA3-256");
    final byte[] message = "abc".getBytes(StandardCharsets.UTF_8);

    Assertions.assertFalse(
        Arrays.equals(Keccak256.digest(message), sha3.digest(message)));
  }
}
