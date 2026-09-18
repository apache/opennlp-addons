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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests JPEG 2000 file-type boxes using original headers without image data. */
public class AssetJpeg2000TypeTest {

  private static final byte[] SIGNATURE = HexFormat.of().parseHex("0000000c6a5020200d0a870a");
  private static final String JP2_BRAND = "jp2 ";
  private static final String JP2_FORMAT = "jp2";
  private static final String JP2_MEDIA_TYPE = "image/jp2";
  private static final String JPX_BRAND = "jpx ";
  private static final String JPX_FORMAT = "jpx";
  private static final String JPX_MEDIA_TYPE = "image/jpx";
  private static final String STANDARD = "standard";
  private static final String URI = "uri";
  private static final String SUPPLIED_MEDIA_TYPE = "application/octet-stream";
  private final AssetDetector detector = new CursorAssetDetector();

  /** @return File brands, metadata, box lengths and encodings. */
  static Stream<Arguments> formats() {
    return Stream.of(
        new String[] {JP2_BRAND, JP2_FORMAT, JP2_MEDIA_TYPE},
        new String[] {JPX_BRAND, JPX_FORMAT, JPX_MEDIA_TYPE},
        new String[] {"jpm ", "jpm", "image/jpm"},
        new String[] {"mjp2", "mj2", "video/mj2"})
        .flatMap(format -> Stream.of(false, true).flatMap(extended ->
            Stream.of(STANDARD, "url", "mime64", "mime76", URI)
                .map(transport -> Arguments.of(format[0], format[1], format[2], extended, transport))));
  }

  /** @return Standard and extended boxes cut before the end of their fixed fields. */
  static Stream<Arguments> truncatedHeaders() {
    return Stream.of(false, true).flatMap(extended ->
        IntStream.range(0, extended ? 36 : 28).mapToObj(length -> Arguments.of(extended, length)));
  }

  /**
   * The file brand selects metadata even when the compatibility list includes JP2.
   *
   * @param brand The primary file brand.
   * @param format The expected format name.
   * @param mediaType The expected media type.
   * @param extended Whether the box uses a 64-bit length.
   * @param transport The encoding to use.
   */
  @ParameterizedTest
  @MethodSource("formats")
  void testFileBrands(String brand, String format, String mediaType, boolean extended, String transport) {
    AssetTestSupport.assertIdentified(detector, header(brand, extended), format, mediaType, transport);
  }

  /**
   * Missing fixed fields prevent inference, including in short untyped data URIs.
   *
   * @param extended Whether the box uses a 64-bit length.
   * @param length The retained header length.
   */
  @ParameterizedTest
  @MethodSource("truncatedHeaders")
  void testIncompleteFileType(boolean extended, int length) {
    AssetTestSupport.assertUnrecognized(detector, Arrays.copyOf(header(JP2_BRAND, extended), length));
  }

  /**
   * The fixed fields identify the format without checking the compatibility list or image data.
   *
   * @param extended Whether the box uses a 64-bit length.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testFixedFieldsWithoutImageData(boolean extended) {
    final byte[] bytes = Arrays.copyOf(header(JP2_BRAND, extended), extended ? 36 : 28);
    AssetTestSupport.assertIdentified(detector, bytes, JP2_FORMAT, JP2_MEDIA_TYPE, STANDARD);
    AssetTestSupport.assertIdentified(detector, bytes, JP2_FORMAT, JP2_MEDIA_TYPE, URI);
  }

  /**
   * A nonzero minor version does not alter the primary brand.
   *
   * @param extended Whether the box uses a 64-bit length.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testMinorVersion(boolean extended) {
    final byte[] bytes = header(JP2_BRAND, extended);
    ByteBuffer.wrap(bytes).putInt(extended ? 32 : 24, -1);
    AssetTestSupport.assertIdentified(detector, bytes, JP2_FORMAT, JP2_MEDIA_TYPE, URI);
  }

  /**
   * The box length must provide space for the brand and minor version.
   *
   * @param length The insufficient standard box length.
   */
  @ParameterizedTest
  @ValueSource(ints = {2, 7, 8, 11, 12, 15})
  void testInsufficientStandardBoxLength(int length) {
    final byte[] bytes = header(JP2_BRAND, false);
    ByteBuffer.wrap(bytes).putInt(12, length);
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * Extended lengths must include the additional length field.
   *
   * @param length The insufficient extended box length.
   */
  @ParameterizedTest
  @ValueSource(longs = {0, 1, 8, 16, 20, 23})
  void testInsufficientExtendedBoxLength(long length) {
    final byte[] bytes = header(JP2_BRAND, true);
    ByteBuffer.wrap(bytes).putLong(20, length);
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * Unsigned box lengths and the zero-to-end marker do not change identification.
   * The detector does not check the complete box contents.
   *
   * @param length The standard length value.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 16, 20, 24, 32, 0x80000000, 0xfffffffc})
  void testStandardLengthValues(int length) {
    final byte[] bytes = header(JP2_BRAND, false);
    ByteBuffer.wrap(bytes).putInt(12, length);
    AssetTestSupport.assertIdentified(detector, bytes, JP2_FORMAT, JP2_MEDIA_TYPE, URI);
  }

  /**
   * The 64-bit field is unsigned and does not contribute brand bytes.
   *
   * @param length The extended length value.
   */
  @ParameterizedTest
  @ValueSource(longs = {24, 28, 32, 0x6a703220L, 0xffffffffL, 0x100000000L,
      Long.MAX_VALUE, Long.MIN_VALUE, -1})
  void testExtendedLengthValues(long length) {
    final byte[] bytes = header(JPX_BRAND, true);
    ByteBuffer.wrap(bytes).putLong(20, length);
    AssetTestSupport.assertIdentified(detector, bytes, JPX_FORMAT, JPX_MEDIA_TYPE, URI);
  }

  /**
   * Unknown primary brands cannot use a compatibility-list entry for inference.
   *
   * @param brand The unsupported brand.
   */
  @ParameterizedTest
  @ValueSource(strings = {"????", "jp2x", "JP2 ", "jpxb", "mj2 ", "jpip", "    "})
  void testUnknownBrand(String brand) {
    AssetTestSupport.assertUnrecognized(detector, header(brand, false));
    AssetTestSupport.assertUnrecognized(detector, header(brand, true));
  }

  /**
   * The file-type box must immediately follow the signature box.
   *
   * @param boxType The incorrect box type.
   */
  @ParameterizedTest
  @ValueSource(strings = {"free", "jp2h", "FTYP", "xxxx"})
  void testIncorrectBoxType(String boxType) {
    final byte[] bytes = header(JP2_BRAND, false);
    ByteBuffer.wrap(bytes).position(16).put(boxType.getBytes(StandardCharsets.US_ASCII));
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * A displaced file-type box cannot identify the payload.
   *
   * @param offset The incorrect start position of the file-type box.
   */
  @ParameterizedTest
  @ValueSource(ints = {11, 13, 16, 24, 32})
  void testDisplacedFileTypeBox(int offset) {
    final byte[] source = header(JP2_BRAND, false);
    final byte[] bytes = Arrays.copyOf(SIGNATURE, 80);
    System.arraycopy(source, 12, bytes, offset, 24);
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * The complete signature box is required before considering a brand.
   *
   * @param offset The signature byte to change.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
  void testIncorrectSignature(int offset) {
    final byte[] bytes = header(JP2_BRAND, false);
    bytes[offset] ^= 1;
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * Explicit URI media types remain available without replacing a supported format name.
   *
   * @param brand The supported or unknown brand.
   */
  @ParameterizedTest
  @ValueSource(strings = {JPX_BRAND, "????"})
  void testSuppliedMediaType(String brand) {
    final byte[] bytes = header(brand, true);
    final String text = "data:" + SUPPLIED_MEDIA_TYPE + ";base64,"
        + Base64.getEncoder().encodeToString(bytes);
    final var assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals(brand.equals(JPX_BRAND) ? JPX_FORMAT : "octet-stream", assets.get(0).format());
    assertEquals(SUPPLIED_MEDIA_TYPE, assets.get(0).mediaType());
    assertArrayEquals(bytes, assets.get(0).decode(text));
  }

  /**
   * Builds a header with a primary brand and JP2 compatibility entry, without image data.
   *
   * @param brand The primary file brand.
   * @param extended Whether to use a 64-bit box length.
   * @return The 80-byte fixture.
   * @see <a href="https://www.itu.int/rec/T-REC-T.800">JPEG 2000 core coding system, Annex I</a>
   */
  private byte[] header(String brand, boolean extended) {
    final ByteBuffer buffer = ByteBuffer.allocate(80).put(SIGNATURE)
        .putInt(extended ? 1 : 24).put("ftyp".getBytes(StandardCharsets.US_ASCII));
    if (extended) {
      buffer.putLong(32);
    }
    return buffer.put(brand.getBytes(StandardCharsets.US_ASCII)).putInt(0)
        .put(brand.getBytes(StandardCharsets.US_ASCII))
        .put(JP2_BRAND.getBytes(StandardCharsets.US_ASCII)).array();
  }
}
