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
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests original caption text against the
 * <a href="https://www.w3.org/TR/2026/CRD-webvtt1-20260520/#iana-text-vtt">WebVTT magic</a>.
 */
class AssetWebVttTest {

  private static final String SIGNATURE = "WEBVTT";
  private static final String BOM = "\uFEFF";
  private static final String FORMAT = "vtt";
  private static final String MEDIA_TYPE = "text/vtt";
  private static final String DATA_URI = "data:;base64,";
  private static final String CUE = "00:00.000 --> 00:03.000\n"
      + "The trail passes a café. Follow the blue signs. 🧭\n";

  private final AssetDetector detector = new CursorAssetDetector();

  /** {@return complete caption headers with and without a UTF-8 BOM} */
  private static Stream<String> headers() {
    return Stream.of("", BOM).flatMap(bom -> Stream.of(
        "\n\n", "\r\r", "\r\n\r\n", " OpenNLP captions\n\n", "\tOpenNLP captions\n\n")
        .map(tail -> bom + SIGNATURE + tail));
  }

  /** {@return caption headers in each supported base64 transport} */
  private static Stream<Arguments> captions() {
    return headers().flatMap(header -> Stream.of("standard", "url", "mime64", "mime76", "uri")
        .map(transport -> Arguments.of(header, transport)));
  }

  /**
   * Complete caption files retain their UTF-8 bytes and source offsets.
   *
   * @param header The header, including its line terminators.
   * @param transport The payload encoding.
   */
  @ParameterizedTest
  @MethodSource("captions")
  void testCaptionFile(String header, String transport) {
    final byte[] bytes = (header + CUE).getBytes(StandardCharsets.UTF_8);
    AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, transport);
  }

  /** {@return all possible bytes after both signature forms} */
  private static Stream<Arguments> boundaries() {
    return Stream.of("", BOM).flatMap(bom -> IntStream.rangeClosed(0, 255)
        .mapToObj(value -> Arguments.of(bom, value)));
  }

  /**
   * Only TAB, LF, CR and SPACE may immediately follow the signature.
   *
   * @param bom The optional byte-order mark.
   * @param value The following byte.
   */
  @ParameterizedTest
  @MethodSource("boundaries")
  void testBoundaryByte(String bom, int value) {
    final byte[] signature = (bom + SIGNATURE).getBytes(StandardCharsets.UTF_8);
    final byte[] bytes = Arrays.copyOf(signature, 80);
    Arrays.fill(bytes, signature.length, bytes.length, (byte) 'x');
    bytes[signature.length] = (byte) value;
    if (value == '\t' || value == '\n' || value == '\r' || value == ' ') {
      AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, "standard");
      AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, "uri");
    } else {
      AssetTestSupport.assertUnrecognized(detector, bytes);
    }
  }

  /** {@return short headers with each permitted separator, including EOF} */
  private static Stream<String> shortHeaders() {
    return Stream.of("", BOM).flatMap(bom -> Stream.of("", "\t", "\n", "\r", "\r\n", " ")
        .map(tail -> bom + SIGNATURE + tail));
  }

  /**
   * Header recognition does not require valid cues or a complete file.
   *
   * @param header The signature and optional separator.
   */
  @ParameterizedTest
  @MethodSource("shortHeaders")
  void testHeaderWithoutCue(String header) {
    AssetTestSupport.assertIdentified(detector, header.getBytes(StandardCharsets.UTF_8),
        FORMAT, MEDIA_TYPE, "uri");
  }

  /**
   * Bare short headers remain below the detector's minimum encoded length.
   *
   * @param header The signature and optional separator.
   */
  @ParameterizedTest
  @MethodSource("shortHeaders")
  void testBareShortHeader(String header) {
    final String encoded = Base64.getEncoder().encodeToString(header.getBytes(StandardCharsets.UTF_8));
    assertTrue(detector.detect(encoded).isEmpty());
  }

  /** {@return incomplete signatures, including truncation within the UTF-8 BOM} */
  private static Stream<Arguments> truncatedHeaders() {
    return Stream.of("", BOM).flatMap(bom -> {
      final byte[] bytes = (bom + SIGNATURE).getBytes(StandardCharsets.UTF_8);
      return IntStream.range(0, bytes.length)
          .mapToObj(length -> Arguments.of(Arrays.copyOf(bytes, length)));
    });
  }

  /**
   * An untyped data URI cannot infer a type from an incomplete signature.
   *
   * @param bytes The truncated header.
   */
  @ParameterizedTest
  @MethodSource("truncatedHeaders")
  void testTruncatedHeader(byte[] bytes) {
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * Different case, extra marks, and displaced or modified signatures are not WebVTT.
   *
   * @param prefix The invalid signature text.
   */
  @ParameterizedTest
  @ValueSource(strings = {"webvtt", "WebVTT", "WEBVT", "WEBVTTX", " WEBVTT", "\nWEBVTT",
      "\uFEFF\uFEFFWEBVTT", "\uFFFEWEBVTT", "WEB\uFEFFVTT", "\uFEFFwebvtt"})
  void testInvalidPrefix(String prefix) {
    AssetTestSupport.assertUnrecognized(detector,
        (prefix + "\n\n" + CUE).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * UTF-16 encodings are not UTF-8 WebVTT headers.
   *
   * @param bigEndian Whether to encode the code units in big-endian order.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testUtf16Header(boolean bigEndian) {
    final byte[] bytes = (BOM + SIGNATURE + "\n\n" + CUE).getBytes(
        bigEndian ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE);
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * A supplied type remains available for bytes without a recognized signature.
   *
   * @param mediaType The supplied media type.
   */
  @ParameterizedTest
  @ValueSource(strings = {MEDIA_TYPE, "text/plain"})
  void testDeclaredType(String mediaType) {
    final byte[] bytes = (SIGNATURE + "X\n\n" + CUE).getBytes(StandardCharsets.UTF_8);
    final String text = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    final var assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals(mediaType.substring(mediaType.indexOf('/') + 1), assets.getFirst().format());
    assertEquals(mediaType, assets.getFirst().mediaType());
    assertArrayEquals(bytes, assets.getFirst().decode(text));
  }

  /** Runs the caption example in the embedded-assets manual. */
  @Test
  void testManualCaptionExample() {
    final String captions = "\uFEFFWEBVTT\tTrail captions\n\n"
        + "00:00.000 --> 00:03.000\nTurn left at the bridge.\n";
    final String text = DATA_URI
        + Base64.getEncoder().encodeToString(captions.getBytes(StandardCharsets.UTF_8));
    final var assets = detector.detect(text);
    assertEquals(1, assets.size());
    final EmbeddedAsset asset = assets.getFirst();
    assertEquals(FORMAT, asset.format());
    assertEquals(MEDIA_TYPE, asset.mediaType());
    assertEquals(captions, new String(asset.decode(text), StandardCharsets.UTF_8));
  }
}
