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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the EMR_HEADER type and EMF signature with original header-only fixtures. */
public class AssetEmfSignatureTest {

  private static final String DATA_URI = "data:;base64,";
  private static final String EMF_SIGNATURE = " EMF";
  private static final String GENERIC_MEDIA_TYPE = "application/octet-stream";

  private final AssetDetector detector = new CursorAssetDetector();

  /**
   * The EMF signature is checked beyond the initial 24 decoded bytes.
   *
   * @param length The fixture length, at least 44 bytes.
   * @param transport The base64 transport.
   */
  @ParameterizedTest
  @CsvSource({"44,standard", "45,standard", "46,standard", "88,standard",
      "44,url", "45,url", "46,url", "88,url", "88,mime64", "88,mime76",
      "44,uri", "45,uri", "46,uri", "88,uri"})
  void testSignatureBeyondInitialHeader(int length, String transport) {
    final byte[] bytes = header(length, 40, EMF_SIGNATURE);
    final String text = AssetTestSupport.encode(bytes, transport);
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    final EmbeddedAsset asset = assets.get(0);
    assertEquals("emf", asset.format());
    assertEquals("image/emf", asset.mediaType());
    assertEquals(length, asset.decodedLength());
    assertEquals(new Span(0, text.length()), asset.span());
    assertEquals(new Span(transport.equals("uri") ? DATA_URI.length() : 0, text.length()),
        asset.payload());
    assertArrayEquals(bytes, asset.decode(text));
  }

  /**
   * A type field without the complete signature does not identify EMF data.
   *
   * @param length The retained fixture bytes.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3, 4, 23, 24, 39, 40, 41, 42, 43})
  void testTruncatedSignatureIsRejected(int length) {
    assertUnrecognized(Arrays.copyOf(header(88, 40, EMF_SIGNATURE), length));
  }

  /**
   * The signature must match all bytes at offset 40.
   *
   * @param signature An incorrect signature of at most 4 ASCII characters.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "EMF", "FME ", " EMX", " emf", "\u0000EMF", " EM"})
  void testIncorrectSignatureIsRejected(String signature) {
    assertUnrecognized(header(88, 40, signature));
  }

  /**
   * EMF signature text elsewhere in the payload does not identify the format.
   *
   * @param offset The incorrect location of the signature.
   */
  @ParameterizedTest
  @ValueSource(ints = {8, 24, 36, 39, 41, 44, 80})
  void testIncorrectSignatureOffsetIsRejected(int offset) {
    assertUnrecognized(header(88, offset, EMF_SIGNATURE));
  }

  /**
   * The EMR_HEADER type is little-endian and must match all 32 bits.
   *
   * @param type An unsupported leading record type.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 2, 0x01000001, 0x01000000, -1})
  void testIncorrectRecordTypeIsRejected(int type) {
    final byte[] bytes = header(88, 40, EMF_SIGNATURE);
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(type);
    assertUnrecognized(bytes);
  }

  /** A supplied media type remains available when the EMF signature is missing. */
  @Test
  void testExplicitMediaTypeWithoutEmfSignature() {
    final byte[] bytes = header(88, 40, "");
    final String text = "data:" + GENERIC_MEDIA_TYPE + ";base64,"
        + Base64.getEncoder().encodeToString(bytes);
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals("octet-stream", assets.get(0).format());
    assertEquals(GENERIC_MEDIA_TYPE, assets.get(0).mediaType());
    assertArrayEquals(bytes, assets.get(0).decode(text));
  }

  /**
   * Builds a header fixture, not a complete metafile.
   *
   * @param length The allocated bytes.
   * @param offset The location to put the signature.
   * @param signature The ASCII signature text.
   * @return An original header with nonzero bounds and frame bytes.
   * @see <a href="https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-emf/ae7e7437-cfe5-485e-84ea-c74b51b000be">
   *     Microsoft EMR_HEADER example</a>
   */
  private byte[] header(int length, int offset, String signature) {
    final ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(1).putInt(88);
    Arrays.fill(buffer.array(), 8, 40, (byte) 0xfb);
    buffer.position(offset).put(signature.getBytes(StandardCharsets.US_ASCII));
    return buffer.array();
  }

  /**
   * Checks that both transports reject an unsupported header.
   *
   * @param bytes The fixture.
   */
  private void assertUnrecognized(byte[] bytes) {
    final String text = Base64.getEncoder().encodeToString(bytes);
    assertTrue(detector.detect(text).isEmpty());
    assertTrue(detector.detect(DATA_URI + text).isEmpty());
  }
}
