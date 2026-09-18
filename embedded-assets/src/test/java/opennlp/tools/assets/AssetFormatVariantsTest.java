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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests format signatures independently of version, encoding and record-count fields. */
class AssetFormatVariantsTest {

  private static final int HEADER_LENGTH = 80;
  private static final String AU_FORMAT = "au";
  private static final String AU_MEDIA_TYPE = "audio/basic";
  private static final String AXX_MAGIC = "c0b9072e4f93f146a015792ca1d9e821";
  private static final String FAT_64_MAGIC = "cafebabf";
  private static final String FAT_32_REVERSED_MAGIC = "bebafeca";
  private static final String FAT_64_REVERSED_MAGIC = "bfbafeca";
  private static final List<String> TRANSPORTS = List.of("standard", "url", "mime64", "mime76", "uri");

  private final AssetDetector detector = new CursorAssetDetector();

  /** {@return the supported base64 transports} */
  private static Stream<String> transports() {
    return TRANSPORTS.stream();
  }

  /**
   * Combines numeric header values with the base64 transports.
   *
   * @param values The header field values.
   * @return Field values with each transport.
   */
  private static Stream<Arguments> valuesAndTransports(IntStream values) {
    return values.boxed().flatMap(value -> transports().map(transport -> Arguments.of(value, transport)));
  }

  /** {@return AU encodings, including A-law and the ADPCM values outside 1-7} */
  private static Stream<Arguments> auEncodings() {
    return valuesAndTransports(IntStream.of(1, 2, 3, 4, 5, 6, 7, 23, 24, 25, 26, 27));
  }

  /**
   * The AU signature does not select a particular audio encoding.
   *
   * @param encoding The encoding field.
   * @param transport The payload encoding.
   * @see <a href="https://docs.oracle.com/cd/E19253-01/816-5174/6mbb98ucf/index.html">AU file format</a>
   */
  @ParameterizedTest
  @MethodSource("auEncodings")
  void testAuEncoding(int encoding, String transport) {
    final byte[] bytes = ByteBuffer.allocate(HEADER_LENGTH)
        .putInt(0x2e736e64).putInt(28).putInt(HEADER_LENGTH - 28)
        .putInt(encoding).putInt(8000).putInt(1).array();
    AssetTestSupport.assertIdentified(detector, bytes, AU_FORMAT, AU_MEDIA_TYPE, transport);
  }

  /**
   * A complete A-law AU file written and read by Java Sound is detected.
   *
   * @param transport The payload encoding.
   * @throws IOException If the in-memory audio file cannot be written or read.
   * @throws UnsupportedAudioFileException If Java Sound rejects its generated AU file.
   */
  @ParameterizedTest
  @MethodSource("transports")
  void testJavaSoundAlaw(String transport) throws IOException, UnsupportedAudioFileException {
    final AudioFormat format = new AudioFormat(AudioFormat.Encoding.ALAW, 8000, 8, 1, 1, 8000, false);
    final byte[] samples = new byte[64];
    Arrays.fill(samples, (byte) 0xd5);
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(samples),
        format, samples.length)) {
      AudioSystem.write(input, AudioFileFormat.Type.AU, output);
    }
    final byte[] bytes = output.toByteArray();
    final AudioFileFormat parsed = AudioSystem.getAudioFileFormat(new ByteArrayInputStream(bytes));
    assertEquals(AudioFileFormat.Type.AU, parsed.getType());
    assertEquals(AudioFormat.Encoding.ALAW, parsed.getFormat().getEncoding());
    assertEquals(27, ByteBuffer.wrap(bytes).getInt(12));
    AssetTestSupport.assertIdentified(detector, bytes, AU_FORMAT, AU_MEDIA_TYPE, transport);
  }

  /** {@return LAS 1.0 through 1.5 headers in each transport} */
  private static Stream<Arguments> lasVersions() {
    return valuesAndTransports(IntStream.rangeClosed(0, 5));
  }

  /**
   * LAS versions after 1.2 retain the same identifying signature.
   *
   * @param minor The minor version.
   * @param transport The payload encoding.
   * @see <a href="https://lasformat.org/latest/02.00_definition.html#public-header-block">LAS header</a>
   */
  @ParameterizedTest
  @MethodSource("lasVersions")
  void testLasVersion(int minor, String transport) {
    final byte[] bytes = header("4c415346");
    bytes[24] = 1;
    bytes[25] = (byte) minor;
    AssetTestSupport.assertIdentified(detector, bytes, "las", "application/x-asprs", transport);
  }

  /** {@return architecture counts across the unsigned 32-bit field for each distinct fat signature} */
  private static Stream<Arguments> architectureCounts() {
    return Stream.of(FAT_64_MAGIC, FAT_32_REVERSED_MAGIC, FAT_64_REVERSED_MAGIC)
        .flatMap(magic -> IntStream.of(1, 2, 19, 20, 255, Integer.MAX_VALUE, Integer.MIN_VALUE, -1)
            .boxed().flatMap(count -> transports().map(transport -> Arguments.of(magic, count, transport))));
  }

  /**
   * Architecture counts do not change signature recognition or validate executable contents.
   *
   * @param magic The catalog signature.
   * @param count The unsigned architecture count, stored in an int.
   * @param transport The payload encoding.
   * @see <a href="https://github.com/apple-oss-distributions/cctools/blob/main/include/mach-o/fat.h">
   *     Mach-O universal header</a>
   */
  @ParameterizedTest
  @MethodSource("architectureCounts")
  void testArchitectureCount(String magic, int count, String transport) {
    final byte[] bytes = header(magic);
    ByteBuffer.wrap(bytes).order(magic.equals(FAT_64_MAGIC) ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN)
        .putInt(4, count);
    AssetTestSupport.assertIdentified(detector, bytes, "macho-fat",
        "application/x-mach-o-universal", transport);
  }

  /** {@return values after the magic shared by Java classes and 32-bit Mach-O universal files} */
  private static Stream<Arguments> sharedMagicFields() {
    return valuesAndTransports(IntStream.of(1, 2, 20, 61, 255));
  }

  /**
   * The shared CAFEBABE signature retains the catalog's Java classification.
   *
   * @param value The following field, which is not used to resolve the shared signature.
   * @param transport The payload encoding.
   */
  @ParameterizedTest
  @MethodSource("sharedMagicFields")
  void testSharedJavaMagic(int value, String transport) {
    final byte[] bytes = header("cafebabe");
    ByteBuffer.wrap(bytes).putInt(4, value);
    AssetTestSupport.assertIdentified(detector, bytes,
        EmbeddedAsset.FORMAT_CLASS, "application/java-vm", transport);
  }

  /** {@return AxCrypt file-format versions used by both software generations} */
  private static Stream<Arguments> axcryptVersions() {
    return valuesAndTransports(IntStream.of(1, 2, 3, 4));
  }

  /**
   * AxCrypt format versions share the GUID; header blocks and HMAC values are not validated.
   *
   * @param version The file-format version, not the software version.
   * @param transport The payload encoding.
   * @see <a href="https://axcrypt.net/information/technical-details/">AxCrypt file formats</a>
   */
  @ParameterizedTest
  @MethodSource("axcryptVersions")
  void testAxcryptVersion(int version, String transport) {
    final byte[] bytes = header(AXX_MAGIC);
    final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(16, 21).put(20, (byte) 2);
    buffer.putInt(37, 10).put(41, (byte) 3).put(42, (byte) version);
    AssetTestSupport.assertIdentified(detector, bytes, "axx", "application/x-axcrypt", transport);
  }

  /** {@return signature bytes and positions for the changed-byte and truncation tests} */
  private static Stream<Arguments> changedSignatures() {
    return Stream.of("2e736e64", "4c415346", AXX_MAGIC,
        FAT_64_MAGIC, FAT_32_REVERSED_MAGIC, FAT_64_REVERSED_MAGIC)
        .flatMap(magic -> IntStream.range(0, magic.length() / 2)
            .mapToObj(offset -> Arguments.of(magic, offset)));
  }

  /**
   * Variable-field tolerance does not permit altered signature bytes.
   *
   * @param magic The original signature.
   * @param offset The byte to replace.
   */
  @ParameterizedTest
  @MethodSource("changedSignatures")
  void testChangedSignature(String magic, int offset) {
    final byte[] bytes = header(magic);
    bytes[offset] = '?';
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * Incomplete signatures cannot identify these formats.
   *
   * @param magic The complete signature.
   * @param length The available byte count.
   */
  @ParameterizedTest
  @MethodSource("changedSignatures")
  void testTruncatedSignature(String magic, int length) {
    AssetTestSupport.assertUnrecognized(detector, Arrays.copyOf(HexFormat.of().parseHex(magic), length));
  }

  /**
   * Builds a zero-filled header fixture, not a complete file.
   *
   * @param magic The initial bytes in hexadecimal.
   * @return The signature and zero-filled remainder.
   */
  private byte[] header(String magic) {
    return Arrays.copyOf(HexFormat.of().parseHex(magic), HEADER_LENGTH);
  }
}
