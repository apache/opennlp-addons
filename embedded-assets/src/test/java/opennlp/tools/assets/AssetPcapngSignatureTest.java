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

package opennlp.tools.assets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests pcapng identification with original section headers and no packet data. */
public class AssetPcapngSignatureTest {

  private static final String FORMAT = "pcapng";
  private static final String MEDIA_TYPE = "application/vnd.tcpdump.pcapng";
  private static final int BLOCK_TYPE = 0x0a0d0d0a;
  private static final int BYTE_ORDER_MAGIC = 0x1a2b3c4d;

  private final AssetDetector detector = new CursorAssetDetector();

  /**
   * Empty sections in both byte orders use the same identifiers and exact spans.
   *
   * @param littleEndian Whether the section uses little-endian fields.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testEmptySectionInBothByteOrders(boolean littleEndian) {
    assertIdentified(section(littleEndian), "standard");
    assertIdentified(section(littleEndian), "uri");
  }

  /**
   * A subsequent section can use a different byte order.
   *
   * @param transport The encoding of the concatenated sections.
   */
  @ParameterizedTest
  @ValueSource(strings = {"standard", "url", "mime64", "mime76", "uri"})
  void testSectionsWithDifferentByteOrders(String transport) {
    final byte[] bytes = ByteBuffer.allocate(56).put(section(true)).put(section(false)).array();
    assertIdentified(bytes, transport);
  }

  /**
   * Both magic fields must be complete before an untyped URI permits inference.
   *
   * @param length The retained bytes of the section header.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3, 4, 5, 7, 8, 9, 10, 11})
  void testIncompleteByteOrderMagicIsRejected(int length) {
    assertUnrecognized(Arrays.copyOf(section(false), length));
    assertUnrecognized(Arrays.copyOf(section(true), length));
  }

  /**
   * The complete signatures identify a possible format without validating a full block.
   *
   * @param littleEndian Whether the section uses little-endian fields.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testSignatureWithoutCompleteBlock(boolean littleEndian) {
    assertIdentified(Arrays.copyOf(section(littleEndian), 12), "uri");
  }

  /**
   * Classic pcap magic numbers and unrelated bytes are not pcapng byte-order magic.
   *
   * @param hex The replacement 4-byte value at offset 8.
   */
  @ParameterizedTest
  @ValueSource(strings = {"00000000", "ffffffff", "a1b2c3d4", "d4c3b2a1", "a1b23c4d",
      "4d3cb2a1", "1a2b3c00", "003c2b1a", "1a2b3c4e", "4e3c2b1a"})
  void testIncorrectByteOrderMagicIsRejected(String hex) {
    final byte[] bytes = section(false);
    System.arraycopy(HexFormat.of().parseHex(hex), 0, bytes, 8, Integer.BYTES);
    assertUnrecognized(bytes);
  }

  /**
   * A byte-order magic at another offset does not identify the file.
   *
   * @param offset The incorrect byte offset.
   * @param littleEndian Whether the bytes use little-endian order.
   */
  @ParameterizedTest
  @CsvSource({"4,false", "7,false", "9,false", "12,false", "24,false",
      "4,true", "7,true", "9,true", "12,true", "24,true"})
  void testIncorrectByteOrderOffsetIsRejected(int offset, boolean littleEndian) {
    final byte[] bytes = section(littleEndian);
    Arrays.fill(bytes, 8, 12, (byte) 0);
    ByteBuffer.wrap(bytes).order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN)
        .putInt(offset, BYTE_ORDER_MAGIC);
    assertUnrecognized(bytes);
  }

  /**
   * The byte-order magic cannot replace the section-header block type.
   *
   * @param type The incorrect leading 32-bit value.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 2, 0x0a0a0a0a, 0x0d0a0d0a, 0x0a0d0d0b, 0x1a2b3c4d})
  void testIncorrectSectionTypeIsRejected(int type) {
    final byte[] bytes = section(false);
    ByteBuffer.wrap(bytes).putInt(type);
    assertUnrecognized(bytes);
  }

  /** A supplied media type remains available for an unrecognized header. */
  @Test
  void testExplicitMediaTypeWithIncorrectByteOrderMagic() {
    final byte[] bytes = section(false);
    Arrays.fill(bytes, 8, 12, (byte) 0);
    final String text = "data:application/octet-stream;base64,"
        + Base64.getEncoder().encodeToString(bytes);
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals("octet-stream", assets.get(0).format());
    assertEquals("application/octet-stream", assets.get(0).mediaType());
    assertArrayEquals(bytes, assets.get(0).decode(text));
  }

  /**
   * Builds a version 1.0 section header with no options or following blocks.
   *
   * @param littleEndian Whether fields use little-endian order.
   * @return The complete 28-byte Section Header Block.
   * @see <a href="https://www.ietf.org/archive/id/draft-ietf-opsawg-pcapng-05.html#name-section-header-block">
   *     pcapng Section Header Block</a>
   */
  private byte[] section(boolean littleEndian) {
    return ByteBuffer.allocate(28).order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN)
        .putInt(BLOCK_TYPE).putInt(28).putInt(BYTE_ORDER_MAGIC)
        .putShort((short) 1).putShort((short) 0).putLong(0).putInt(28).array();
  }

  /**
   * Checks both inference paths for an unsupported header.
   *
   * @param bytes The input bytes.
   */
  private void assertUnrecognized(byte[] bytes) {
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * Checks identifiers, source spans, decoded length and exact bytes.
   *
   * @param bytes The input bytes.
   * @param transport The encoding to use.
   * @throws IllegalArgumentException If the transport is unsupported.
   */
  private void assertIdentified(byte[] bytes, String transport) {
    AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, transport);
  }
}
