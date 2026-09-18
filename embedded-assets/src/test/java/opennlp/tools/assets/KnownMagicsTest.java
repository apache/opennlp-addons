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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests file signatures, base64 prefixes, media types, and longest-match selection.
 */
public class KnownMagicsTest {

  /** DXB's 19-byte header, including CR, LF, SUB, and NUL. */
  private static final String DXB_HEADER = "AutoCAD DXB 1.0\r\n\u001a\u0000";

  /** Every prefix is the base64 image of its magic, floored to whole characters. */
  @Test
  void testPrefixesAreTheFlooredBase64ImageOfTheMagic() {
    for (final KnownMagics.Entry entry : KnownMagics.ENTRIES) {
      final String encoded =
          Base64.getEncoder().withoutPadding().encodeToString(entry.magic());
      assertEquals(entry.magic().length * 8 / 6, entry.prefix().length(),
          entry.format().name() + ": incorrect base64 prefix length");
      assertTrue(encoded.startsWith(entry.prefix()),
          entry.format().name() + ": the prefix must be the image of the magic");
    }
  }

  /** Entries are held longest first, so a lookup yields the most specific match. */
  @Test
  void testEntriesAreSortedLongestFirst() {
    for (int i = 1; i < KnownMagics.ENTRIES.size(); i++) {
      assertTrue(KnownMagics.ENTRIES.get(i - 1).magic().length
              >= KnownMagics.ENTRIES.get(i).magic().length,
          "entry " + i + " breaks the longest-first order");
    }
  }

  /** No two entries carry identical magic bytes. */
  @Test
  void testNoDuplicateMagics() {
    final Set<String> seen = new HashSet<>();
    for (final KnownMagics.Entry entry : KnownMagics.ENTRIES) {
      assertTrue(seen.add(Arrays.toString(entry.magic())),
          "duplicate magic for " + entry.format().name());
    }
  }

  /** The longest matching magic wins where one magic extends another. */
  @Test
  void testLookupFavorsTheLongestMagic() {
    final byte[] deb = "!<arch>\ndebian-binary junk".getBytes(StandardCharsets.US_ASCII);
    assertEquals("deb", KnownMagics.formatOf(deb).name());
    final byte[] ar = "!<arch>\nsomething else".getBytes(StandardCharsets.US_ASCII);
    assertEquals("ar", KnownMagics.formatOf(ar).name());
  }

  /** Binary DXF uses control bytes after the ASCII sentinel. */
  @Test
  void testBinaryDxfSentinel() {
    final byte[] header = "AutoCAD Binary DXF\r\n\u001a\u0000"
        .getBytes(StandardCharsets.US_ASCII);
    assertEquals(22, header.length);
    assertEquals("dxf", KnownMagics.formatOf(header).name());
    final byte[] content = Arrays.copyOf(header, 30);
    final String encoded = Base64.getEncoder().encodeToString(content);
    final EmbeddedAsset asset = new CursorAssetDetector().detect(encoded).get(0);
    assertEquals("dxf", asset.format());
    assertEquals("image/vnd.dxf", asset.mediaType());
    assertEquals(content.length, asset.decode(encoded).length);
  }

  /**
   * Encodes a DXB point at (10, 20).
   *
   * @see <a href="https://forums.autodesk.com/autodesk/attachments/autodesk/autocad-lt-forum-en/25135/1/DXF%20Reference.pdf">
   *     Autodesk DXF reference, DXB file format</a>
   */
  @Test
  void testDxbPointDrawing() {
    final byte[] header = DXB_HEADER.getBytes(StandardCharsets.US_ASCII);
    assertEquals(19, header.length);
    assertEquals("dxb", KnownMagics.formatOf(header).name());
    final byte[] drawing = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
        .put(header).put((byte) 2).putShort((short) 10).putShort((short) 20)
        .put((byte) 0).array();
    final String encoded = Base64.getEncoder().encodeToString(drawing);
    final List<EmbeddedAsset> assets = new CursorAssetDetector().detect(encoded);
    assertEquals(1, assets.size());
    assertEquals("dxb", assets.get(0).format());
    assertEquals("image/vnd.dxb", assets.get(0).mediaType());
    assertArrayEquals(drawing, assets.get(0).decode(encoded));
  }

  /**
   * The complete DXB sentinel is required, including the final NUL.
   *
   * @param length The number of header bytes retained.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 14, 15, 16, 17, 18})
  void testTruncatedDxbSentinelIsRejected(int length) {
    assertNull(KnownMagics.formatOf(Arrays.copyOf(
        DXB_HEADER.getBytes(StandardCharsets.US_ASCII), length)));
  }

  /**
   * Textual hexadecimal notation and changed control bytes are not DXB headers.
   *
   * @param sentinel The incorrect header.
   */
  @ParameterizedTest
  @ValueSource(strings = {"AutoCAD DXB 1.0\r\n0x1A00", "AutoCAD DXB 1.0\r\n^Z\u0000",
      "AutoCAD DXB 1.0\n\r\u001a\u0000", "AutoCAD DXB 1.0\r\n\u001a!"})
  void testInvalidDxbSentinelIsRejected(String sentinel) {
    final byte[] content = Arrays.copyOf(sentinel.getBytes(StandardCharsets.US_ASCII), 30);
    assertNull(KnownMagics.formatOf(content));
    assertTrue(new CursorAssetDetector().detect(
        Base64.getEncoder().encodeToString(content)).isEmpty());
  }

  /**
   * OpenEXR storage flags do not change the format or establish ACES conformance.
   *
   * @param flags The storage flags in the version field.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 0x200, 0x400, 0x600, 0x800, 0xc00, 0x1000, 0x1400, 0x1800, 0x1c00})
  void testOpenExrHeaderFlags(int flags) {
    final byte[] header = openExrHeader(flags);
    assertEquals("exr", KnownMagics.formatOf(header).name());
    final String encoded = Base64.getEncoder().encodeToString(header);
    final List<EmbeddedAsset> assets = new CursorAssetDetector().detect(encoded);
    assertEquals(1, assets.size());
    assertEquals("exr", assets.get(0).format());
    assertEquals("image/x-exr", assets.get(0).mediaType());
    assertArrayEquals(header, assets.get(0).decode(encoded));
  }

  /** A URI's media type is retained, not inferred from the OpenEXR magic. */
  @Test
  void testDeclaredAcesTypeIsPreserved() {
    final byte[] header = openExrHeader(0);
    final String uri = "data:image/aces;base64," + Base64.getEncoder().encodeToString(header);
    final List<EmbeddedAsset> assets = new CursorAssetDetector().detect(uri);
    assertEquals(1, assets.size());
    assertEquals("exr", assets.get(0).format());
    assertEquals("image/aces", assets.get(0).mediaType());
    assertArrayEquals(header, assets.get(0).decode(uri));
  }

  /**
   * OpenEXR's magic is little-endian and must be present in full.
   *
   * @param length The number of bytes retained from the 4-byte magic.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void testTruncatedOpenExrMagicIsRejected(int length) {
    assertNull(KnownMagics.formatOf(Arrays.copyOf(openExrHeader(0), length)));
  }

  /** An OpenEXR magic number stored in big-endian order is not recognized. */
  @Test
  void testBigEndianOpenExrMagicIsRejected() {
    final byte[] header = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        .putInt(20000630).putInt(2).array();
    assertNull(KnownMagics.formatOf(header));
    assertTrue(new CursorAssetDetector().detect(
        Base64.getEncoder().encodeToString(header)).isEmpty());
  }

  /** A Snappy identifier chunk followed by padding identifies a compressed stream. */
  @Test
  void testSnappyFramedStream() {
    final byte[] stream = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0x000006ff).put("sNaPpY".getBytes(StandardCharsets.US_ASCII))
        .putInt(0x000012fe).array();
    assertEquals("sz", KnownMagics.formatOf(stream).name());
    final String encoded = Base64.getEncoder().encodeToString(stream);
    final List<EmbeddedAsset> assets = new CursorAssetDetector().detect(encoded);
    assertEquals(1, assets.size());
    assertEquals("sz", assets.get(0).format());
    assertEquals("application/x-snappy-framed", assets.get(0).mediaType());
    assertArrayEquals(stream, assets.get(0).decode(encoded));
  }

  /** A Snappy identifier without the chunk header is not recognized. */
  @Test
  void testSnappyIdentifierWithoutChunkHeaderIsRejected() {
    final byte[] text = "sNaPpY is the identifier, not the complete chunk"
        .getBytes(StandardCharsets.US_ASCII);
    assertNull(KnownMagics.formatOf(text));
    assertTrue(new CursorAssetDetector().detect(
        Base64.getEncoder().encodeToString(text)).isEmpty());
  }

  /**
   * TIFF and BigTIFF header fixtures cover both byte orders without image data.
   *
   * @param littleEndian Whether the header uses little-endian values.
   * @param version The TIFF version number, 42 or 43.
   */
  @ParameterizedTest
  @CsvSource({"true, 42", "false, 42", "true, 43", "false, 43"})
  void testTiffByteOrders(boolean littleEndian, int version) {
    final ByteBuffer buffer = ByteBuffer.allocate(32).order(
        littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    final byte marker = (byte) (littleEndian ? 'I' : 'M');
    buffer.put(marker).put(marker).putShort((short) version);
    if (version == 43) {
      buffer.putShort((short) 8).putShort((short) 0).putLong(16);
    } else {
      buffer.putInt(8);
    }
    final byte[] header = buffer.array();
    assertEquals(EmbeddedAsset.FORMAT_TIFF, KnownMagics.formatOf(header).name());
    final String encoded = Base64.getEncoder().encodeToString(header);
    final List<EmbeddedAsset> assets = new CursorAssetDetector().detect(encoded);
    assertEquals(1, assets.size());
    assertEquals(EmbeddedAsset.FORMAT_TIFF, assets.get(0).format());
    assertEquals("image/tiff", assets.get(0).mediaType());
    assertArrayEquals(header, assets.get(0).decode(encoded));
  }

  /**
   * Builds a header fixture with no image body or ACES attributes.
   *
   * @param flags The storage flags in the version field.
   * @return A 32-byte fixture with the OpenEXR magic and version fields.
   */
  private byte[] openExrHeader(int flags) {
    return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(20000630).putInt(2 | flags).array();
  }

  /** XAR archives are not Xara vector graphics despite sharing an extension. */
  @Test
  void testXarArchiveMediaType() {
    final byte[] header = new byte[28];
    header[0] = 'x';
    header[1] = 'a';
    header[2] = 'r';
    header[3] = '!';
    header[5] = 28;
    header[7] = 1;
    final String encoded = Base64.getEncoder().encodeToString(header);
    final EmbeddedAsset asset = new CursorAssetDetector().detect(encoded).get(0);
    assertEquals("xar", asset.format());
    assertEquals("application/x-xar", asset.mediaType());
  }

  /** The XPM header identifies a pixmap, not an XBM bitmap. */
  @Test
  void testXpmFormatAndMediaType() {
    final String xpm = "/* XPM */\nstatic char *image[] = {\n"
        + "\"1 1 1 1\",\n\". c #000000\",\n\".\"};\n";
    final String encoded = Base64.getEncoder()
        .encodeToString(xpm.getBytes(StandardCharsets.US_ASCII));
    final EmbeddedAsset asset = new CursorAssetDetector().detect(encoded).get(0);
    assertEquals("xpm", asset.format());
    assertEquals("image/x-xpixmap", asset.mediaType());
  }

  /** A textual hexadecimal notation is not the binary DXF sentinel. */
  @Test
  void testTextualHexadecimalDxfSuffixIsRejected() {
    final byte[] header = "AutoCAD Binary DXF\r\n0x1A00"
        .getBytes(StandardCharsets.US_ASCII);
    assertNull(KnownMagics.formatOf(header));
  }

  /** @return The format constants and media types represented in the signature table. */
  static Stream<KnownMagics.Format> coreFormats() {
    return Stream.of(
        new KnownMagics.Format(EmbeddedAsset.FORMAT_PNG, "image/png"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_JPEG, "image/jpeg"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_GIF, "image/gif"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_PDF, "application/pdf"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_ZIP, "application/zip"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_TIFF, "image/tiff"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_GZIP, "application/gzip"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_SEVEN_ZIP, "application/x-7z-compressed"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_RAR, "application/vnd.rar"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_FLAC, "audio/flac"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_OGG, "application/ogg"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_MIDI, "audio/midi"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_SQLITE, "application/vnd.sqlite3"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_ELF, "application/x-elf"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_PE, "application/vnd.microsoft.portable-executable"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_CLASS, "application/java-vm"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_WOFF, "font/woff"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_WOFF2, "font/woff2"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_MP3, "audio/mpeg"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_OLE2, "application/x-ole-storage"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_ZSTD, "application/zstd"),
        new KnownMagics.Format(EmbeddedAsset.FORMAT_WASM, "application/wasm"));
  }

  /**
   * The signature table includes each non-RIFF format constant and media type.
   *
   * @param format The expected identifiers.
   */
  @ParameterizedTest
  @MethodSource("coreFormats")
  void testEmbeddedAssetConstantsResolve(KnownMagics.Format format) {
    assertTrue(KnownMagics.ENTRIES.stream().map(KnownMagics.Entry::format)
        .anyMatch(format::equals));
  }

  /**
   * The detector, not the signature table, resolves RIFF form types.
   *
   * @param format The RIFF format name.
   */
  @ParameterizedTest
  @ValueSource(strings = {EmbeddedAsset.FORMAT_WEBP, EmbeddedAsset.FORMAT_WAV, EmbeddedAsset.FORMAT_AVI})
  void testRiffFormatsAreNotTableEntries(String format) {
    assertTrue(KnownMagics.ENTRIES.stream()
        .noneMatch(entry -> format.equals(entry.format().name())));
  }

  /** Lookups return the shared identifiers of each matching table entry. */
  @Test
  void testMatchingFormatAndMediaTypeStayTogether() {
    for (final KnownMagics.Entry entry : KnownMagics.ENTRIES) {
      assertSame(entry.format(), KnownMagics.formatOf(entry.magic()));
    }
  }

  /** The table stays clear of prose: no magic under four bytes except the core set. */
  @Test
  void testShortMagicsAreLimitedToTheCoreSet() {
    for (final KnownMagics.Entry entry : KnownMagics.ENTRIES) {
      if (entry.magic().length < 4) {
        assertTrue(entry.format().name().equals(EmbeddedAsset.FORMAT_JPEG)
                || entry.format().name().equals(EmbeddedAsset.FORMAT_GZIP)
                || entry.format().name().equals(EmbeddedAsset.FORMAT_MP3),
            "unexpected short magic for " + entry.format().name());
      }
    }
  }
}
