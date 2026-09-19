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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests DEX magic with original headers, not executable files. */
class AssetDexMagicTest {

  private static final String FORMAT = "dex";
  private static final String MEDIA_TYPE = "application/x-dex";
  private static final String VERSION = "035";
  private static final int MAGIC_LENGTH = 8;
  private static final int PAYLOAD_LENGTH = 80;

  private final AssetDetector detector = new CursorAssetDetector();

  /**
   * Text starting with dex and a newline is not an executable.
   *
   * @param transport The payload encoding.
   */
  @ParameterizedTest
  @ValueSource(strings = {"standard", "url", "mime64", "mime76", "uri"})
  void testEncodedNote(String transport) {
    final byte[] note = ("dex\nThis is a note about Android bytecode, not an executable file.")
        .getBytes(StandardCharsets.US_ASCII);
    assertTrue(detector.detect(AssetTestSupport.encode(note, transport)).isEmpty());
  }

  /** {@return historical, current and unassigned version numbers in all supported encodings} */
  private static Stream<Arguments> versions() {
    return Stream.of("009", "013", VERSION, "037", "038", "039", "040", "041", "000", "999")
        .flatMap(version -> Stream.of("standard", "url", "mime64", "mime76", "uri")
            .map(transport -> Arguments.of(version, transport)));
  }

  /**
   * Decimal versions need not name an Android release.
   *
   * @param version The version digits.
   * @param transport The payload encoding.
   */
  @ParameterizedTest
  @MethodSource("versions")
  void testVersionAndTransport(String version, String transport) {
    final byte[] bytes = AssetTestSupport.dex(version, PAYLOAD_LENGTH);
    bytes[13] = (byte) 0xfb;
    bytes[14] = (byte) 0xff;
    AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, transport);
  }

  /** {@return bytes near the decimal boundaries and representative non-ASCII bytes} */
  private static Stream<Arguments> versionBytes() {
    return IntStream.range(4, 7).boxed().flatMap(offset ->
        IntStream.of(0, ' ', '/', '0', '1', '8', '9', ':', 'A', 'a', 0x7f, 0x80, 0xb2, 0xd9, 0xff)
            .mapToObj(value -> Arguments.of(offset, value)));
  }

  /**
   * Version bytes must be ASCII decimal digits.
   *
   * @param offset The position within the magic.
   * @param value The replacement byte.
   */
  @ParameterizedTest
  @MethodSource("versionBytes")
  void testVersionDigit(int offset, int value) {
    final byte[] bytes = AssetTestSupport.dex(VERSION, PAYLOAD_LENGTH);
    bytes[offset] = (byte) value;
    if (value >= '0' && value <= '9') {
      AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, "standard");
      AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, "uri");
    } else {
      AssetTestSupport.assertUnrecognized(detector, bytes);
    }
  }

  /**
   * The terminating byte must be zero.
   *
   * @param terminator The nonzero terminating byte.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 9, 10, 13, 32, 48, 127, 128, 255})
  void testNonzeroTerminator(int terminator) {
    final byte[] bytes = AssetTestSupport.dex(VERSION, PAYLOAD_LENGTH);
    bytes[MAGIC_LENGTH - 1] = (byte) terminator;
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * A zero later in the payload cannot terminate the magic.
   *
   * @param offset The misplaced zero.
   */
  @ParameterizedTest
  @ValueSource(ints = {8, 9, 12, 16, 24})
  void testMisplacedTerminator(int offset) {
    final byte[] bytes = AssetTestSupport.dex(VERSION, PAYLOAD_LENGTH);
    Arrays.fill(bytes, MAGIC_LENGTH - 1, bytes.length, (byte) 'x');
    bytes[offset] = 0;
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * Untyped data URIs need the complete magic, even for short payloads.
   *
   * @param length The available byte count.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
  void testTruncatedMagic(int length) {
    AssetTestSupport.assertUnrecognized(detector, AssetTestSupport.dex(VERSION, length));
  }

  /**
   * Recognition does not establish that executable headers or code are valid.
   *
   * @param length The size of the incomplete file.
   */
  @ParameterizedTest
  @ValueSource(ints = {8, 9, 12, 16, 23, 24})
  void testMagicWithoutCompleteFile(int length) {
    AssetTestSupport.assertIdentified(detector, AssetTestSupport.dex(VERSION, length),
        FORMAT, MEDIA_TYPE, "uri");
  }

  /** {@return modifications of the fixed DEX prefix} */
  private static Stream<Arguments> prefixes() {
    return IntStream.range(0, 4).boxed().flatMap(offset ->
        IntStream.of(0, ' ', 'D', 'X', '\r', 0xff)
            .mapToObj(value -> Arguments.of(offset, value)));
  }

  /**
   * Valid version digits do not compensate for a different leading identifier.
   *
   * @param offset The modified prefix position.
   * @param value The replacement byte.
   */
  @ParameterizedTest
  @MethodSource("prefixes")
  void testChangedPrefix(int offset, int value) {
    final byte[] bytes = AssetTestSupport.dex(VERSION, PAYLOAD_LENGTH);
    bytes[offset] = (byte) value;
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * A supplied type remains usable when the payload has no recognized signature.
   *
   * @param mediaType The supplied media type.
   */
  @ParameterizedTest
  @ValueSource(strings = {"application/octet-stream", MEDIA_TYPE})
  void testDeclaredTypeWithInvalidMagic(String mediaType) {
    final byte[] bytes = AssetTestSupport.dex("abc", PAYLOAD_LENGTH);
    final String text = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals(mediaType.substring(mediaType.indexOf('/') + 1), assets.getFirst().format());
    assertEquals(mediaType, assets.getFirst().mediaType());
    assertArrayEquals(bytes, assets.getFirst().decode(text));
  }
}
