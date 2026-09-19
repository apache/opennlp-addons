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
import java.util.Base64;

import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Header fixtures and encodings for format detection, not complete files. */
final class AssetTestSupport {

  private static final String DATA_URI = "data:;base64,";

  /** Prevents construction of the fixture utility. */
  private AssetTestSupport() {
  }

  /** {@return an original SVG circle with an explicit namespace} */
  static byte[] svg() {
    return ("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'>"
        + "<circle cx='5' cy='5' r='4'/></svg>").getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Builds an original PEM envelope with placeholder bytes, not cryptographic data.
   *
   * @param label The opening and closing label.
   * @return The ASCII envelope bytes.
   */
  static byte[] pem(String label) {
    return ("-----BEGIN " + label + "-----\nAAAA\n-----END " + label + "-----\n")
        .getBytes(StandardCharsets.US_ASCII);
  }

  /**
   * Builds DEX magic followed by zero-filled bytes, without executable contents.
   *
   * @param version The version text to insert, including malformed test values.
   * @param length The resulting byte count.
   * @return The complete or truncated header fixture.
   */
  static byte[] dex(String version, int length) {
    return Arrays.copyOf(("dex\n" + version + '\0').getBytes(StandardCharsets.US_ASCII), length);
  }

  /**
   * Builds a 45-byte PNG prefix with an IHDR chunk and zero-filled trailing bytes.
   *
   * @param width The declared width.
   * @param height The declared height.
   * @return The header fixture, without a valid CRC or image data.
   */
  static byte[] png(int width, int height) {
    return ByteBuffer.allocate(45)
        .put(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})
        .putInt(13)
        .put(new byte[] {'I', 'H', 'D', 'R'})
        .putInt(width).putInt(height)
        .put(new byte[] {8, 6, 0, 0, 0}).array();
  }

  /**
   * Builds a 30-byte GIF prefix with zero-filled trailing bytes.
   *
   * @param width The declared logical screen width.
   * @param height The declared logical screen height.
   * @return The header fixture, without image data.
   */
  static byte[] gif(int width, int height) {
    return ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        .put(new byte[] {'G', 'I', 'F', '8', '9', 'a'})
        .putShort((short) width).putShort((short) height).array();
  }

  /**
   * Encodes a fixture with the selected transport.
   *
   * @param bytes The fixture.
   * @param transport The transport name.
   * @return The encoded text, including the data-URI prefix when requested.
   * @throws IllegalArgumentException If the transport is unsupported.
   */
  static String encode(byte[] bytes, String transport) {
    return switch (transport) {
      case "standard" -> Base64.getEncoder().encodeToString(bytes);
      case "url" -> Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      case "mime64" -> Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
      case "mime76" -> Base64.getMimeEncoder(76, new byte[] {'\r', '\n'}).encodeToString(bytes);
      case "uri" -> DATA_URI + Base64.getEncoder().encodeToString(bytes);
      default -> throw new IllegalArgumentException("transport is unsupported");
    };
  }

  /**
   * Checks format inference, source spans, decoded length and exact bytes.
   *
   * @param detector The detector to exercise.
   * @param bytes The input bytes.
   * @param format The expected format name.
   * @param mediaType The expected media type.
   * @param transport The encoding to use.
   * @throws IllegalArgumentException If the transport is unsupported.
   */
  static void assertIdentified(AssetDetector detector, byte[] bytes, String format,
                              String mediaType, String transport) {
    final String encoded = encode(bytes, transport);
    final String prefix = "Attachment: [";
    final int payloadOffset = transport.equals("uri") ? DATA_URI.length() : 0;
    final String text = prefix + encoded + "]";
    final var assets = detector.detect(text);
    assertEquals(1, assets.size());
    final EmbeddedAsset asset = assets.get(0);
    assertEquals(format, asset.format());
    assertEquals(mediaType, asset.mediaType());
    assertEquals(bytes.length, asset.decodedLength());
    assertEquals(new Span(prefix.length(), text.length() - 1), asset.span());
    assertEquals(new Span(prefix.length() + payloadOffset, text.length() - 1), asset.payload());
    assertArrayEquals(bytes, asset.decode(text));
  }

  /**
   * Checks standard base64 and untyped data URIs for an unrecognized header.
   *
   * @param detector The detector to exercise.
   * @param bytes The input bytes.
   */
  static void assertUnrecognized(AssetDetector detector, byte[] bytes) {
    final String encoded = Base64.getEncoder().encodeToString(bytes);
    assertTrue(detector.detect(encoded).isEmpty());
    assertTrue(detector.detect(DATA_URI + encoded).isEmpty());
  }
}
