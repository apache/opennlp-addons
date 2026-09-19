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
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static opennlp.tools.assets.AssetTestSupport.gif;
import static opennlp.tools.assets.AssetTestSupport.png;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks image dimensions separately from format identification. */
public class AssetImageMetadataTest {

  /**
   * Supplies recognizable image headers with invalid or incomplete dimensions.
   *
   * @return Labels, format names, and header bytes.
   */
  private static Stream<Arguments> invalidHeaders() {
    final byte[] wrongType = png(5, 7);
    wrongType[12] = 'X';
    final byte[] wrongLength = png(5, 7);
    wrongLength[11] = 12;
    final byte[] wrongPngSignature = png(5, 7);
    wrongPngSignature[0] = 0;
    final byte[] wrongGifSignature = gif(5, 7);
    wrongGifSignature[0] = 'X';
    return Stream.of(
        Arguments.of("PNG zero width", "png", png(0, 7)),
        Arguments.of("PNG zero height", "png", png(5, 0)),
        Arguments.of("PNG unsigned width overflow", "png", png(Integer.MIN_VALUE, 7)),
        Arguments.of("PNG unsigned height overflow", "png", png(5, Integer.MIN_VALUE)),
        Arguments.of("PNG largest unsigned width", "png", png(-1, 7)),
        Arguments.of("PNG wrong first chunk", "png", wrongType),
        Arguments.of("PNG wrong IHDR length", "png", wrongLength),
        Arguments.of("PNG declared without its signature", "png", wrongPngSignature),
        Arguments.of("PNG missing height bytes", "png", Arrays.copyOf(png(5, 7), 23)),
        Arguments.of("GIF zero width", "gif", gif(0, 7)),
        Arguments.of("GIF zero height", "gif", gif(5, 0)),
        Arguments.of("GIF declared without its signature", "gif", wrongGifSignature),
        Arguments.of("GIF missing height bytes", "gif", Arrays.copyOf(gif(5, 7), 9)));
  }

  /**
   * Leaves dimensions unknown without losing the format or encoded payload.
   *
   * @param label The malformed header case.
   * @param format The format recognizable from the signature.
   * @param bytes The header bytes.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidHeaders")
  void testInvalidMetadataDoesNotDiscardAsset(String label, String format, byte[] bytes) {
    final String text = "data:image/" + format + ";base64,"
        + Base64.getEncoder().encodeToString(bytes);
    final var assets = new CursorAssetDetector().detect(text);
    assertEquals(1, assets.size(), label);
    final EmbeddedAsset asset = assets.get(0);
    assertEquals(format, asset.format());
    assertEquals(-1, asset.width());
    assertEquals(-1, asset.height());
    assertEquals(bytes.length, asset.decodedLength());
    assertArrayEquals(bytes, asset.decode(text));
  }

  /**
   * Retains the full legal PNG dimension range without allocating image pixels.
   *
   * @param dimension The declared width and height.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, Integer.MAX_VALUE})
  void testPngDimensionLimits(int dimension) {
    final String text = Base64.getEncoder().encodeToString(png(dimension, dimension));
    final EmbeddedAsset asset = new CursorAssetDetector().detect(text).get(0);
    assertEquals(dimension, asset.width());
    assertEquals(dimension, asset.height());
  }

  /**
   * Reads GIF logical screen dimensions as unsigned little-endian values.
   *
   * @param dimension The declared width and height.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 65535})
  void testGifDimensionLimits(int dimension) {
    final String text = Base64.getEncoder().encodeToString(gif(dimension, dimension));
    final EmbeddedAsset asset = new CursorAssetDetector().detect(text).get(0);
    assertEquals(dimension, asset.width());
    assertEquals(dimension, asset.height());
  }
}
