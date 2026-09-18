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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static opennlp.tools.assets.AssetTestSupport.gif;
import static opennlp.tools.assets.AssetTestSupport.png;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests format identification and source spans using generated header fixtures.
 */
public class CursorAssetDetectorTest {

  private final CursorAssetDetector detector = new CursorAssetDetector();

  /**
   * Builds a RIFF prefix.
   *
   * @param kind The four-character form type, for example {@code WEBP}.
   * @return The leading bytes of a RIFF file.
   */
  private byte[] riff(String kind) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {'R', 'I', 'F', 'F', 100, 0, 0, 0});
    out.writeBytes(kind.getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(new byte[20]);
    return out.toByteArray();
  }

  /**
   * Pads bytes to at least the length the detector demands of a bare run once
   * encoded.
   *
   * @param prefix The leading bytes.
   * @return The bytes padded to 30, so the base64 form exceeds the bare minimum.
   */
  private byte[] padded(byte[] prefix) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(prefix);
    if (out.size() < 30) {
      out.writeBytes(new byte[30 - out.size()]);
    }
    return out.toByteArray();
  }

  /**
   * Surrounds a run with plain words, so it has a text boundary on either side.
   *
   * @param run The run to embed.
   * @return The run with surrounding text.
   */
  private String embed(String run) {
    return "x " + run + " y";
  }

  /**
   * Encodes bytes with the requested RFC line width and CRLF separators.
   *
   * @param bytes The bytes to encode.
   * @param width The line width, a multiple of four.
   * @return The wrapped base64 text.
   */
  private String wrapped(byte[] bytes, int width) {
    return Base64.getMimeEncoder(width, "\r\n".getBytes(StandardCharsets.US_ASCII))
        .encodeToString(bytes);
  }

  /**
   * The standard and widely deployed PEM labels supported by the detector.
   *
   * @return Label, format, and media type triples.
   */
  private static Stream<Arguments> pemTypes() {
    return Stream.of(
        Arguments.of("CERTIFICATE", "pem-cert", "application/x-x509-cert"),
        Arguments.of("X509 CRL", "pem-crl", "application/pkix-crl"),
        Arguments.of("CERTIFICATE REQUEST", "pem-request", "application/pkcs10"),
        Arguments.of("PKCS7", "pem-pkcs7", "application/pkcs7-signature"),
        Arguments.of("CMS", "pem-cms", "application/pkcs7-mime"),
        Arguments.of("PRIVATE KEY", "pem-key", "application/x-x509-key"),
        Arguments.of("ENCRYPTED PRIVATE KEY", "pem-key", "application/x-x509-key"),
        Arguments.of("PUBLIC KEY", "pem-key", "application/x-x509-key"),
        Arguments.of("ATTRIBUTE CERTIFICATE", "pem-attribute-cert",
            "application/x-x509-attribute-cert"),
        Arguments.of("EC PARAMETERS", "pem-parameters",
            "application/x-x509-ec-parameters"));
  }

  @Test
  void testBarePngRunWithDimensions() {
    final byte[] bytes = png(640, 480);
    final String encoded = Base64.getEncoder().encodeToString(bytes);
    final String text = "before " + encoded + " after";
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    final EmbeddedAsset asset = assets.get(0);
    assertEquals(EmbeddedAsset.FORMAT_PNG, asset.format());
    assertEquals("image/png", asset.mediaType());
    assertEquals(640, asset.width());
    assertEquals(480, asset.height());
    assertEquals(bytes.length, asset.decodedLength());
    assertEquals(encoded, asset.span().getCoveredText(text).toString());
    assertArrayEquals(bytes, asset.decode(text));
  }

  /** A MIME payload wrapped at 76 columns remains one exact, decodable asset span. */
  @Test
  void testMimeWrappedBarePayload() {
    final ByteArrayOutputStream content = new ByteArrayOutputStream();
    content.writeBytes(png(640, 480));
    content.writeBytes(new byte[128]);
    final byte[] bytes = content.toByteArray();
    final String encoded = wrapped(bytes, 76);
    final String text = embed(encoded);

    final List<EmbeddedAsset> assets = detector.detect(text);

    assertEquals(1, assets.size());
    final EmbeddedAsset asset = assets.get(0);
    assertEquals(EmbeddedAsset.FORMAT_PNG, asset.format());
    assertEquals(encoded, asset.span().getCoveredText(text).toString());
    assertEquals(encoded, asset.payload().getCoveredText(text).toString());
    assertEquals(bytes.length, asset.decodedLength());
    assertArrayEquals(bytes, asset.decode(text));
  }

  /** A base64url payload uses the URL alphabet without padding and still decodes. */
  @Test
  void testBareBase64UrlPayload() {
    final ByteArrayOutputStream content = new ByteArrayOutputStream();
    content.writeBytes(png(8, 9));
    content.writeBytes(new byte[] {(byte) 0xFB, (byte) 0xFF, (byte) 0xFF});
    final byte[] bytes = content.toByteArray();
    final String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    assertTrue(encoded.indexOf('-') >= 0 || encoded.indexOf('_') >= 0);

    final List<EmbeddedAsset> assets = detector.detect(embed(encoded));

    assertEquals(1, assets.size());
    assertEquals(EmbeddedAsset.FORMAT_PNG, assets.get(0).format());
    assertArrayEquals(bytes, assets.get(0).decode(embed(encoded)));
  }

  /** An RFC 7468 certificate envelope reports the envelope and decodes its body. */
  @Test
  void testPemCertificateEnvelope() {
    final byte[] certificate = new byte[160];
    certificate[0] = 0x30;
    certificate[1] = (byte) 0x82;
    certificate[2] = 0x01;
    certificate[3] = 0x20;
    final String body = wrapped(certificate, 64);
    final String pem = "-----BEGIN CERTIFICATE-----\r\n" + body
        + "\r\n-----END CERTIFICATE-----";
    final String text = "certificate:\n" + pem + "\nend";

    final List<EmbeddedAsset> assets = detector.detect(text);

    assertEquals(1, assets.size());
    final EmbeddedAsset asset = assets.get(0);
    assertEquals("pem-cert", asset.format());
    assertEquals("application/x-x509-cert", asset.mediaType());
    assertEquals(pem, asset.span().getCoveredText(text).toString());
    assertEquals(body, asset.payload().getCoveredText(text).toString());
    assertEquals(certificate.length, asset.decodedLength());
    assertArrayEquals(certificate, asset.decode(text));
  }

  /**
   * Every supported PEM family maps its verified boundary label to stable metadata.
   *
   * @param label The PEM label.
   * @param format The expected format tag.
   * @param mediaType The expected media type.
   */
  @ParameterizedTest
  @MethodSource("pemTypes")
  void testPemTypeMappings(String label, String format, String mediaType) {
    final byte[] content = new byte[96];
    final String body = wrapped(content, 64);
    final String pem = "-----BEGIN " + label + "-----\n" + body.replace("\r\n", "\n")
        + "\n-----END " + label + "-----";

    final List<EmbeddedAsset> assets = detector.detect(pem);

    assertEquals(1, assets.size());
    assertEquals(format, assets.get(0).format());
    assertEquals(mediaType, assets.get(0).mediaType());
    assertArrayEquals(content, assets.get(0).decode(pem));
  }

  /** A compact JWT reports its full token and exposes the claims segment as payload. */
  @Test
  void testJsonWebToken() {
    final byte[] header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}"
        .getBytes(StandardCharsets.UTF_8);
    final byte[] claims = "{\"sub\":\"1234567890\",\"admin\":true}"
        .getBytes(StandardCharsets.UTF_8);
    final String encodedHeader = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(header);
    final String encodedClaims = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(claims);
    final String signature = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(new byte[32]);
    final String token = encodedHeader + "." + encodedClaims + "." + signature;
    final String text = "token=" + token + ";";

    final List<EmbeddedAsset> assets = detector.detect(text);

    assertEquals(1, assets.size());
    final EmbeddedAsset asset = assets.get(0);
    assertEquals("jwt", asset.format());
    assertEquals("application/jwt", asset.mediaType());
    assertEquals(token, asset.span().getCoveredText(text).toString());
    assertEquals(encodedClaims, asset.payload().getCoveredText(text).toString());
    assertEquals(claims.length, asset.decodedLength());
    assertArrayEquals(claims, asset.decode(text));
  }

  /** RFC 7519 unsecured JWTs use {@code alg=none} and an empty signature segment. */
  @Test
  void testUnsecuredJsonWebToken() {
    final String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
    final String claims = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"sub\":\"123\"}".getBytes(StandardCharsets.UTF_8));
    final String token = header + "." + claims + ".";

    final List<EmbeddedAsset> assets = detector.detect(token);

    assertEquals(1, assets.size());
    assertEquals(token, assets.get(0).span().getCoveredText(token).toString());
    assertEquals(EmbeddedAsset.FORMAT_JWT, assets.get(0).format());
  }

  /** JWT lookalikes fail closed unless header, claims, signature, and boundaries agree. */
  @Test
  void testJsonWebTokenNearMisses() {
    final String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    final String validHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
    final String claims = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"sub\":\"123\"}".getBytes(StandardCharsets.UTF_8));
    final String notJson = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "plain text".getBytes(StandardCharsets.UTF_8));
    final String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(
        new byte[32]);
    final String noneHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
    final String malformedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"alg\":\"HS256\",}".getBytes(StandardCharsets.UTF_8));
    final String emptyAlgorithm = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"alg\":\"\"}".getBytes(StandardCharsets.UTF_8));

    assertTrue(detector.detect(header + "." + claims + "." + signature).isEmpty());
    assertTrue(detector.detect(validHeader + "." + notJson + "." + signature).isEmpty());
    assertTrue(detector.detect(validHeader + "." + claims).isEmpty());
    assertTrue(detector.detect(validHeader + "." + claims + ".").isEmpty());
    assertTrue(detector.detect(noneHeader + "." + claims + "." + signature).isEmpty());
    assertTrue(detector.detect(malformedHeader + "." + claims + "." + signature).isEmpty());
    assertTrue(detector.detect(emptyAlgorithm + "." + claims + "." + signature).isEmpty());
    assertTrue(detector.detect("x" + validHeader + "." + claims + "." + signature).isEmpty());
  }

  /** PEM envelopes fail closed on mismatched labels and malformed body characters. */
  @Test
  void testPemEnvelopeNearMisses() {
    final String body = wrapped(new byte[96], 64);
    assertTrue(detector.detect("-----BEGIN CERTIFICATE-----\r\n" + body
        + "\r\n-----END PUBLIC KEY-----").isEmpty());
    assertTrue(detector.detect("-----BEGIN CERTIFICATE-----\r\n" + body + "!"
        + "\r\n-----END CERTIFICATE-----").isEmpty());
  }

  @Test
  void testDataUriDeclaresTheMediaType() {
    final byte[] bytes = png(2, 3);
    final String uri = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
    final String text = "<img src=\"" + uri + "\">";
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    final EmbeddedAsset asset = assets.get(0);
    assertEquals(EmbeddedAsset.FORMAT_PNG, asset.format());
    assertEquals("image/png", asset.mediaType());
    assertEquals(uri, asset.span().getCoveredText(text).toString());
    assertArrayEquals(bytes, asset.decode(text));
  }

  @Test
  void testDataUriWithUnknownHeaderKeepsTheDeclaredFormat() {
    final byte[] bytes = "just some plain bytes padded out".getBytes(
        StandardCharsets.US_ASCII);
    final String uri = "data:text/plain;base64," + Base64.getEncoder().encodeToString(bytes);
    final List<EmbeddedAsset> assets = detector.detect(embed(uri));
    assertEquals(1, assets.size());
    assertEquals("plain", assets.get(0).format());
    assertEquals("text/plain", assets.get(0).mediaType());
  }

  @Test
  void testGifJpegRiffPdfZipAllIdentify() {
    final String gif = Base64.getEncoder().encodeToString(padded(gif(12, 34)));
    final String jpeg = Base64.getEncoder().encodeToString(
        padded(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
    final String webp = Base64.getEncoder().encodeToString(riff("WEBP"));
    final String wave = Base64.getEncoder().encodeToString(riff("WAVE"));
    final String avi = Base64.getEncoder().encodeToString(riff("AVI "));
    final String pdf = Base64.getEncoder().encodeToString(
        padded("%PDF-1.7 stub content".getBytes(
            StandardCharsets.US_ASCII)));
    final String zip = Base64.getEncoder().encodeToString(
        padded(new byte[] {'P', 'K', 3, 4}));
    final String text = String.join("\n", gif, jpeg, webp, wave, avi, pdf, zip);
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(7, assets.size());
    assertEquals(EmbeddedAsset.FORMAT_GIF, assets.get(0).format());
    assertEquals(12, assets.get(0).width());
    assertEquals(34, assets.get(0).height());
    assertEquals(EmbeddedAsset.FORMAT_JPEG, assets.get(1).format());
    assertEquals(-1, assets.get(1).width());
    assertEquals(EmbeddedAsset.FORMAT_WEBP, assets.get(2).format());
    assertEquals(EmbeddedAsset.FORMAT_WAV, assets.get(3).format());
    assertEquals(EmbeddedAsset.FORMAT_AVI, assets.get(4).format());
    assertEquals("video/x-msvideo", assets.get(4).mediaType());
    assertEquals(EmbeddedAsset.FORMAT_PDF, assets.get(5).format());
    assertEquals(EmbeddedAsset.FORMAT_ZIP, assets.get(6).format());
  }

  /**
   * The header bytes of every remaining recognized format, each the format's magic as
   * its specification publishes it.
   *
   * @return One case per format: the expected format tag, media type, and header.
   */
  private static Stream<Arguments> knownFormatHeaders() {
    final ByteArrayOutputStream sqlite = new ByteArrayOutputStream();
    sqlite.writeBytes("SQLite format 3".getBytes(StandardCharsets.US_ASCII));
    sqlite.write(0);
    return Stream.of(
        Arguments.of(EmbeddedAsset.FORMAT_TIFF, "image/tiff",
            new byte[] {'I', 'I', 0x2A, 0x00}),
        Arguments.of(EmbeddedAsset.FORMAT_TIFF, "image/tiff",
            new byte[] {'M', 'M', 0x00, 0x2A}),
        Arguments.of(EmbeddedAsset.FORMAT_GZIP, "application/gzip",
            new byte[] {0x1F, (byte) 0x8B, 0x08}),
        Arguments.of(EmbeddedAsset.FORMAT_SEVEN_ZIP, "application/x-7z-compressed",
            new byte[] {'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C}),
        Arguments.of(EmbeddedAsset.FORMAT_RAR, "application/vnd.rar",
            new byte[] {'R', 'a', 'r', '!', 0x1A, 0x07, 0x00}),
        Arguments.of(EmbeddedAsset.FORMAT_FLAC, "audio/flac",
            new byte[] {'f', 'L', 'a', 'C'}),
        Arguments.of(EmbeddedAsset.FORMAT_OGG, "application/ogg",
            new byte[] {'O', 'g', 'g', 'S'}),
        Arguments.of(EmbeddedAsset.FORMAT_MIDI, "audio/midi",
            new byte[] {'M', 'T', 'h', 'd'}),
        Arguments.of(EmbeddedAsset.FORMAT_SQLITE, "application/vnd.sqlite3",
            sqlite.toByteArray()),
        Arguments.of(EmbeddedAsset.FORMAT_ELF, "application/x-elf",
            new byte[] {0x7F, 'E', 'L', 'F', 2, 1, 1}),
        Arguments.of(EmbeddedAsset.FORMAT_PE, "application/vnd.microsoft.portable-executable",
            new byte[] {'M', 'Z', (byte) 0x90, 0x00}),
        Arguments.of(EmbeddedAsset.FORMAT_CLASS, "application/java-vm",
            new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 0x37}),
        Arguments.of(EmbeddedAsset.FORMAT_WOFF, "font/woff",
            new byte[] {'w', 'O', 'F', 'F'}),
        Arguments.of(EmbeddedAsset.FORMAT_WOFF2, "font/woff2",
            new byte[] {'w', 'O', 'F', '2'}),
        Arguments.of(EmbeddedAsset.FORMAT_MP3, "audio/mpeg",
            new byte[] {'I', 'D', '3', 3, 0, 0}),
        Arguments.of(EmbeddedAsset.FORMAT_OLE2, "application/x-ole-storage",
            new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}),
        Arguments.of(EmbeddedAsset.FORMAT_ZSTD, "application/zstd",
            new byte[] {0x28, (byte) 0xB5, 0x2F, (byte) 0xFD}),
        Arguments.of(EmbeddedAsset.FORMAT_WASM, "application/wasm",
            new byte[] {0, 'a', 's', 'm', 1, 0, 0, 0}),
        Arguments.of("rtf", "application/rtf",
            "{\\rtf1\\ansi minimal".getBytes(StandardCharsets.US_ASCII)),
        Arguments.of("xz", "application/x-xz",
            new byte[] {(byte) 0xFD, '7', 'z', 'X', 'Z', 0}),
        Arguments.of("matroska", "application/x-matroska",
            new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3}),
        Arguments.of("psd", "image/vnd.adobe.photoshop",
            new byte[] {'8', 'B', 'P', 'S', 0, 1}),
        Arguments.of("ico", "image/vnd.microsoft.icon",
            new byte[] {0, 0, 1, 0}),
        Arguments.of("macho", "application/x-mach-o",
            new byte[] {(byte) 0xFE, (byte) 0xED, (byte) 0xFA, (byte) 0xCE}),
        Arguments.of("pem-cert", "application/x-x509-cert",
            "-----BEGIN CERTIFICATE-----".getBytes(
                StandardCharsets.US_ASCII)),
        Arguments.of("torrent", "application/x-bittorrent",
            "d8:announce27:http".getBytes(StandardCharsets.US_ASCII)),
        Arguments.of("php", "text/x-php",
            "<?php echo 'shell';".getBytes(StandardCharsets.US_ASCII)),
        Arguments.of("parquet", "application/x-parquet",
            new byte[] {'P', 'A', 'R', '1'}));
  }

  @ParameterizedTest
  @MethodSource("knownFormatHeaders")
  void testExpandedMagicTableIdentifies(String format, String mediaType, byte[] header) {
    final String encoded = Base64.getEncoder().encodeToString(padded(header));
    final String text = embed(encoded);
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals(format, assets.get(0).format());
    assertEquals(mediaType, assets.get(0).mediaType());
    assertEquals(encoded, assets.get(0).span().getCoveredText(text).toString());
  }

  /**
   * A TIFF-looking run whose fourth byte breaks the magic: the base64 prefix pins only
   * the first two bits of that byte, so the decoded header check must reject it.
   */
  @Test
  void testTiffPrefixWithBrokenMagicByteIsIgnored() {
    final String lookalike = Base64.getEncoder().encodeToString(
        padded(new byte[] {'I', 'I', 0x2A, 0x01}));
    assertTrue(lookalike.startsWith("SUkqA"));
    assertEquals(List.of(), detector.detect(embed(lookalike)));
  }

  /**
   * A PE-looking run whose fourth byte breaks the DOS-stub magic: the prefix matches,
   * the decoded header check rejects.
   */
  @Test
  void testPePrefixWithBrokenStubByteIsIgnored() {
    final String lookalike = Base64.getEncoder().encodeToString(
        padded(new byte[] {'M', 'Z', (byte) 0x90, 0x01}));
    assertTrue(lookalike.startsWith("TVqQA"));
    assertEquals(List.of(), detector.detect(embed(lookalike)));
  }

  /** A short base64-looking token, such as a URL path segment, is never an asset. */
  @Test
  void testShortRunsAreIgnored() {
    assertEquals(List.of(), detector.detect("GET /9j/abc/thumbnail HTTP/1.1"));
  }

  /** A long run without a known magic prefix is not an asset. */
  @Test
  void testLongRunWithoutMagicIsIgnored() {
    final String encoded = Base64.getEncoder().encodeToString(
        "this is just text encoded as base64 for transport".getBytes(
            StandardCharsets.US_ASCII));
    assertEquals(List.of(), detector.detect("payload: " + encoded));
  }

  /** A magic prefix whose decoded header does not verify is not an asset. */
  @Test
  void testMagicPrefixWithoutVerifyingHeaderIsIgnored() {
    // A RIFF container carrying an unrecognized form type: the payload starts with the
    // RIFF prefix characters, but the header check rejects it.
    final String lookalike = Base64.getEncoder().encodeToString(riff("JUNK"));
    assertTrue(lookalike.startsWith("UklGR"));
    assertEquals(List.of(), detector.detect(embed(lookalike)));
  }

  /** An ordinary paragraph, with words of base64 characters, yields nothing. */
  @Test
  void testPlainProseYieldsNothing() {
    assertEquals(List.of(), detector.detect(
        "Plain prose with ordinary words does not contain payloads, "
            + "not even AlphanumericRunsLikeThisOne0123456789 of some length."));
  }

  /** A one-character base64 run cannot leave the scanner on the same cursor. */
  @Test
  void testSingleCharacterRunMakesProgress() {
    assertTimeoutPreemptively(Duration.ofSeconds(1),
        () -> assertEquals(List.of(), detector.detect("x")));
  }

  /** A truncated payload is trimmed to a decodable length instead of failing later. */
  @Test
  void testTruncatedPayloadStaysDecodable() {
    final byte[] bytes = padded(png(1, 1));
    final String encoded = Base64.getEncoder().withoutPadding().encodeToString(bytes);
    // Cut so the run length is 1 mod 4, the length no base64 payload can end at.
    final int cut = ((encoded.length() - 4) / 4) * 4 + 1;
    final String truncated = encoded.substring(0, cut);
    final List<EmbeddedAsset> assets = detector.detect(truncated);
    assertEquals(1, assets.size());
    assertEquals(cut - 1, assets.get(0).payload().getEnd());
    assertTrue(assets.get(0).decode(truncated).length > 0);
  }

  /**
   * A data URI declaring GIF but carrying a PNG payload: the sniffed format wins over
   * the declaration, while the media type stays as declared.
   */
  @Test
  void testDeclaredGifWithPngPayloadSniffsPng() {
    final byte[] bytes = png(9, 4);
    final String uri = "data:image/gif;base64," + Base64.getEncoder().encodeToString(bytes);
    final List<EmbeddedAsset> assets = detector.detect(embed(uri));
    assertEquals(1, assets.size());
    final EmbeddedAsset asset = assets.get(0);
    assertEquals(EmbeddedAsset.FORMAT_PNG, asset.format());
    assertEquals("image/gif", asset.mediaType());
    // The dimensions are read the sniffed format's way, from the PNG header.
    assertEquals(9, asset.width());
    assertEquals(4, asset.height());
  }

  /** A bare payload starting at the very first character of the text. */
  @Test
  void testPayloadStartingAtIndexZero() {
    final String encoded = Base64.getEncoder().encodeToString(png(1, 1));
    final String text = encoded + " trails";
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals(0, assets.get(0).span().getStart());
    assertEquals(encoded.length(), assets.get(0).span().getEnd());
  }

  /** A data URI whose payload runs to the very last character of the text. */
  @Test
  void testDataUriEndingAtTheEndOfTheText() {
    final String text = "logo: data:image/png;base64,"
        + Base64.getEncoder().encodeToString(png(1, 1));
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals(6, assets.get(0).span().getStart());
    assertEquals(text.length(), assets.get(0).span().getEnd());
  }

  /** The empty text yields the empty result, not an exception. */
  @Test
  void testEmptyTextYieldsNothing() {
    assertEquals(List.of(), detector.detect(""));
  }

  /**
   * Two data URIs back to back with no separator: the first payload's padding ends its
   * run, so both are detected with abutting spans.
   */
  @Test
  void testAdjacentDataUrisAreBothDetected() {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.writeBytes(png(1, 1));
    // One trailing byte makes the length 1 mod 3, so the base64 form ends in padding.
    bytes.write(0);
    final String uri = "data:image/png;base64,"
        + Base64.getEncoder().encodeToString(bytes.toByteArray());
    assertTrue(uri.endsWith("=="));
    final String text = uri + uri;
    final List<EmbeddedAsset> assets = detector.detect(text);
    assertEquals(2, assets.size());
    assertEquals(0, assets.get(0).span().getStart());
    assertEquals(uri.length(), assets.get(0).span().getEnd());
    assertEquals(uri.length(), assets.get(1).span().getStart());
    assertEquals(text.length(), assets.get(1).span().getEnd());
    assertArrayEquals(bytes.toByteArray(), assets.get(0).decode(text));
    assertArrayEquals(bytes.toByteArray(), assets.get(1).decode(text));
  }

  @Test
  void testRejectsNull() {
    assertThrows(IllegalArgumentException.class, () -> detector.detect(null));
  }
}
