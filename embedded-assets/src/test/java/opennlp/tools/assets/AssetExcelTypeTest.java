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
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests BIFF2-BIFF4 document types with header fixtures, not complete workbooks. */
public class AssetExcelTypeTest {

  private static final String DATA_URI = "data:;base64,";
  private static final String WORKSHEET_FORMAT = "xls";
  private static final String WORKSHEET_MEDIA_PREFIX = "application/vnd.ms-excel.sheet.";

  private final AssetDetector detector = new CursorAssetDetector();

  /**
   * Worksheet, chart and macro-sheet records use the worksheet media type.
   *
   * @param version The BIFF version.
   * @param type The document type in the BOF record.
   */
  @ParameterizedTest
  @CsvSource({"2,16", "2,32", "2,64", "3,16", "3,32", "3,64", "4,16", "4,32", "4,64"})
  void testWorksheetDocumentTypes(int version, int type) {
    assertFormat(header(version, type, 0), WORKSHEET_FORMAT, WORKSHEET_MEDIA_PREFIX + version);
  }

  /**
   * Workspace documents use a separate format and media type.
   *
   * @param version The BIFF version.
   * @param unused The contents of the unused BOF fields.
   */
  @ParameterizedTest
  @CsvSource({"3,0", "3,65535", "4,0", "4,65535"})
  void testWorkspaceDocumentTypes(int version, int unused) {
    assertFormat(header(version, 0x0100, unused), "xlw",
        "application/vnd.ms-excel.workspace." + version);
  }

  /**
   * Unused BOF fields do not restrict worksheet recognition.
   *
   * @param version The BIFF version.
   */
  @ParameterizedTest
  @ValueSource(ints = {2, 3, 4})
  void testUnusedFieldsAreIgnored(int version) {
    assertFormat(header(version, 0x0010, 0xbeef), WORKSHEET_FORMAT, WORKSHEET_MEDIA_PREFIX + version);
  }

  /**
   * A supported record prefix with an unsupported document type is not recognized.
   *
   * @param version The BIFF version.
   * @param type The unsupported document type.
   */
  @ParameterizedTest
  @CsvSource({"2,0", "2,5", "2,256", "2,65535", "3,0", "3,5", "3,6", "3,272",
      "3,65535", "4,0", "4,5", "4,6", "4,272", "4,65535"})
  void testUnsupportedDocumentTypesAreRejected(int version, int type) {
    final String encoded = Base64.getEncoder().encodeToString(header(version, type, 0));
    assertTrue(detector.detect(encoded).isEmpty());
    assertTrue(detector.detect(DATA_URI + encoded).isEmpty());
  }

  /**
   * An inferred type requires the complete BOF record.
   *
   * @param version The BIFF version.
   * @param length The number of bytes retained from the header fixture.
   */
  @ParameterizedTest
  @CsvSource({"2,0", "2,3", "2,4", "2,5", "2,6", "2,7",
      "3,4", "3,6", "3,7", "3,8", "3,9", "4,4", "4,7", "4,8", "4,9"})
  void testTruncatedBofIsRejected(int version, int length) {
    final byte[] truncated = Arrays.copyOf(header(version, 0x0010, 0), length);
    final String encoded = Base64.getEncoder().encodeToString(truncated);
    assertTrue(detector.detect(DATA_URI + encoded).isEmpty());
  }

  /**
   * A complete BOF record can identify a short data URI without a file body.
   *
   * @param version The BIFF version.
   */
  @ParameterizedTest
  @ValueSource(ints = {2, 3, 4})
  void testCompleteBofWithoutFileBody(int version) {
    final byte[] bytes = Arrays.copyOf(header(version, 0x0010, 0), version == 2 ? 8 : 10);
    final String text = DATA_URI + Base64.getEncoder().encodeToString(bytes);
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals(WORKSHEET_FORMAT, assets.get(0).format());
    assertEquals(WORKSHEET_MEDIA_PREFIX + version, assets.get(0).mediaType());
    assertArrayEquals(bytes, assets.get(0).decode(text));
  }

  /** An unrecognized header still permits a data URI with a supplied media type. */
  @Test
  void testSuppliedTypeRemainsAvailableForUnknownDocumentType() {
    final byte[] bytes = header(3, 0xffff, 0);
    final String text = "data:application/octet-stream;base64,"
        + Base64.getEncoder().encodeToString(bytes);
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals("octet-stream", assets.get(0).format());
    assertEquals("application/octet-stream", assets.get(0).mediaType());
    assertArrayEquals(bytes, assets.get(0).decode(text));
  }

  /**
   * Builds an original BOF record with trailing zeros for base64 detection.
   *
   * @param version The BIFF version, 2, 3 or 4.
   * @param type The document type.
   * @param unused The value for unused fields.
   * @return A 32-byte header fixture.
   * @throws IllegalArgumentException If the version is unsupported.
   * @see <a href="https://www.openoffice.org/sc/excelfileformat.pdf#page=135">
   *     OpenOffice Excel file format reference, BOF records</a>
   */
  private byte[] header(int version, int type, int unused) {
    final int record = switch (version) {
      case 2 -> 0x0009;
      case 3 -> 0x0209;
      case 4 -> 0x0409;
      default -> throw new IllegalArgumentException("version must be 2, 3 or 4");
    };
    return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        .putShort((short) record).putShort((short) (version == 2 ? 4 : 6))
        .putShort((short) unused).putShort((short) type).putShort((short) unused).array();
  }

  /**
   * Checks inferred identifiers and decoded bytes with both supported transports.
   *
   * @param bytes The header fixture.
   * @param format The expected format name.
   * @param mediaType The expected media type.
   */
  private void assertFormat(byte[] bytes, String format, String mediaType) {
    final String encoded = Base64.getEncoder().encodeToString(bytes);
    final List<EmbeddedAsset> payloadAssets = detector.detect(encoded);
    final List<EmbeddedAsset> uri = detector.detect(DATA_URI + encoded);
    assertEquals(1, payloadAssets.size());
    assertEquals(1, uri.size());
    assertEquals(format, payloadAssets.get(0).format());
    assertEquals(format, uri.get(0).format());
    assertEquals(mediaType, payloadAssets.get(0).mediaType());
    assertEquals(mediaType, uri.get(0).mediaType());
    assertArrayEquals(bytes, payloadAssets.get(0).decode(encoded));
    assertArrayEquals(bytes, uri.get(0).decode(DATA_URI + encoded));
  }
}
