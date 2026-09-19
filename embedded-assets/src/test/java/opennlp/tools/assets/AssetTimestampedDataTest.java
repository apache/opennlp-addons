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

import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests original CMS headers without validating timestamp evidence. */
class AssetTimestampedDataTest {

  private static final String FORMAT = "tsd";
  private static final String MEDIA_TYPE = "application/timestamped-data";
  private static final String INDEFINITE_LENGTH = "80";
  private static final String OID_LENGTH = "0b";
  private static final String OID = "2a864886f70d010910011f";
  private static final int PAYLOAD_LENGTH = 80;

  private final AssetDetector detector = new CursorAssetDetector();

  /** {@return BER length encodings that leave the full identifier within 24 header bytes} */
  private static Stream<Arguments> lengths() {
    return Stream.of(
        Arguments.of(INDEFINITE_LENGTH, OID_LENGTH),
        Arguments.of("0d", OID_LENGTH),
        Arguments.of("7f", OID_LENGTH),
        Arguments.of("8180", OID_LENGTH),
        Arguments.of("81ff", OID_LENGTH),
        Arguments.of("820100", OID_LENGTH),
        Arguments.of("83010000", OID_LENGTH),
        Arguments.of("84ffffffff", OID_LENGTH),
        Arguments.of("850100000000", OID_LENGTH),
        Arguments.of("8900000000000000000d", OID_LENGTH),
        Arguments.of(INDEFINITE_LENGTH, "810b"),
        Arguments.of(INDEFINITE_LENGTH, "82000b"),
        Arguments.of(INDEFINITE_LENGTH, "8900000000000000000b"),
        Arguments.of("82000f", "82000b"))
        .flatMap(args -> Stream.of("standard", "url", "mime64", "mime76", "uri")
            .map(transport -> Arguments.of(args.get()[0], args.get()[1], transport)));
  }

  /**
   * Definite and indefinite envelopes identify timestamped attachments in supported encodings.
   *
   * @param sequenceLength The outer BER length field in hexadecimal.
   * @param oidLength The identifier's BER length field in hexadecimal.
   * @param transport The payload encoding.
   */
  @ParameterizedTest
  @MethodSource("lengths")
  void testLengthAndTransport(String sequenceLength, String oidLength, String transport) {
    AssetTestSupport.assertIdentified(detector, header(sequenceLength, oidLength),
        FORMAT, MEDIA_TYPE, transport);
  }

  /** {@return single-bit changes throughout the timestamped-data identifier} */
  private static Stream<Arguments> changedIdentifiers() {
    return IntStream.range(4, 15).boxed().flatMap(offset ->
        IntStream.range(0, Byte.SIZE).mapToObj(bit -> Arguments.of(offset, bit)));
  }

  /**
   * A shared CMS identifier prefix does not identify timestamped data.
   *
   * @param offset The changed identifier byte.
   * @param bit The changed bit.
   */
  @ParameterizedTest
  @MethodSource("changedIdentifiers")
  void testDifferentIdentifier(int offset, int bit) {
    final byte[] bytes = header(INDEFINITE_LENGTH, OID_LENGTH);
    bytes[offset] ^= (byte) (1 << bit);
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * A definite envelope must contain the entire identifier field.
   *
   * @param length The supplied content length, below the identifier field's size.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 4, 8, 11, 12})
  void testShortEnvelope(int length) {
    final String value = HexFormat.of().toHexDigits((byte) length);
    AssetTestSupport.assertUnrecognized(detector, header(value, OID_LENGTH));
    AssetTestSupport.assertUnrecognized(detector, header("81" + value, OID_LENGTH));
    AssetTestSupport.assertUnrecognized(detector, header("890000000000000000" + value, OID_LENGTH));
  }

  /**
   * Long identifier length fields count toward the envelope length.
   *
   * @param length The outer length that omits part of a long identifier field.
   */
  @ParameterizedTest
  @ValueSource(strings = {"0d", "0e", "810d", "82000e"})
  void testEnvelopeExcludesIdentifierLength(String length) {
    AssetTestSupport.assertUnrecognized(detector, header(length, "82000b"));
  }

  /**
   * Malformed tags, lengths and misplaced identifiers do not identify an attachment.
   *
   * @param prefix The bytes preceding the identifier value.
   */
  @ParameterizedTest
  @ValueSource(strings = {"3180060b", "1080060b", "30ff060b", "30fe060b",
      "3080260b", "3080040b", "30800680", "308006ff", "308006fe",
      "3080060a", "3080060c", "308006810a", "3080068180", "30800685010000000b",
      "30800500060b", "003080060b"})
  void testInvalidIdentifierHeader(String prefix) {
    AssetTestSupport.assertUnrecognized(detector,
        Arrays.copyOf(HexFormat.of().parseHex(prefix + OID), PAYLOAD_LENGTH));
  }

  /**
   * Untyped data URIs require the complete identifier, even for short payloads.
   *
   * @param length The available byte count.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14})
  void testTruncatedIdentifier(int length) {
    AssetTestSupport.assertUnrecognized(detector,
        Arrays.copyOf(header(INDEFINITE_LENGTH, OID_LENGTH), length));
  }

  /** {@return cuts within BER long length fields and the following identifier} */
  private static Stream<Arguments> truncatedLongHeaders() {
    return Stream.of("308900000000000000000d060b", "3080068900000000000000000b",
        "3082000d060b").flatMap(prefix -> {
          final byte[] bytes = HexFormat.of().parseHex(prefix + OID);
          return IntStream.range(0, bytes.length).mapToObj(length -> Arguments.of(prefix, length));
        });
  }

  /**
   * Truncated long length fields cannot supply a complete content-type identifier.
   *
   * @param prefix The bytes preceding the identifier value.
   * @param length The available header byte count.
   */
  @ParameterizedTest
  @MethodSource("truncatedLongHeaders")
  void testTruncatedLongHeader(String prefix, int length) {
    AssetTestSupport.assertUnrecognized(detector,
        Arrays.copyOf(HexFormat.of().parseHex(prefix + OID), length));
  }

  /**
   * Format identification does not require or validate timestamp evidence.
   *
   * @param length The available header byte count.
   */
  @ParameterizedTest
  @ValueSource(ints = {15, 16, 20, 23, 24})
  void testIdentifierWithoutCompleteFile(int length) {
    AssetTestSupport.assertIdentified(detector, Arrays.copyOf(header(INDEFINITE_LENGTH, OID_LENGTH), length),
        FORMAT, MEDIA_TYPE, "uri");
  }

  /**
   * Header detection does not search beyond 24 bytes to finish an identifier.
   *
   * @param sequenceLength The valid but unusually wide outer length field.
   * @param oidLength The valid but unusually wide identifier length field.
   */
  @ParameterizedTest
  @MethodSource("longHeaders")
  void testIdentifierBeyondHeaderLimit(String sequenceLength, String oidLength) {
    AssetTestSupport.assertUnrecognized(detector, header(sequenceLength, oidLength));
  }

  /** {@return length fields that place the end of the identifier at byte 25} */
  private static Stream<Arguments> longHeaders() {
    return Stream.of(Arguments.of("8a0000000000000000000d", OID_LENGTH),
        Arguments.of(INDEFINITE_LENGTH, "8a0000000000000000000b"));
  }

  /**
   * An explicit media type remains usable for a different CMS content type.
   *
   * @param mediaType The supplied media type.
   */
  @ParameterizedTest
  @ValueSource(strings = {"application/octet-stream", "application/pkcs7-mime", MEDIA_TYPE})
  void testDeclaredType(String mediaType) {
    final byte[] bytes = header(INDEFINITE_LENGTH, OID_LENGTH);
    bytes[14] = 2;
    final String text = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals(mediaType.substring(mediaType.indexOf('/') + 1), assets.getFirst().format());
    assertEquals(mediaType, assets.getFirst().mediaType());
    assertArrayEquals(bytes, assets.getFirst().decode(text));
  }

  /**
   * Builds an original ContentInfo header followed by bytes that exercise both base64 alphabets.
   *
   * @param sequenceLength The outer BER length field in hexadecimal.
   * @param oidLength The identifier's BER length field in hexadecimal.
   * @return Header bytes without signed content or timestamp evidence.
   */
  private byte[] header(String sequenceLength, String oidLength) {
    final byte[] bytes = Arrays.copyOf(
        HexFormat.of().parseHex("30" + sequenceLength + "06" + oidLength + OID), PAYLOAD_LENGTH);
    bytes[70] = (byte) 0xfb;
    bytes[71] = (byte) 0xff;
    return bytes;
  }
}
