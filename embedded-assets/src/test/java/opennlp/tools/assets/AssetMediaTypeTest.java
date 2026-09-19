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
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests media types for signatures that share a format name. */
public class AssetMediaTypeTest {

  private static final String DATA_URI = "data:;base64,";
  private static final String SUPPLIED_MEDIA_TYPE = "application/octet-stream;charset=binary";

  private final AssetDetector detector = new CursorAssetDetector();

  /**
   * Supplies synthetic Excel headers and PEM text with placeholder parameter bytes.
   * These inputs test recognition, not file or cryptographic validity.
   *
   * @return Inputs with shared format names and distinct media types.
   */
  static Stream<Arguments> mediaTypes() {
    return Stream.of(
        Arguments.of(Arrays.copyOf(HexFormat.of().parseHex("0900040000001000"), 32),
            "xls", "application/vnd.ms-excel.sheet.2"),
        Arguments.of(Arrays.copyOf(HexFormat.of().parseHex("0902060000001000"), 32),
            "xls", "application/vnd.ms-excel.sheet.3"),
        Arguments.of(Arrays.copyOf(HexFormat.of().parseHex("0904060000001000"), 32),
            "xls", "application/vnd.ms-excel.sheet.4"),
        Arguments.of(AssetTestSupport.pem("DSA PARAMETERS"),
            "pem-parameters", "application/x-x509-dsa-parameters"),
        Arguments.of(AssetTestSupport.pem("EC PARAMETERS"),
            "pem-parameters", "application/x-x509-ec-parameters"));
  }

  /** @return All entries for checking metadata lookup, not format validity. */
  static Stream<KnownMagics.Entry> signatures() {
    return KnownMagics.ENTRIES.stream();
  }

  /**
   * Each table entry provides both identifiers through base64 and data-URI detection.
   *
   * @param entry The signature and expected metadata.
   */
  @ParameterizedTest
  @MethodSource("signatures")
  void testEverySignatureRetainsItsMediaType(KnownMagics.Entry entry) {
    final boolean emf = "emf".equals(entry.format().name());
    final byte[] bytes = "svg".equals(entry.format().name())
        ? AssetTestSupport.svg() : Arrays.copyOf(entry.magic(), emf ? 44 : 32);
    if (emf) {
      // EMR_HEADER requires the additional signature at byte 40.
      System.arraycopy(" EMF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 40, 4);
    }
    if ("xls".equals(entry.format().name())) {
      // Excel signatures also require a supported document type: 0x0010 is a worksheet.
      bytes[6] = 0x10;
    }
    if ("pcapng".equals(entry.format().name())) {
      // The block type also requires byte-order magic at byte 8.
      ByteBuffer.wrap(bytes).putInt(8, 0x1a2b3c4d);
    }
    if ("aiff".equals(entry.format().name())) {
      // FORM requires an AIFF or AIFC type at byte 8.
      ByteBuffer.wrap(bytes).putInt(8, 0x41494646);
    }
    if ("jp2".equals(entry.format().name())) {
      // The shared signature requires an ftyp box with the JP2 brand.
      ByteBuffer.wrap(bytes).putInt(12, 20).putInt(16, 0x66747970)
          .putInt(20, 0x6a703220).putInt(28, 0x6a703220);
    }
    if ("dex".equals(entry.format().name())) {
      final byte[] magic = AssetTestSupport.dex("035", 8);
      System.arraycopy(magic, 0, bytes, 0, magic.length);
    }
    if ("vtt".equals(entry.format().name())) {
      bytes[entry.magic().length] = '\n';
    }
    if ("dwf".equals(entry.format().name())) {
      System.arraycopy("00.55)".getBytes(StandardCharsets.US_ASCII), 0, bytes, 6, 6);
    }
    if ("ics".equals(entry.format().name())) {
      bytes[entry.magic().length] = '\r';
      bytes[entry.magic().length + 1] = '\n';
    }
    final String encoded = Base64.getEncoder().encodeToString(bytes);
    assertAsset(encoded, bytes, entry.format().name(), entry.format().mediaType());
    assertAsset(DATA_URI + encoded, bytes, entry.format().name(), entry.format().mediaType());
  }

  /**
   * A base64 payload uses the media type of the matching signature.
   *
   * @param bytes The header fixture or encoded PEM text.
   * @param format The expected format name.
   * @param mediaType The media type of the matching signature.
   */
  @ParameterizedTest(name = "{1}: {2}")
  @MethodSource("mediaTypes")
  void testBarePayloadMediaType(byte[] bytes, String format, String mediaType) {
    final String prefix = "Payload: [";
    final String encoded = Base64.getEncoder().encodeToString(bytes);
    final String text = prefix + encoded + "]";
    final EmbeddedAsset asset = assertAsset(text, bytes, format, mediaType);
    final Span expected = new Span(prefix.length(), prefix.length() + encoded.length());
    assertEquals(expected, asset.span());
    assertEquals(expected, asset.payload());
  }

  /**
   * A data URI without a media type uses the matching signature.
   *
   * @param bytes The header fixture or encoded PEM text.
   * @param format The expected format name.
   * @param mediaType The media type of the matching signature.
   */
  @ParameterizedTest(name = "{1}: {2}")
  @MethodSource("mediaTypes")
  void testDataUriMediaTypeInference(byte[] bytes, String format, String mediaType) {
    final String text = DATA_URI + Base64.getEncoder().encodeToString(bytes);
    final EmbeddedAsset asset = assertAsset(text, bytes, format, mediaType);
    assertEquals(new Span(0, text.length()), asset.span());
    assertEquals(new Span(DATA_URI.length(), text.length()), asset.payload());
  }

  /**
   * A supplied media type takes precedence without changing the recognized format.
   *
   * @param bytes The header fixture or encoded PEM text.
   * @param format The expected format name.
   * @param mediaType The media type inferred without a supplied type.
   */
  @ParameterizedTest(name = "{1}: {2}")
  @MethodSource("mediaTypes")
  void testDataUriSuppliedTypeIsPreserved(byte[] bytes, String format, String mediaType) {
    assertNotEquals(SUPPLIED_MEDIA_TYPE, mediaType);
    final String text = "data:" + SUPPLIED_MEDIA_TYPE + ";base64,"
        + Base64.getEncoder().encodeToString(bytes);
    assertAsset(text, bytes, format, SUPPLIED_MEDIA_TYPE);
  }

  /**
   * RIFF headers use the form type at offset 8.
   *
   * @param form The RIFF form type.
   * @param format The expected format name.
   * @param mediaType The expected media type.
   */
  @ParameterizedTest
  @CsvSource(value = {"WEBP,webp,image/webp", "WAVE,wav,audio/wav", "'AVI ',avi,video/x-msvideo"})
  void testRiffMediaTypes(String form, String format, String mediaType) {
    final byte[] header = riffHeader(form);
    final String encoded = Base64.getEncoder().encodeToString(header);
    assertAsset(encoded, header, format, mediaType);
    assertAsset(DATA_URI + encoded, header, format, mediaType);
  }

  /**
   * Unknown or incomplete RIFF form types do not produce findings.
   *
   * @param form The unrecognized form field.
   */
  @ParameterizedTest
  @ValueSource(strings = {"NONE", "WEB", "WAV", "AVI", ""})
  void testUnknownRiffTypeIsRejected(String form) {
    final byte[] header = riffHeader(form);
    final String encoded = Base64.getEncoder().encodeToString(header);
    assertTrue(detector.detect(encoded).isEmpty());
    assertTrue(detector.detect(DATA_URI + encoded).isEmpty());
  }

  /**
   * Builds a RIFF header fixture without a file body.
   *
   * @param form The form type to put at offset 8.
   * @return A 32-byte header fixture.
   */
  private byte[] riffHeader(String form) {
    return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(24)
        .put(form.getBytes(StandardCharsets.US_ASCII)).array();
  }

  /**
   * Checks one asset and verifies that decoding returns the complete input.
   *
   * @param text The text to inspect.
   * @param bytes The expected decoded bytes.
   * @param format The expected format name.
   * @param mediaType The expected media type.
   * @return The detected asset.
   */
  private EmbeddedAsset assertAsset(String text, byte[] bytes, String format, String mediaType) {
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    final EmbeddedAsset asset = assets.get(0);
    assertEquals(format, asset.format());
    assertEquals(mediaType, asset.mediaType());
    assertEquals(bytes.length, asset.decodedLength());
    assertArrayEquals(bytes, asset.decode(text));
    return asset;
  }
}
