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
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the record contract of {@link EmbeddedAsset} under direct construction: every
 * compact-constructor rejection with its exact message, the {@code decode} bounds
 * check, and the accessor round-trip of a valid instance.
 */
public class EmbeddedAssetTest {

  /**
   * Builds a valid asset whose payload sits inside its span, for the accessor and
   * decode tests.
   *
   * @return An asset with span 4..12, payload 8..12, and matching metadata.
   */
  private EmbeddedAsset valid() {
    return new EmbeddedAsset(new Span(4, 12), new Span(8, 12), "png", "image/png",
        3, 5, 7);
  }

  /**
   * The invalid constructions the compact constructor documents, each paired with the
   * exact rejection message it must produce.
   *
   * @return Invalid arguments and their rejection messages.
   */
  private static Stream<Arguments> invalidConstructions() {
    return Stream.of(
        Arguments.of("span must not be null",
            (Executable) () -> new EmbeddedAsset(null, new Span(8, 12),
                "png", "image/png", 3, -1, -1)),
        Arguments.of("payload must not be null",
            (Executable) () -> new EmbeddedAsset(new Span(4, 12), null,
                "png", "image/png", 3, -1, -1)),
        Arguments.of("format must not be null or blank",
            (Executable) () -> new EmbeddedAsset(new Span(4, 12), new Span(8, 12),
                null, "image/png", 3, -1, -1)),
        Arguments.of("mediaType must not be null or blank",
            (Executable) () -> new EmbeddedAsset(new Span(4, 12), new Span(8, 12),
                "png", null, 3, -1, -1)),
        Arguments.of("payload must lie inside the span",
            (Executable) () -> new EmbeddedAsset(new Span(4, 12), new Span(3, 12),
                "png", "image/png", 3, -1, -1)),
        Arguments.of("payload must lie inside the span",
            (Executable) () -> new EmbeddedAsset(new Span(4, 12), new Span(4, 13),
                "png", "image/png", 3, -1, -1)),
        Arguments.of("format must not be null or blank",
            (Executable) () -> new EmbeddedAsset(new Span(4, 12), new Span(8, 12),
                " ", "image/png", 3, -1, -1)),
        Arguments.of("mediaType must not be null or blank",
            (Executable) () -> new EmbeddedAsset(new Span(4, 12), new Span(8, 12),
                "png", " ", 3, -1, -1)),
        Arguments.of("decodedLength must not be negative",
            (Executable) () -> new EmbeddedAsset(new Span(4, 12), new Span(8, 12),
                "png", "image/png", -1, -1, -1)));
  }

  @ParameterizedTest
  @MethodSource("invalidConstructions")
  void testConstructorRejectsWithTheDocumentedMessage(String message,
      Executable construction) {
    final IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, construction);
    assertEquals(message, e.getMessage());
  }

  /**
   * Rejects dimensions that are neither positive nor the unknown sentinel.
   *
   * @param dimension The invalid dimension.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, -2, Integer.MIN_VALUE})
  void testRejectsInvalidDimensions(int dimension) {
    final Span span = new Span(0, 4);
    assertEquals("width must be positive or -1", assertThrows(IllegalArgumentException.class,
        () -> new EmbeddedAsset(span, span, "png", "image/png", 3, dimension, 1))
        .getMessage());
    assertEquals("height must be positive or -1", assertThrows(IllegalArgumentException.class,
        () -> new EmbeddedAsset(span, span, "png", "image/png", 3, 1, dimension))
        .getMessage());
  }

  /**
   * Retains positive dimensions and permits an unknown dimension independently.
   *
   * @param dimension A positive dimension or the unknown sentinel.
   */
  @ParameterizedTest
  @ValueSource(ints = {-1, 1, Integer.MAX_VALUE})
  void testAcceptsDimensions(int dimension) {
    final Span span = new Span(0, 4);
    final EmbeddedAsset asset = new EmbeddedAsset(span, span, "png", "image/png",
        3, dimension, dimension);
    assertEquals(dimension, asset.width());
    assertEquals(dimension, asset.height());
    assertEquals(dimension, new EmbeddedAsset(span, span, "png", "image/png",
        3, dimension, -1).width());
    assertEquals(dimension, new EmbeddedAsset(span, span, "png", "image/png",
        3, -1, dimension).height());
  }

  /** Rejects a missing source before attempting to read the payload span. */
  @Test
  void testDecodeRejectsNull() {
    assertEquals("text must not be null", assertThrows(IllegalArgumentException.class,
        () -> valid().decode(null)).getMessage());
  }

  @Test
  void testAccessorsRoundTrip() {
    final EmbeddedAsset asset = valid();
    assertEquals(new Span(4, 12), asset.span());
    assertEquals(new Span(8, 12), asset.payload());
    assertEquals("png", asset.format());
    assertEquals("image/png", asset.mediaType());
    assertEquals(3, asset.decodedLength());
    assertEquals(5, asset.width());
    assertEquals(7, asset.height());
  }

  @Test
  void testDecodeReadsThePayloadSpanFromTheText() {
    // Payload characters at 8..12 are the base64 image of "ABC".
    assertArrayEquals(new byte[] {'A', 'B', 'C'}, valid().decode("padding QUJD"));
  }

  @Test
  void testDecodeRejectsTextShorterThanThePayloadSpan() {
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> valid().decode("short"));
    assertEquals("text is shorter than the payload span", e.getMessage());
  }

  /** Wrapped MIME base64 decodes through the same public record operation. */
  @Test
  void testDecodeAcceptsWrappedStandardBase64() {
    final String encoded = Base64.getMimeEncoder(4, "\r\n".getBytes(StandardCharsets.US_ASCII))
        .encodeToString("ABCDEF".getBytes(StandardCharsets.US_ASCII));
    final EmbeddedAsset asset = new EmbeddedAsset(new Span(0, encoded.length()),
        new Span(0, encoded.length()), "bin", "application/octet-stream", 6, -1, -1);

    assertArrayEquals("ABCDEF".getBytes(StandardCharsets.US_ASCII), asset.decode(encoded));
  }

  /** Unpadded base64url decodes without conversion to the standard alphabet. */
  @Test
  void testDecodeAcceptsUnpaddedBase64Url() {
    final byte[] bytes = {(byte) 0xFB, (byte) 0xFF, (byte) 0xFF};
    final String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    final EmbeddedAsset asset = new EmbeddedAsset(new Span(0, encoded.length()),
        new Span(0, encoded.length()), "bin", "application/octet-stream", 3, -1, -1);

    assertArrayEquals(bytes, asset.decode(encoded));
  }

  /** A payload cannot combine the standard-only and URL-only alphabet characters. */
  @Test
  void testDecodeRejectsMixedBase64Alphabets() {
    final EmbeddedAsset asset = new EmbeddedAsset(new Span(0, 4), new Span(0, 4),
        "bin", "application/octet-stream", 3, -1, -1);

    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> asset.decode("+_AA"));

    assertEquals("payload mixes base64 and base64url alphabets", e.getMessage());
  }
}
