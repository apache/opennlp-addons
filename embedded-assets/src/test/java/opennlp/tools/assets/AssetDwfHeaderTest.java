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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests complete DWF headers with original fixtures, not drawing files. */
class AssetDwfHeaderTest {

  private static final String FORMAT = "dwf";
  private static final String MEDIA_TYPE = "model/vnd.dwf";
  private static final String VERSION = "00.55";
  private static final String SUPPLIED_MEDIA_TYPE = "application/octet-stream";
  private static final int HEADER_LENGTH = 12;
  private static final int PAYLOAD_LENGTH = 80;
  private static final List<String> TRANSPORTS = List.of("standard", "url", "mime64", "mime76", "uri");

  private final AssetDetector detector = new CursorAssetDetector();

  /** {@return the supported base64 transports} */
  private static Stream<String> transports() {
    return TRANSPORTS.stream();
  }

  /** {@return version fields from stream and package formats, plus unassigned values} */
  private static Stream<Arguments> versions() {
    return Stream.of("00.00", VERSION, "00.60", "06.00", "06.01", "07.00", "99.99")
        .flatMap(version -> transports().map(transport -> Arguments.of(version, transport)));
  }

  /**
   * Recognition checks version syntax without selecting an Autodesk release.
   *
   * @param version The version field.
   * @param transport The payload encoding.
   */
  @ParameterizedTest
  @MethodSource("versions")
  void testVersionAndTransport(String version, String transport) {
    AssetTestSupport.assertIdentified(detector, header(version, PAYLOAD_LENGTH),
        FORMAT, MEDIA_TYPE, transport);
  }

  /**
   * Encoded prose with the DWF prefix is not a drawing header.
   *
   * @param transport The payload encoding.
   */
  @ParameterizedTest
  @MethodSource("transports")
  void testEncodedNote(String transport) {
    final byte[] bytes = "(DWF Version notes) This text describes a drawing file header."
        .getBytes(StandardCharsets.US_ASCII);
    assertTrue(detector.detect(AssetTestSupport.encode(bytes, transport)).isEmpty());
  }

  /** {@return ASCII decimal boundaries and representative non-ASCII bytes at each digit position} */
  private static Stream<Arguments> versionBytes() {
    return IntStream.of(6, 7, 9, 10).boxed().flatMap(offset ->
        IntStream.of(0, '\t', '\n', '\r', ' ', '/', '0', '1', '8', '9', ':', 'A', 'a', 0x7f, 0x80, 0xff)
            .mapToObj(value -> Arguments.of(offset, value)));
  }

  /**
   * Version digits are ASCII decimals at fixed offsets.
   *
   * @param offset The digit position.
   * @param value The replacement byte.
   */
  @ParameterizedTest
  @MethodSource("versionBytes")
  void testVersionByte(int offset, int value) {
    final byte[] bytes = header(VERSION, PAYLOAD_LENGTH);
    bytes[offset] = (byte) value;
    if (value >= '0' && value <= '9') {
      AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, "standard");
      AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, "uri");
    } else {
      AssetTestSupport.assertUnrecognized(detector, bytes);
    }
  }

  /** {@return replacements for the decimal point and closing parenthesis} */
  private static Stream<Arguments> punctuationBytes() {
    return IntStream.of(8, 11).boxed().flatMap(offset ->
        IntStream.of(0, '\t', '\n', '\r', ' ', '.', ')', '0', '9', ',', '/', 0x7f, 0x80, 0xff)
            .mapToObj(value -> Arguments.of(offset, value)));
  }

  /**
   * Punctuation is part of the header, not optional whitespace.
   *
   * @param offset The punctuation position.
   * @param value The replacement byte.
   */
  @ParameterizedTest
  @MethodSource("punctuationBytes")
  void testPunctuationByte(int offset, int value) {
    final byte[] bytes = header(VERSION, PAYLOAD_LENGTH);
    bytes[offset] = (byte) value;
    if (value == (offset == 8 ? '.' : ')')) {
      AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, "standard");
    } else {
      AssetTestSupport.assertUnrecognized(detector, bytes);
    }
  }

  /**
   * Every byte of the header must be present, including in short data URIs.
   *
   * @param length The available byte count.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
  void testTruncatedHeader(int length) {
    AssetTestSupport.assertUnrecognized(detector, header(VERSION, length));
  }

  /**
   * A recognized header does not require a complete drawing or ZIP package.
   *
   * @param length The available byte count.
   */
  @ParameterizedTest
  @ValueSource(ints = {12, 13, 16, 23, 24})
  void testHeaderWithoutDrawing(int length) {
    AssetTestSupport.assertIdentified(detector, header(VERSION, length), FORMAT, MEDIA_TYPE, "uri");
  }

  /** {@return changes to each fixed prefix byte} */
  private static Stream<Arguments> prefixBytes() {
    return IntStream.range(0, 6).boxed().flatMap(offset ->
        IntStream.of(0, 'x', 0x80, 0xff).mapToObj(value -> Arguments.of(offset, value)));
  }

  /**
   * A valid version cannot identify a different file prefix.
   *
   * @param offset The prefix position.
   * @param value The replacement byte.
   */
  @ParameterizedTest
  @MethodSource("prefixBytes")
  void testChangedPrefix(int offset, int value) {
    final byte[] bytes = header(VERSION, PAYLOAD_LENGTH);
    bytes[offset] = (byte) value;
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * A header cannot use version digits or punctuation from later payload bytes.
   *
   * @param version The malformed field.
   */
  @ParameterizedTest
  @ValueSource(strings = {"0.55", "000.55", "00.5", "00.555", "00.55 ", " 00.55", "00,55"})
  void testMisplacedVersion(String version) {
    AssetTestSupport.assertUnrecognized(detector, header(version, PAYLOAD_LENGTH));
  }

  /**
   * A caller-supplied type remains available when the header is malformed.
   *
   * @param version The valid or malformed field.
   */
  @ParameterizedTest
  @ValueSource(strings = {VERSION, "00,55", "aa.bb"})
  void testDeclaredMediaType(String version) {
    final byte[] bytes = header(version, PAYLOAD_LENGTH);
    final String text = "data:" + SUPPLIED_MEDIA_TYPE + ";base64,"
        + Base64.getEncoder().encodeToString(bytes);
    final var assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals(VERSION.equals(version) ? FORMAT : "octet-stream", assets.get(0).format());
    assertEquals(SUPPLIED_MEDIA_TYPE, assets.get(0).mediaType());
    assertArrayEquals(bytes, assets.get(0).decode(text));
  }

  /**
   * W2D uses a different prefix and is not reported as DWF.
   *
   * @param transport The payload encoding.
   */
  @ParameterizedTest
  @MethodSource("transports")
  void testW2dPrefix(String transport) {
    final byte[] bytes = header("06.00", PAYLOAD_LENGTH);
    bytes[1] = 'W';
    bytes[2] = '2';
    bytes[3] = 'D';
    assertTrue(detector.detect(AssetTestSupport.encode(bytes, transport)).isEmpty());
  }

  /**
   * A drawing header inside a ZIP entry does not change the archive's detected type.
   *
   * @param transport The payload encoding.
   * @throws IOException If the in-memory archive cannot be written.
   */
  @ParameterizedTest
  @MethodSource("transports")
  void testZipContainer(String transport) throws IOException {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      final ZipEntry entry = new ZipEntry("drawing.dwf");
      entry.setTime(0);
      zip.putNextEntry(entry);
      zip.write(header(VERSION, PAYLOAD_LENGTH));
      zip.closeEntry();
    }
    AssetTestSupport.assertIdentified(detector, output.toByteArray(),
        "zip", "application/zip", transport);
  }

  /**
   * Creates an original DWF header with arbitrary trailing bytes.
   *
   * @param version The version field, including malformed test values.
   * @param length The total fixture length.
   * @return The header fixture, without drawing or package contents.
   */
  private byte[] header(String version, int length) {
    final byte[] bytes = Arrays.copyOf(("(DWF V" + version + ')')
        .getBytes(StandardCharsets.US_ASCII), length);
    if (length > HEADER_LENGTH + 2) {
      bytes[length - 2] = (byte) 0xfb;
      bytes[length - 1] = (byte) 0xff;
    }
    return bytes;
  }
}
