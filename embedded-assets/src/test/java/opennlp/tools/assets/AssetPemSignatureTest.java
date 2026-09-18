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

/** Tests base64-encoded PEM opening markers using original placeholder envelopes. */
class AssetPemSignatureTest {

  private static final String BEGIN = "-----BEGIN ";
  private static final String BOUNDARY = "-----";
  private static final String KEY_FORMAT = "pem-key";
  private static final String KEY_MEDIA_TYPE = "application/x-x509-key";
  private static final String PARAMETERS_FORMAT = "pem-parameters";

  private static final List<PemHeader> HEADERS = List.of(
      new PemHeader("CERTIFICATE", "pem-cert", "application/x-x509-cert"),
      new PemHeader("DSA PARAMETERS", PARAMETERS_FORMAT, "application/x-x509-dsa-parameters"),
      new PemHeader("EC PARAMETERS", PARAMETERS_FORMAT, "application/x-x509-ec-parameters"),
      new PemHeader("DSA PRIVATE KEY", KEY_FORMAT, KEY_MEDIA_TYPE),
      new PemHeader("RSA PRIVATE KEY", KEY_FORMAT, KEY_MEDIA_TYPE),
      new PemHeader("PRIVATE KEY", KEY_FORMAT, KEY_MEDIA_TYPE),
      new PemHeader("PUBLIC KEY", KEY_FORMAT, KEY_MEDIA_TYPE),
      new PemHeader("DSA KEY", KEY_FORMAT, KEY_MEDIA_TYPE),
      new PemHeader("RSA KEY", KEY_FORMAT, KEY_MEDIA_TYPE),
      new PemHeader("KEY", KEY_FORMAT, KEY_MEDIA_TYPE),
      new PemHeader("PKCS7", "p7s", "application/pkcs7-signature"));

  private final AssetDetector detector = new CursorAssetDetector();

  /**
   * The supported encoded-PEM labels and their existing metadata.
   *
   * @param label The encapsulation label.
   * @param format The detected format name.
   * @param mediaType The inferred media type.
   */
  private record PemHeader(String label, String format, String mediaType) {
  }

  /** {@return the encoded-PEM labels in the signature catalog} */
  private static Stream<PemHeader> headers() {
    return HEADERS.stream();
  }

  /** {@return each supported label in all five base64 transports} */
  private static Stream<Arguments> transports() {
    return headers().flatMap(header -> Stream.of("standard", "url", "mime64", "mime76", "uri")
        .map(transport -> Arguments.of(header, transport)));
  }

  /**
   * Recognition retains metadata, decoded bytes and source offsets.
   *
   * @param header The label and expected metadata.
   * @param transport The payload encoding.
   */
  @ParameterizedTest
  @MethodSource("transports")
  void testEncodedEnvelope(PemHeader header, String transport) {
    AssetTestSupport.assertIdentified(detector, AssetTestSupport.pem(header.label()),
        header.format(), header.mediaType(), transport);
  }

  /** {@return each byte position within the complete opening markers} */
  private static Stream<Arguments> positions() {
    return headers().flatMap(header -> IntStream.range(0, opening(header).length)
        .mapToObj(length -> Arguments.of(header, length)));
  }

  /**
   * A marker truncated within its label or closing hyphens cannot identify a file.
   *
   * @param header The label and expected metadata.
   * @param length The available byte count.
   */
  @ParameterizedTest
  @MethodSource("positions")
  void testTruncatedMarker(PemHeader header, int length) {
    AssetTestSupport.assertUnrecognized(detector, Arrays.copyOf(opening(header), length));
  }

  /**
   * A changed marker byte is rejected even when an end marker is present.
   *
   * @param header The label and expected metadata.
   * @param offset The modified position in the opening marker.
   */
  @ParameterizedTest
  @MethodSource("positions")
  void testChangedMarker(PemHeader header, int offset) {
    final byte[] bytes = AssetTestSupport.pem(header.label());
    bytes[offset] = '?';
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * The complete opening marker identifies a possible format without validating content.
   *
   * @param header The label and expected metadata.
   */
  @ParameterizedTest
  @MethodSource("headers")
  void testOpeningWithoutEnvelope(PemHeader header) {
    AssetTestSupport.assertIdentified(detector, opening(header),
        header.format(), header.mediaType(), "uri");
  }

  /**
   * Similar labels and unfinished boundary text are not supported markers.
   *
   * @param label The unrelated label or unfinished marker text.
   */
  @ParameterizedTest
  @ValueSource(strings = {"DSA PARAMETER", "DSA PARAMETERS NOTES", "EC PARAMETERS NOTES",
      "DSA PRIVATE KITCHEN", "RSA PRIVATE KITCHEN", "PKCS70", "PKCS7 NOTES",
      "CERTIFICATE--notes", "PRIVATE KEY--notes", "PUBLIC KEY---notes"})
  void testSimilarLabel(String label) {
    AssetTestSupport.assertUnrecognized(detector, AssetTestSupport.pem(label));
  }

  /**
   * Explicit media types are preserved when an opening marker is incomplete.
   *
   * @param mediaType The supplied media type.
   */
  @ParameterizedTest
  @ValueSource(strings = {"application/octet-stream", "application/x-x509-cert"})
  void testDeclaredType(String mediaType) {
    final byte[] bytes = (BEGIN + "CERTIFICATE--notes about a certificate")
        .getBytes(StandardCharsets.US_ASCII);
    final String text = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    final var assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals(mediaType.substring(mediaType.indexOf('/') + 1), assets.getFirst().format());
    assertEquals(mediaType, assets.getFirst().mediaType());
    assertArrayEquals(bytes, assets.getFirst().decode(text));
  }

  /**
   * Builds only the opening marker, without a body or end marker.
   *
   * @param header The selected label.
   * @return The ASCII marker bytes.
   */
  private static byte[] opening(PemHeader header) {
    return (BEGIN + header.label() + BOUNDARY).getBytes(StandardCharsets.US_ASCII);
  }
}
