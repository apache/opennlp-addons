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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests Android XML chunk layouts and GRIB signature recognition with original fixtures. */
class AssetAndroidGribTest {

  private static final int MAGIC_LENGTH = 4;
  private static final int CHUNK_HEADER_LENGTH = 8;
  private static final int STRING_POOL_TYPE = 1;
  private static final int XML_TYPE = 3;
  private static final int RESOURCE_MAP_TYPE = 0x0180;
  private static final int UNKNOWN_CHUNK_TYPE = 0x7ffe;
  private static final String ANDROID_FORMAT = "axml";
  private static final String ANDROID_MEDIA_TYPE = "application/vnd.android.axml";
  private static final String GRIB_FORMAT = "grb";
  private static final String GRIB_MEDIA_TYPE = "application/x-grib";
  private static final String GRIB_MAGIC = "GRIB";
  private static final List<String> TRANSPORTS = List.of("standard", "url", "mime64", "mime76", "uri");

  private final AssetDetector detector = new CursorAssetDetector();

  /** {@return zero, one or two chunks preceding the string pool} */
  private static Stream<List<Integer>> leadingChunks() {
    return Stream.of(List.of(), List.of(RESOURCE_MAP_TYPE), List.of(UNKNOWN_CHUNK_TYPE),
        List.of(UNKNOWN_CHUNK_TYPE, RESOURCE_MAP_TYPE));
  }

  /** {@return Android XML layouts in the supported base64 transports} */
  private static Stream<Arguments> xmlLayouts() {
    return leadingChunks().flatMap(chunks -> TRANSPORTS.stream()
        .map(transport -> Arguments.of(chunks, transport)));
  }

  /**
   * A resource map or unknown chunk may precede the string pool.
   *
   * @param chunks The preceding chunk types.
   * @param transport The payload encoding.
   * @see <a href="https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/ResourceTypes.cpp">
   *     Android ResXMLTree reader</a>
   */
  @ParameterizedTest
  @MethodSource("xmlLayouts")
  void testAndroidXmlLayout(List<Integer> chunks, String transport) {
    final byte[] bytes = binaryXml(chunks);
    AssetTestSupport.assertIdentified(detector, bytes, ANDROID_FORMAT, ANDROID_MEDIA_TYPE, transport);
  }

  /** {@return representative edition values, including values without a defined format} */
  private static Stream<Arguments> gribEditions() {
    return IntStream.of(0, 1, 2, 3, 127, 128, 255).boxed().flatMap(edition ->
        TRANSPORTS.stream().map(transport -> Arguments.of(edition, transport)));
  }

  /**
   * The edition field does not change the format inferred from the GRIB signature.
   * Values without an assigned edition do not establish a valid GRIB message.
   *
   * @param edition The byte at offset 7.
   * @param transport The payload encoding.
   */
  @ParameterizedTest
  @MethodSource("gribEditions")
  void testGribEdition(int edition, String transport) {
    final byte[] bytes = gribHeader(edition);
    AssetTestSupport.assertIdentified(detector, bytes, GRIB_FORMAT, GRIB_MEDIA_TYPE, transport);
  }

  /** {@return both format names with each signature byte position} */
  private static Stream<Arguments> magicPositions() {
    return Stream.of(ANDROID_FORMAT, GRIB_FORMAT).flatMap(format ->
        IntStream.range(0, MAGIC_LENGTH).mapToObj(offset -> Arguments.of(format, offset)));
  }

  /**
   * Every byte in the identifying signature is required.
   *
   * @param format The fixture format.
   * @param offset The changed byte.
   */
  @ParameterizedTest
  @MethodSource("magicPositions")
  void testChangedSignature(String format, int offset) {
    final byte[] bytes = fixture(format);
    bytes[offset] ^= (byte) 0xff;
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * An incomplete signature is not recognized, including in an untyped data URI.
   *
   * @param format The fixture format.
   * @param length The available signature bytes.
   */
  @ParameterizedTest
  @MethodSource("magicPositions")
  void testTruncatedSignature(String format, int length) {
    AssetTestSupport.assertUnrecognized(detector, Arrays.copyOf(fixture(format), length));
  }

  /** {@return short prefixes with a complete signature but incomplete format records} */
  private static Stream<Arguments> incompleteFiles() {
    return Stream.of(ANDROID_FORMAT, GRIB_FORMAT).flatMap(format ->
        IntStream.of(4, 5, 7, 8, 12, 16).mapToObj(length -> Arguments.of(format, length)));
  }

  /**
   * Signature recognition does not validate container lengths, nodes or message sections.
   *
   * @param format The fixture format.
   * @param length The available byte count.
   */
  @ParameterizedTest
  @MethodSource("incompleteFiles")
  void testSignatureWithoutCompleteFile(String format, int length) {
    final String mediaType = ANDROID_FORMAT.equals(format) ? ANDROID_MEDIA_TYPE : GRIB_MEDIA_TYPE;
    AssetTestSupport.assertIdentified(detector, Arrays.copyOf(fixture(format), length),
        format, mediaType, "uri");
  }

  /**
   * Chooses an original fixture for the signature tests.
   *
   * @param format The format name.
   * @return An Android XML document or GRIB header fixture.
   */
  private byte[] fixture(String format) {
    return ANDROID_FORMAT.equals(format) ? binaryXml(List.of()) : gribHeader(2);
  }

  /**
   * Builds a GRIB 2-style indicator section with a caller-selected edition byte.
   * The trailing bytes are not weather data or complete GRIB sections.
   *
   * @param edition The edition byte, including unassigned values.
   * @return The original header fixture.
   * @see <a href="https://codes.ecmwf.int/grib/format/grib2/sections/0/">GRIB indicator section</a>
   */
  private byte[] gribHeader(int edition) {
    final ByteBuffer bytes = ByteBuffer.allocate(80);
    bytes.put(GRIB_MAGIC.getBytes(StandardCharsets.US_ASCII));
    bytes.putShort((short) 0).put((byte) 0).put((byte) edition).putLong(bytes.capacity());
    bytes.put(19, (byte) 0xfb).put(20, (byte) 0xff);
    return bytes.array();
  }

  /**
   * Builds an Android binary XML document for an empty manifest element.
   * Additional chunks have no data and precede the UTF-8 string pool.
   *
   * @param chunks The extra chunk types in order.
   * @return The original XML bytes, not an APK or application manifest with a package name.
   * @see <a href="https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/include/androidfw/ResourceTypes.h">
   *     Android resource structures</a>
   */
  private byte[] binaryXml(List<Integer> chunks) {
    final ByteBuffer bytes = ByteBuffer.allocate(112 + chunks.size() * CHUNK_HEADER_LENGTH)
        .order(ByteOrder.LITTLE_ENDIAN);
    chunk(bytes, XML_TYPE, CHUNK_HEADER_LENGTH, bytes.capacity());
    for (int type : chunks) {
      chunk(bytes, type, CHUNK_HEADER_LENGTH, CHUNK_HEADER_LENGTH);
    }
    chunk(bytes, STRING_POOL_TYPE, 28, 44);
    bytes.putInt(1).putInt(0).putInt(0x0100).putInt(32).putInt(0);
    bytes.putInt(0);
    bytes.put((byte) 8).put((byte) 8).put("manifest".getBytes(StandardCharsets.US_ASCII));
    bytes.putShort((short) 0);
    chunk(bytes, 0x0102, 16, 36);
    bytes.putInt(1).putInt(-1).putInt(-1).putInt(0);
    bytes.putShort((short) 20).putShort((short) 20).putLong(0);
    chunk(bytes, 0x0103, 16, 24);
    bytes.putInt(1).putInt(-1).putInt(-1).putInt(0);
    assertEquals(bytes.capacity(), bytes.position());
    return bytes.array();
  }

  /**
   * Writes the fixed portion of an Android resource chunk header.
   *
   * @param bytes The little-endian output buffer.
   * @param type The chunk type.
   * @param headerLength The header size.
   * @param totalLength The chunk size, including data.
   */
  private void chunk(ByteBuffer bytes, int type, int headerLength, int totalLength) {
    bytes.putShort((short) type).putShort((short) headerLength).putInt(totalLength);
  }
}
