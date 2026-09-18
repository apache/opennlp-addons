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
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Tests complete payload syntax after a recognized file header. */
public class AssetTransportValidationTest {

  private static final String PDF = Base64.getEncoder().encodeToString(
      ("%PDF-1.7" + "a".repeat(40)).getBytes(StandardCharsets.US_ASCII));
  private static final String JWT_HEADER = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
  private static final String JWT_CLAIMS = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("{\"sub\":\"123\"}".getBytes(StandardCharsets.UTF_8));
  private static final String DATA_PREFIX = "data:application/pdf;base64,";

  private final AssetDetector detector = new CursorAssetDetector();

  /** @return Invalid payload suffixes after the header-decoding window. */
  static Stream<Arguments> invalidPayloads() {
    return Stream.of(
        Arguments.of("padding after a complete group", PDF + "="),
        Arguments.of("padding count inconsistent with length", PDF + "AA="),
        Arguments.of("mixed alphabets", PDF + "+_AA"),
        Arguments.of("wrapped URL alphabet", PDF + "\r\nAA-_"));
  }

  /**
   * Rejects malformed bare payloads after a recognized prefix.
   *
   * @param name The malformed encoding case.
   * @param encoded The complete candidate payload.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidPayloads")
  void testMalformedBarePayloadIsNotReported(String name, String encoded) {
    assertEquals(List.of(), detector.detect(encoded), name);
  }

  /**
   * Applies the same payload validation inside a data URI.
   *
   * @param suffix The invalid suffix after a recognizable header.
   */
  @ParameterizedTest
  @ValueSource(strings = {"=", "AA=", "+_AA"})
  void testMalformedDataPayloadIsNotReported(String suffix) {
    assertEquals(List.of(), detector.detect(DATA_PREFIX + PDF + suffix));
  }

  /**
   * Keeps decodable suffixes and reports their exact decoded length.
   *
   * @param suffix The valid payload suffix.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "AA", "AAA", "AA==", "AAA=", "+/AA", "-_AA"})
  void testValidPayloadSuffixesDecodeWithAccurateLength(String suffix) {
    final String encoded = PDF + suffix;
    final List<EmbeddedAsset> assets = detector.detect(encoded);
    assertEquals(1, assets.size());
    final EmbeddedAsset asset = assets.get(0);
    final byte[] expected = suffix.indexOf('-') >= 0
        ? Base64.getUrlDecoder().decode(encoded) : Base64.getDecoder().decode(encoded);
    assertEquals(encoded.length(), asset.payload().getEnd());
    assertEquals(expected.length, asset.decodedLength());
    assertArrayEquals(expected, asset.decode(encoded));
  }

  /**
   * Recognizes typed payloads shorter than one base64 group.
   *
   * @param value The text to encode.
   */
  @ParameterizedTest
  @ValueSource(strings = {"A", "AB", "ABC"})
  void testShortDataPayloadsAreDetected(String value) {
    final byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    final String text = "data:text/plain;base64," + Base64.getEncoder().encodeToString(bytes);
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals("plain", assets.get(0).format());
    assertEquals(bytes.length, assets.get(0).decodedLength());
    assertArrayEquals(bytes, assets.get(0).decode(text));
  }

  /** Retains MIME parameters without including them in the format name. */
  @Test
  void testDataMediaTypeParametersAreNotPartOfTheFormat() {
    final String text = "data:text/plain;charset=UTF-8;base64,SGVsbG8=";
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals("plain", assets.get(0).format());
    assertEquals("text/plain;charset=UTF-8", assets.get(0).mediaType());
  }

  /**
   * Rejects impossible base64url lengths and padding in JWT signatures.
   *
   * @param signature The invalid encoded signature.
   */
  @ParameterizedTest
  @ValueSource(strings = {"A", "AAAAA", "AAAA=", "AAAA=="})
  void testMalformedJwtSignatureEncodingIsRejected(String signature) {
    final String text = JWT_HEADER + "." + JWT_CLAIMS + "." + signature;
    assertFalse(detector.detect(text).stream()
        .anyMatch(asset -> EmbeddedAsset.FORMAT_JWT.equals(asset.format())));
  }

  /**
   * Accepts each possible unpadded base64url remainder.
   *
   * @param signature The encoded signature.
   */
  @ParameterizedTest
  @ValueSource(strings = {"AA", "AAA", "AAAA"})
  void testUnpaddedJwtSignatureLengthsAreAccepted(String signature) {
    final String text = JWT_HEADER + "." + JWT_CLAIMS + "." + signature;
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals(EmbeddedAsset.FORMAT_JWT, assets.get(0).format());
    assertEquals(text.length(), assets.get(0).span().getEnd());
  }
}
