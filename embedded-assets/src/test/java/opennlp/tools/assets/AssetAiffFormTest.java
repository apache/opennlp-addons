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
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests AIFF form identifiers using original headers, not complete audio files. */
public class AssetAiffFormTest {

  private static final String FORMAT = "aiff";
  private static final String MEDIA_TYPE = "audio/x-aiff";
  private static final String FORM = "FORM";
  private static final String AIFF = "AIFF";
  private static final String AIFC = "AIFC";

  private final AssetDetector detector = new CursorAssetDetector();

  /** @return The audio form types in each supported transport. */
  static Stream<Arguments> transports() {
    return Stream.of(AIFF, AIFC)
        .flatMap(type -> Stream.of("standard", "url", "mime64", "mime76", "uri")
            .map(transport -> Arguments.of(type, transport)));
  }

  /** @return Audio form types with lengths below and above 16 MiB. */
  static Stream<Arguments> sizes() {
    return Stream.of(AIFF, AIFC)
        .flatMap(type -> IntStream.of(4, 56, 65536, 0x00fffffe, 0x01000000, 0x10000000, 0x7ffffffe)
            .mapToObj(size -> Arguments.of(type, size)));
  }

  /** @return Audio form types with incomplete header lengths. */
  static Stream<Arguments> truncatedHeaders() {
    return Stream.of(AIFF, AIFC)
        .flatMap(type -> IntStream.range(0, 12).mapToObj(length -> Arguments.of(type, length)));
  }

  /** @return Audio form types at incorrect byte offsets. */
  static Stream<Arguments> offsets() {
    return Stream.of(AIFF, AIFC)
        .flatMap(type -> IntStream.of(4, 7, 9, 12, 20)
            .mapToObj(offset -> Arguments.of(type, offset)));
  }

  /**
   * AIFF and AIFC headers share the AIFF format name and media type.
   *
   * @param type The audio form type.
   * @param transport The encoding to use.
   */
  @ParameterizedTest
  @MethodSource("transports")
  void testAudioForms(String type, String transport) {
    AssetTestSupport.assertIdentified(detector, header(type, 56), FORMAT, MEDIA_TYPE, transport);
  }

  /**
   * Format inference does not use the size field as part of the signature.
   *
   * @param type The audio form type.
   * @param size The value in the FORM length field.
   */
  @ParameterizedTest
  @MethodSource("sizes")
  void testFormSizeIsNotSignature(String type, int size) {
    final byte[] bytes = header(type, size);
    AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, "standard");
    AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, "uri");
  }

  /**
   * A complete FORM identifier cannot replace a missing audio form type.
   *
   * @param type The audio form type before truncation.
   * @param length The retained header length.
   */
  @ParameterizedTest
  @MethodSource("truncatedHeaders")
  void testIncompleteFormType(String type, int length) {
    assertUnrecognized(Arrays.copyOf(header(type, 56), length));
  }

  /**
   * A complete identifier can identify a possible format without validating local chunks.
   *
   * @param type The audio form type.
   */
  @ParameterizedTest
  @ValueSource(strings = {AIFF, AIFC})
  void testFormTypeWithoutLocalChunks(String type) {
    AssetTestSupport.assertIdentified(detector, Arrays.copyOf(header(type, 4), 12),
        FORMAT, MEDIA_TYPE, "uri");
  }

  /**
   * Other FORM types and incorrect audio tags are not AIFF.
   *
   * @param type The non-AIFF form type.
   */
  @ParameterizedTest
  @ValueSource(strings = {"ILBM", "8SVX", "ANIM", "PBM ", "MAUD", "????", "aiff", "aifc"})
  void testOtherFormTypes(String type) {
    assertUnrecognized(header(type, 56));
  }

  /**
   * A byte after the 4-byte form type does not change the format identifier.
   *
   * @param typeAndByte The audio form type and an additional byte.
   */
  @ParameterizedTest
  @ValueSource(strings = {"AIFFX", "AIFCX"})
  void testByteAfterFormType(String typeAndByte) {
    AssetTestSupport.assertIdentified(detector, header(typeAndByte, 56), FORMAT, MEDIA_TYPE, "standard");
  }

  /**
   * The audio form type must begin at byte 8.
   *
   * @param type The audio form type.
   * @param offset The incorrect byte offset.
   */
  @ParameterizedTest
  @MethodSource("offsets")
  void testIncorrectFormTypeOffset(String type, int offset) {
    final byte[] bytes = header("????", 56);
    ByteBuffer.wrap(bytes).position(offset).put(type.getBytes(StandardCharsets.US_ASCII));
    assertUnrecognized(bytes);
  }

  /**
   * Audio form types do not identify other container kinds as AIFF.
   *
   * @param container The incorrect leading identifier.
   */
  @ParameterizedTest
  @ValueSource(strings = {"RIFF", "RIFX", "LIST", "CAT ", "F0RM", "form"})
  void testIncorrectContainer(String container) {
    final byte[] bytes = header(AIFF, 56);
    ByteBuffer.wrap(bytes).put(container.getBytes(StandardCharsets.US_ASCII));
    assertUnrecognized(bytes);
  }

  /** A supplied media type remains available for a non-AIFF form. */
  @Test
  void testSuppliedTypeForOtherForm() {
    final byte[] bytes = header("ILBM", 56);
    final String text = "data:application/octet-stream;base64,"
        + Base64.getEncoder().encodeToString(bytes);
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals("octet-stream", assets.get(0).format());
    assertEquals("application/octet-stream", assets.get(0).mediaType());
    assertArrayEquals(bytes, assets.get(0).decode(text));
  }

  /**
   * Builds an original 64-byte FORM header fixture with zero-filled trailing bytes.
   *
   * @param type The form type at byte 8.
   * @param size The value in the FORM length field, independent of fixture length.
   * @return The header fixture, without valid local chunks or audio data.
   * @see <a href="https://www.mmsp.ece.mcgill.ca/Documents/AudioFormats/AIFF/Docs/AIFF-1.3.pdf#page=5">
   *     AIFF FORM header</a>
   */
  private byte[] header(String type, int size) {
    return ByteBuffer.allocate(64).put(FORM.getBytes(StandardCharsets.US_ASCII))
        .putInt(size).put(type.getBytes(StandardCharsets.US_ASCII)).array();
  }

  /**
   * Checks standard base64 and untyped data URIs for an unsupported header.
   *
   * @param bytes The input bytes.
   */
  private void assertUnrecognized(byte[] bytes) {
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }
}
