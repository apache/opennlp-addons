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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Checks complete PNG, MNG, JNG and HDF5 signatures using original header fixtures.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#5PNG-file-signature">PNG signature</a>
 * @see <a href="https://www.libpng.org/pub/mng/spec/mng-1.0-20010209-pdg.html">
 *     MNG and JNG signatures</a>
 * @see <a href="https://portal.hdfgroup.org/documentation/hdf5/latest/_f_m_t11.html">
 *     HDF5 file signature</a>
 */
public class AssetFullSignatureTest {

  private static final FileSignature JNG =
      new FileSignature("8b4a4e470d0a1a0a", "jng", "image/x-jng");
  private static final List<FileSignature> SIGNATURES = List.of(
      new FileSignature("89504e470d0a1a0a", "png", "image/png"),
      new FileSignature("8a4d4e470d0a1a0a", "mng", "video/x-mng"),
      JNG,
      new FileSignature("894844460d0a1a0a", "hdf", "application/x-hdf"));

  private final AssetDetector detector = new CursorAssetDetector();

  /**
   * The signature and expected identifiers from the format specification.
   *
   * @param hex The signature bytes in hexadecimal.
   * @param format The expected format name.
   * @param mediaType The expected media type.
   */
  private record FileSignature(String hex, String format, String mediaType) {
  }

  /** @return The independent format signatures. */
  static Stream<FileSignature> signatures() {
    return SIGNATURES.stream();
  }

  /** @return The signatures in each supported transport. */
  static Stream<Arguments> transports() {
    return signatures().flatMap(signature -> Stream.of("standard", "url", "mime64", "mime76", "uri")
        .map(transport -> Arguments.of(signature, transport)));
  }

  /** @return The signatures and positions within their 8 bytes. */
  static Stream<Arguments> positions() {
    return signatures().flatMap(signature -> IntStream.range(0, 8)
        .mapToObj(position -> Arguments.of(signature, position)));
  }

  /** @return The signatures with representative values of the next byte. */
  static Stream<Arguments> followingBytes() {
    return signatures().flatMap(signature -> IntStream.of(0, 63, 128, 255)
        .mapToObj(value -> Arguments.of(signature, value)));
  }

  /**
   * Full signatures identify encoded headers without inspecting image or dataset contents.
   *
   * @param signature The expected format.
   * @param transport The payload encoding.
   */
  @ParameterizedTest
  @MethodSource("transports")
  void testCompleteSignatures(FileSignature signature, String transport) {
    final byte[] bytes = header(signature, 64);
    assertEquals(new KnownMagics.Format(signature.format(), signature.mediaType()),
        KnownMagics.formatOf(bytes));
    AssetTestSupport.assertIdentified(detector, bytes, signature.format(), signature.mediaType(), transport);
  }

  /**
   * An untyped URI can identify a signature without enough bytes for a complete file.
   *
   * @param signature The expected format.
   */
  @ParameterizedTest
  @MethodSource("signatures")
  void testSignatureWithoutFileContents(FileSignature signature) {
    AssetTestSupport.assertIdentified(detector, header(signature, 8),
        signature.format(), signature.mediaType(), "uri");
  }

  /**
   * An incomplete signature must not identify a file.
   *
   * @param signature The format signature.
   * @param length The number of retained bytes.
   */
  @ParameterizedTest
  @MethodSource("positions")
  void testTruncatedSignatures(FileSignature signature, int length) {
    assertUnrecognized(header(signature, length));
  }

  /**
   * All signature bytes, including the final control bytes, must match.
   *
   * @param signature The format signature.
   * @param position The byte to change.
   */
  @ParameterizedTest
  @MethodSource("positions")
  void testIncorrectSignatureByte(FileSignature signature, int position) {
    final byte[] bytes = header(signature, 64);
    bytes[position] ^= 1;
    assertUnrecognized(bytes);
  }

  /**
   * The encoded prefix must not constrain bits from the next byte.
   *
   * @param signature The expected format.
   * @param value The unsigned value of the next byte.
   */
  @ParameterizedTest
  @MethodSource("followingBytes")
  void testByteAfterSignature(FileSignature signature, int value) {
    final byte[] bytes = header(signature, 64);
    bytes[8] = (byte) value;
    AssetTestSupport.assertIdentified(detector, bytes, signature.format(), signature.mediaType(), "standard");
  }

  /**
   * A supplied media type remains available for an incomplete signature.
   *
   * @param signature The format signature.
   */
  @ParameterizedTest
  @MethodSource("signatures")
  void testSuppliedMediaType(FileSignature signature) {
    final byte[] bytes = header(signature, 7);
    final String text = "data:application/octet-stream;base64,"
        + Base64.getEncoder().encodeToString(bytes);
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals("octet-stream", assets.get(0).format());
    assertEquals("application/octet-stream", assets.get(0).mediaType());
    assertArrayEquals(bytes, assets.get(0).decode(text));
  }

  /** An explicit video media type is retained without changing the JNG format name. */
  @Test
  void testSuppliedJngVideoMediaType() {
    final byte[] bytes = header(JNG, 8);
    final String text = "data:video/x-jng;base64," + Base64.getEncoder().encodeToString(bytes);
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals(JNG.format(), assets.get(0).format());
    assertEquals("video/x-jng", assets.get(0).mediaType());
    assertArrayEquals(bytes, assets.get(0).decode(text));
  }

  /**
   * Copies the signature with truncation or zero-filled trailing bytes.
   *
   * @param signature The format signature.
   * @param length The fixture length.
   * @return An original header fixture, not a complete file.
   */
  private byte[] header(FileSignature signature, int length) {
    return Arrays.copyOf(HexFormat.of().parseHex(signature.hex()), length);
  }

  /**
   * Checks the table and both detection paths for an unsupported header.
   *
   * @param bytes The input bytes.
   */
  private void assertUnrecognized(byte[] bytes) {
    assertNull(KnownMagics.formatOf(bytes));
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }
}
