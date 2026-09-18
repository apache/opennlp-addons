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
 * The Keccak-256 hash, as needed for the
 * <a href="https://eips.ethereum.org/EIPS/eip-55">EIP-55</a> address checksum.
 *
 * <p>This is the original
 * <a href="https://keccak.team/files/Keccak-reference-3.0.pdf">Keccak</a> submission with
 * a 1088-bit rate, not the
 * <a href="https://doi.org/10.6028/NIST.FIPS.202">FIPS 202</a> SHA3-256 the JDK provides:
 * The domain-separation byte is {@code 0x01} for Keccak-256 and {@code 0x06} for
 * SHA3-256. The hashes are not interchangeable.</p>
 */
final class Keccak256 {

  /** Constants for each iota step. */
  private static final long[] ROUND_CONSTANTS = {
      0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
      0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
      0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
      0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
      0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
      0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L,
  };

  /** The rotation offset of each rho step, that is {@code (t + 1)(t + 2) / 2 mod 64}. */
  private static final int[] ROTATIONS = {
      1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14,
      27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44,
  };

  /** The lane the pi step moves each rotated lane to. */
  private static final int[] LANES = {
      10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4,
      15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1,
  };

  private static final int LANE_COUNT = 25;
  private static final int ROW_COUNT = 5;
  private static final int ROUNDS = 24;

  /** The rate in bytes: 1600 bits of state less 512 bits of capacity. */
  private static final int RATE = 136;

  /** The digest length in bytes. */
  static final int DIGEST_LENGTH = 32;

  private static final int BYTES_PER_LANE = 8;
  private static final int BITS_PER_BYTE = 8;

  /** The domain separation byte of the original Keccak padding. */
  private static final byte PADDING = 0x01;

  /** The final bit of the {@code pad10*1} padding rule. */
  private static final byte PADDING_END = (byte) 0x80;

  /** Prevents construction of this utility class. */
  private Keccak256() {
  }

  /**
   * Hashes a message.
   *
   * @param message The message bytes. Must not be {@code null}.
   * @return The 32-byte digest.
   */
  static byte[] digest(byte[] message) {
    final long[] state = new long[LANE_COUNT];
    int offset = 0;
    while (message.length - offset >= RATE) {
      absorb(state, message, offset);
      permute(state);
      offset += RATE;
    }
    final byte[] last = new byte[RATE];
    final int remaining = message.length - offset;
    System.arraycopy(message, offset, last, 0, remaining);
    last[remaining] = PADDING;
    last[RATE - 1] |= PADDING_END;
    absorb(state, last, 0);
    permute(state);
    final byte[] digest = new byte[DIGEST_LENGTH];
    for (int i = 0; i < DIGEST_LENGTH; i++) {
      digest[i] = (byte) (state[i / BYTES_PER_LANE]
          >>> BITS_PER_BYTE * (i % BYTES_PER_LANE));
    }
    return digest;
  }

  /**
   * Exclusive-ors one rate-sized block into the state, reading each lane in little-endian
   * byte order.
   *
   * @param state The sponge state.
   * @param block The block to absorb from.
   * @param offset The offset of the block within {@code block}.
   */
  private static void absorb(long[] state, byte[] block, int offset) {
    for (int i = 0; i < RATE; i++) {
      state[i / BYTES_PER_LANE] ^=
          ((long) (block[offset + i] & 0xFF)) << BITS_PER_BYTE * (i % BYTES_PER_LANE);
    }
  }

  /**
   * Applies the 24 rounds of the Keccak-f[1600] permutation to the state in place.
   *
   * @param state The sponge state.
   */
  private static void permute(long[] state) {
    final long[] parity = new long[ROW_COUNT];
    for (int round = 0; round < ROUNDS; round++) {
      for (int x = 0; x < ROW_COUNT; x++) {
        parity[x] = state[x] ^ state[x + 5] ^ state[x + 10] ^ state[x + 15] ^ state[x + 20];
      }
      for (int x = 0; x < ROW_COUNT; x++) {
        final long column = parity[(x + 4) % ROW_COUNT]
            ^ Long.rotateLeft(parity[(x + 1) % ROW_COUNT], 1);
        for (int y = 0; y < LANE_COUNT; y += ROW_COUNT) {
          state[x + y] ^= column;
        }
      }
      long current = state[1];
      for (int t = 0; t < ROUNDS; t++) {
        final int lane = LANES[t];
        final long moved = state[lane];
        state[lane] = Long.rotateLeft(current, ROTATIONS[t]);
        current = moved;
      }
      for (int y = 0; y < LANE_COUNT; y += ROW_COUNT) {
        final long a0 = state[y];
        final long a1 = state[y + 1];
        final long a2 = state[y + 2];
        final long a3 = state[y + 3];
        final long a4 = state[y + 4];
        state[y] = a0 ^ (~a1 & a2);
        state[y + 1] = a1 ^ (~a2 & a3);
        state[y + 2] = a2 ^ (~a3 & a4);
        state[y + 3] = a3 ^ (~a4 & a0);
        state[y + 4] = a4 ^ (~a0 & a1);
      }
      state[0] ^= ROUND_CONSTANTS[round];
    }
  }
}
