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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import opennlp.tools.util.Span;

/**
 * Detects base64 content in {@code data:} URIs, bare payload runs,
 * <a href="https://www.rfc-editor.org/rfc/rfc7468.html">RFC 7468</a> PEM envelopes,
 * and <a href="https://www.rfc-editor.org/rfc/rfc7519.html">RFC 7519</a> compact JSON
 * Web Tokens.
 *
 * <p>Bare runs require at least 32 encoded characters and a recognized file signature
 * in the decoded header. Explicitly typed {@code data:} URIs accept non-empty payloads
 * of any length. Format detection uses the decoded header when recognized; otherwise,
 * the declared media subtype is used. File signatures identify possible formats, not
 * complete or safe files.</p>
 *
 * <p>Bare standard-base64 payloads may use the 76-column MIME or 64-column PEM line
 * width with CRLF or LF separators. The
 * <a href="https://www.rfc-editor.org/rfc/rfc4648.html#section-5">base64url</a>
 * alphabet is accepted for unwrapped payloads. PEM labels and end boundaries are
 * verified before an envelope is reported.
 * A compact JWT is reported only when it has three base64url segments, a UTF-8 JSON
 * object header with a non-empty {@code alg} member, and a UTF-8 JSON object claims
 * set. Signature encoding must be unpadded base64url; cryptographic signatures are
 * not verified.</p>
 *
 * <p>Malformed padding and mixed base64 alphabets are rejected. An unpadded bare run
 * of length 1 modulo 4 is shortened by one character to recover a decodable prefix.</p>
 *
 * <p>The detector is stateless and safe for concurrent use by multiple threads.</p>
 *
 * @since 3.0.0
 */
public final class CursorAssetDetector implements AssetDetector {

  /** Minimum encoded length for bare-payload detection. */
  private static final int MIN_BARE_PAYLOAD = 32;

  private static final int HEADER_ENCODED_LENGTH = 32;
  private static final int EMF_HEADER_ENCODED_LENGTH = 60;
  private static final int JPEG2000_HEADER_ENCODED_LENGTH = 48;
  private static final int PEM_HEADER_ENCODED_LENGTH = 48;

  /** Base64 prefix determined by the complete "-----BEGIN " text. */
  private static final String PEM_PREFIX = "LS0tLS1CRUdJTi";

  private static final int DEX_VERSION_OFFSET = 4;
  private static final int DEX_MAGIC_LENGTH = 8;

  private static final int DWF_VERSION_OFFSET = 6;
  private static final int DWF_DECIMAL_OFFSET = 8;
  private static final int DWF_HEADER_LENGTH = 12;

  private static final int WEBVTT_MAGIC_LENGTH = 6;
  private static final int UTF8_BOM_LENGTH = 3;

  private static final String CALENDAR_OPENING = "begin:vcalendar";
  private static final String CALENDAR_FORMAT_NAME = "ics";
  private static final KnownMagics.Format CALENDAR_FORMAT =
      new KnownMagics.Format(CALENDAR_FORMAT_NAME, "text/calendar");

  private final SvgHeader svgHeader = new SvgHeader();

  private static final int BER_SEQUENCE_TAG = 0x30;
  private static final int BER_OBJECT_IDENTIFIER_TAG = 0x06;
  private static final int BER_INDEFINITE_LENGTH = 0x80;

  /** Encoded content type 1.2.840.113549.1.9.16.1.31. */
  private static final byte[] TIMESTAMPED_DATA_OID = {
      0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x09, 0x10, 0x01, 0x1f};
  private static final KnownMagics.Format TIMESTAMPED_DATA_FORMAT =
      new KnownMagics.Format("tsd", "application/timestamped-data");

  /** Base64 encoding of the JPEG 2000 signature box. */
  private static final String JPEG2000_PREFIX = "AAAADGpQICANCocK";
  private static final int JPEG2000_BOX_OFFSET = 12;
  private static final int JPEG2000_BRAND_OFFSET = 20;
  private static final int JPEG2000_MIN_BOX_LENGTH = 16;

  private static final KnownMagics.Format JPX_FORMAT = new KnownMagics.Format("jpx", "image/jpx");
  private static final KnownMagics.Format JPM_FORMAT = new KnownMagics.Format("jpm", "image/jpm");
  private static final KnownMagics.Format MJ2_FORMAT = new KnownMagics.Format("mj2", "video/mj2");

  /** Base64 prefix of the little-endian EMR_HEADER record type (1). */
  private static final String EMF_PREFIX = "AQAAA";

  private static final int EMF_SIGNATURE_OFFSET = 40;
  private static final String EMF_SIGNATURE = " EMF";

  private static final int PCAPNG_BYTE_ORDER_OFFSET = 8;
  private static final int PCAPNG_BYTE_ORDER_MAGIC = 0x1a2b3c4d;
  private static final int PCAPNG_REVERSED_BYTE_ORDER_MAGIC =
      Integer.reverseBytes(PCAPNG_BYTE_ORDER_MAGIC);

  private static final String DATA_URI_SCHEME = "data:";
  private static final String BASE64_MARKER = ";base64,";
  private static final String JWT_MEDIA_TYPE = "application/jwt";
  private static final String JWT_ALGORITHM_MEMBER = "alg";
  private static final String JWT_UNSECURED_ALGORITHM = "none";
  private static final int MAX_JWT_SEGMENT_LENGTH = 16 * 1024;
  private static final String PEM_CERT_FORMAT = "pem-cert";
  private static final String PEM_REQUEST_FORMAT = "pem-request";
  private static final String PEM_KEY_FORMAT = "pem-key";
  private static final String PEM_PARAMETERS_FORMAT = "pem-parameters";
  private static final String X509_CERT_MEDIA_TYPE = "application/x-x509-cert";
  private static final String PKCS10_MEDIA_TYPE = "application/pkcs10";
  private static final String X509_KEY_MEDIA_TYPE = "application/x-x509-key";

  private static final List<PemType> PEM_TYPES = List.of(
      pemType("CERTIFICATE", PEM_CERT_FORMAT, X509_CERT_MEDIA_TYPE),
      pemType("X509 CERTIFICATE", PEM_CERT_FORMAT, X509_CERT_MEDIA_TYPE),
      pemType("X509 CRL", "pem-crl", "application/pkix-crl"),
      pemType("CERTIFICATE REQUEST", PEM_REQUEST_FORMAT, PKCS10_MEDIA_TYPE),
      pemType("NEW CERTIFICATE REQUEST", PEM_REQUEST_FORMAT, PKCS10_MEDIA_TYPE),
      pemType("PKCS7", "pem-pkcs7", "application/pkcs7-signature"),
      pemType("CMS", "pem-cms", "application/pkcs7-mime"),
      pemType("PRIVATE KEY", PEM_KEY_FORMAT, X509_KEY_MEDIA_TYPE),
      pemType("ENCRYPTED PRIVATE KEY", PEM_KEY_FORMAT, X509_KEY_MEDIA_TYPE),
      pemType("RSA PRIVATE KEY", PEM_KEY_FORMAT, X509_KEY_MEDIA_TYPE),
      pemType("DSA PRIVATE KEY", PEM_KEY_FORMAT, X509_KEY_MEDIA_TYPE),
      pemType("EC PRIVATE KEY", PEM_KEY_FORMAT, X509_KEY_MEDIA_TYPE),
      pemType("PUBLIC KEY", PEM_KEY_FORMAT, X509_KEY_MEDIA_TYPE),
      pemType("RSA PUBLIC KEY", PEM_KEY_FORMAT, X509_KEY_MEDIA_TYPE),
      pemType("ATTRIBUTE CERTIFICATE", "pem-attribute-cert",
          "application/x-x509-attribute-cert"),
      pemType("DSA PARAMETERS", PEM_PARAMETERS_FORMAT,
          "application/x-x509-dsa-parameters"),
      pemType("EC PARAMETERS", PEM_PARAMETERS_FORMAT,
          "application/x-x509-ec-parameters"),
      pemType("DH PARAMETERS", PEM_PARAMETERS_FORMAT,
          "application/x-x509-dh-parameters"));

  /**
   * The base64 image of the RIFF container magic. RIFF is recognized apart from the
   * {@link KnownMagics} table because its file type sits at offset eight, behind the
   * chunk size, and is resolved from the decoded header.
   */
  private static final String RIFF_PREFIX = "UklGR";

  /** The RIFF container identifier. */
  private static final String RIFF_MAGIC = "RIFF";

  /** Byte offset of the 4-character form type in RIFF and FORM headers. */
  private static final int FORM_TYPE_OFFSET = 8;

  private static final KnownMagics.Format WEBP_FORMAT =
      new KnownMagics.Format(EmbeddedAsset.FORMAT_WEBP, "image/webp");
  private static final KnownMagics.Format WAV_FORMAT =
      new KnownMagics.Format(EmbeddedAsset.FORMAT_WAV, "audio/wav");
  private static final KnownMagics.Format AVI_FORMAT =
      new KnownMagics.Format(EmbeddedAsset.FORMAT_AVI, "video/x-msvideo");

  private static final int BIFF_RECORD_HEADER_SIZE = 4;
  private static final int BIFF_DOCUMENT_TYPE_OFFSET = 6;
  private static final String EXCEL_WORKSPACE_FORMAT = "xlw";
  private static final KnownMagics.Format EXCEL_3_WORKSPACE =
      new KnownMagics.Format(EXCEL_WORKSPACE_FORMAT, "application/vnd.ms-excel.workspace.3");
  private static final KnownMagics.Format EXCEL_4_WORKSPACE =
      new KnownMagics.Format(EXCEL_WORKSPACE_FORMAT, "application/vnd.ms-excel.workspace.4");

  /**
   * {@inheritDoc}
   *
   * <p>The returned assets never overlap: the scan resumes behind the payload of the
   * asset it just reported.</p>
   */
  @Override
  public List<EmbeddedAsset> detect(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    final List<EmbeddedAsset> assets = new ArrayList<>();
    final int length = text.length();
    int i = 0;
    while (i < length) {
      final int uriEnd = tryDataUri(text, i, assets);
      if (uriEnd > i) {
        i = uriEnd;
        continue;
      }
      final int pemEnd = tryPem(text, i, assets);
      if (pemEnd > i) {
        i = pemEnd;
        continue;
      }
      final int jwtEnd = tryJwt(text, i, assets);
      if (jwtEnd > i) {
        i = jwtEnd;
        continue;
      }
      if (isAnyBase64Char(text.charAt(i))) {
        final Payload payload = payload(text, i, true);
        tryBareRun(text, payload, assets);
        i = Math.max(payload.end(), i + 1);
        continue;
      }
      i++;
    }
    return assets;
  }

  /**
   * Recognizes a {@code data:<mediatype>;base64,<payload>} URI starting at an index.
   *
   * @param text The text.
   * @param start The candidate start.
   * @param assets The list to add a finding to.
   * @return The index after the URI when one was recognized, or {@code start}.
   */
  private int tryDataUri(CharSequence text, int start, List<EmbeddedAsset> assets) {
    if (!matches(text, start, DATA_URI_SCHEME)) {
      return start;
    }
    int i = start + DATA_URI_SCHEME.length();
    final int mediaTypeStart = i;
    final int length = text.length();
    while (i < length && !matches(text, i, BASE64_MARKER)) {
      final char c = text.charAt(i);
      // A media type with optional parameters; anything else means this is not a
      // base64 data URI and the scheme text is left to ordinary scanning.
      if (c <= ' ' || c == '"' || c == '\'' || i - mediaTypeStart > 127) {
        return start;
      }
      i++;
    }
    if (i >= length) {
      return start;
    }
    final String declared = text.subSequence(mediaTypeStart, i).toString();
    final int payloadStart = i + BASE64_MARKER.length();
    final Payload payload = payload(text, payloadStart, false);
    if (payload.end() == payloadStart || payload.urlSafe()) {
      return start;
    }
    final byte[] header = decodeHeader(text, payload);
    if (header == null) {
      return start;
    }
    final KnownMagics.Format sniffed = formatOf(header, text, payload);
    final String inferredType = sniffed == null ? null : sniffed.mediaType();
    final String mediaType = declared.indexOf('/') > 0 ? declared : inferredType;
    if (mediaType == null) {
      return start;
    }
    assets.add(asset(start, payload.end(), payload,
        sniffed == null ? null : sniffed.name(), mediaType, header));
    return payload.end();
  }

  /**
   * Recognizes a bare payload run, demanding a known magic prefix and a verifying
   * decoded header.
   *
   * @param text The text.
   * @param payload The scanned payload.
   * @param assets The list to add a finding to.
   */
  private void tryBareRun(CharSequence text, Payload payload, List<EmbeddedAsset> assets) {
    if (payload.encodedLength() < MIN_BARE_PAYLOAD) {
      return;
    }
    boolean magic = matchesBase64Prefix(text, payload.start(), RIFF_PREFIX)
        || startsWithSequence(text, payload.start()) || startsWithCalendar(text, payload.start())
        || startsWithXml(text, payload.start());
    if (!magic) {
      for (final String prefix : KnownMagics.PREFIXES) {
        if (matchesBase64Prefix(text, payload.start(), prefix)) {
          magic = true;
          break;
        }
      }
    }
    if (!magic) {
      return;
    }
    final byte[] header = decodeHeader(text, payload);
    if (header == null) {
      return;
    }
    final KnownMagics.Format format = formatOf(header, text, payload);
    if (format == null) {
      return;
    }
    assets.add(asset(payload.start(), payload.end(), payload,
        format.name(), format.mediaType(), header));
  }

  /**
   * Recognizes one RFC 7468 textual envelope with a supported label.
   *
   * @param text The text being scanned.
   * @param start The candidate boundary marker start.
   * @param assets The list to add a finding to.
   * @return The end of the envelope, or {@code start} when none begins here.
   */
  private int tryPem(CharSequence text, int start, List<EmbeddedAsset> assets) {
    if (!lineBoundaryBefore(text, start)) {
      return start;
    }
    for (final PemType type : PEM_TYPES) {
      if (!matches(text, start, type.begin())) {
        continue;
      }
      final int bodyStart = lineBreakEnd(text, start + type.begin().length());
      if (bodyStart == start + type.begin().length()) {
        return start;
      }
      int i = bodyStart;
      int encodedLength = 0;
      int padding = 0;
      int payloadEnd = bodyStart;
      boolean sawBody = false;
      while (i < text.length()) {
        if (matches(text, i, type.end())) {
          final int spanEnd = i + type.end().length();
          if (!sawBody || !lineBoundaryAfter(text, spanEnd)
              || encodedLength % 4 != 0) {
            return start;
          }
          final Payload payload = new Payload(bodyStart, payloadEnd, encodedLength,
              padding, false, true);
          final byte[] header = decodeHeader(text, payload);
          if (header == null) {
            return start;
          }
          assets.add(asset(start, spanEnd, payload, type.format(),
              type.mediaType(), header));
          return spanEnd;
        }
        if (matches(text, i, "-----END ")) {
          return start;
        }
        final int lineEnd = lineEnd(text, i);
        final int lineLength = lineEnd - i;
        if (lineLength == 0 || lineLength > 64) {
          return start;
        }
        boolean paddedLine = false;
        for (int at = i; at < lineEnd; at++) {
          final char c = text.charAt(at);
          if (isBase64Char(c) && !paddedLine) {
            encodedLength++;
          } else if (c == '=' && padding < 2) {
            padding++;
            encodedLength++;
            paddedLine = true;
          } else {
            return start;
          }
        }
        sawBody = true;
        payloadEnd = lineEnd;
        final int next = lineBreakEnd(text, lineEnd);
        if (next == lineEnd || (paddedLine && !matches(text, next, type.end()))) {
          return start;
        }
        i = next;
      }
      return start;
    }
    return start;
  }

  /**
   * Recognizes a compact JSON Web Token and exposes its claims segment as payload.
   * This identifies structure only; it does not authenticate the signature.
   *
   * @param text The text being scanned.
   * @param start The candidate header-segment start.
   * @param assets The list to add a finding to.
   * @return The token end, or {@code start} when no JWT begins here.
   */
  private int tryJwt(CharSequence text, int start, List<EmbeddedAsset> assets) {
    if (!tokenBoundaryBefore(text, start)) {
      return start;
    }
    final int headerEnd = urlSegmentEnd(text, start);
    if (headerEnd == start || headerEnd >= text.length() || text.charAt(headerEnd) != '.') {
      return start;
    }
    final int claimsStart = headerEnd + 1;
    final int claimsEnd = urlSegmentEnd(text, claimsStart);
    if (claimsEnd == claimsStart || claimsEnd >= text.length()
        || text.charAt(claimsEnd) != '.') {
      return start;
    }
    final int signatureStart = claimsEnd + 1;
    final int tokenEnd = urlSegmentEnd(text, signatureStart);
    if (!tokenBoundaryAfter(text, tokenEnd) || (tokenEnd - signatureStart) % 4 == 1) {
      return start;
    }
    if (headerEnd - start > MAX_JWT_SEGMENT_LENGTH
        || claimsEnd - claimsStart > MAX_JWT_SEGMENT_LENGTH
        || tokenEnd - signatureStart > MAX_JWT_SEGMENT_LENGTH) {
      return start;
    }
    final byte[] header = decodeUrlSegment(text, start, headerEnd);
    final byte[] claims = decodeUrlSegment(text, claimsStart, claimsEnd);
    if (header == null || claims == null) {
      return start;
    }
    final String headerJson = utf8(header);
    final String claimsJson = utf8(claims);
    final JsonObjectParser.Result parsedHeader =
        JsonObjectParser.parse(headerJson, JWT_ALGORITHM_MEMBER);
    if (!parsedHeader.valid() || !JsonObjectParser.parse(claimsJson, null).valid()) {
      return start;
    }
    final String algorithm = parsedHeader.member();
    if (algorithm == null || algorithm.isEmpty()
        || (JWT_UNSECURED_ALGORITHM.equals(algorithm) != (tokenEnd == signatureStart))) {
      return start;
    }
    final Payload payload = new Payload(claimsStart, claimsEnd,
        claimsEnd - claimsStart, 0, true, true);
    assets.add(asset(start, tokenEnd, payload, EmbeddedAsset.FORMAT_JWT,
        JWT_MEDIA_TYPE, claims));
    return tokenEnd;
  }

  /**
   * Reads one unpadded base64url segment.
   *
   * @param text The source text.
   * @param start The segment start.
   * @return The exclusive segment end.
   */
  private int urlSegmentEnd(CharSequence text, int start) {
    int i = start;
    final int limit = Math.min(text.length(), start + MAX_JWT_SEGMENT_LENGTH + 1);
    while (i < limit && isBase64UrlChar(text.charAt(i))) {
      i++;
    }
    return i;
  }

  /**
   * Decodes a bounded base64url segment.
   *
   * @param text The source text.
   * @param start The segment start.
   * @param end The segment end.
   * @return The bytes, or {@code null} for an impossible encoded length.
   */
  private byte[] decodeUrlSegment(CharSequence text, int start, int end) {
    if ((end - start) % 4 == 1) {
      return null;
    }
    try {
      return Base64.getUrlDecoder().decode(text.subSequence(start, end).toString());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Decodes UTF-8 without accepting replacement characters.
   *
   * @param bytes The bytes to decode.
   * @return The decoded text, or {@code null} when malformed.
   */
  private String utf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      return null;
    }
  }

  /**
   * Builds the asset record: spans, dimensions from the header where the format
   * carries them, and the decoded length from the payload length.
   *
   * @param spanStart The asset start, at the URI scheme or the payload.
   * @param spanEnd The exclusive end of the complete asset.
   * @param payload The encoded payload and its accounting.
   * @param format The detected format, or {@code null} when only the media type
   *               identifies the format.
   * @param mediaType The media type.
   * @param header The decoded leading bytes.
   * @return The asset. Never {@code null}.
   */
  private EmbeddedAsset asset(int spanStart, int spanEnd,
      Payload payload, String format, String mediaType, byte[] header) {
    final long decodedLength = decodedLength(payload.encodedLength(), payload.padding());
    int width = -1;
    int height = -1;
    if (EmbeddedAsset.FORMAT_PNG.equals(format) && header.length >= 24
        && readInt(header, 8) == 13 && carries(header, 12, "IHDR")) {
      width = readInt(header, 16);
      height = readInt(header, 20);
    } else if (EmbeddedAsset.FORMAT_GIF.equals(format) && header.length >= 10) {
      width = readUnsignedShortLE(header, 6);
      height = readUnsignedShortLE(header, 8);
    }
    if (width <= 0 || height <= 0) {
      width = -1;
      height = -1;
    }
    return new EmbeddedAsset(new Span(spanStart, spanEnd),
        new Span(payload.start(), payload.end()),
        format != null ? format : subtype(mediaType), mediaType, decodedLength, width, height);
  }

  /**
   * Scans one base64 or base64url payload. Standard-base64 runs may cross CRLF or LF
   * when every continued line uses the MIME width of 76 or PEM width of 64.
   *
   * @param text The text.
   * @param start The run start.
   * @param allowWrap Whether conventional standard-base64 line wrapping is allowed.
   * @return The payload accounting. Never {@code null}.
   */
  private Payload payload(CharSequence text, int start, boolean allowWrap) {
    final int length = text.length();
    int i = start;
    int encodedLength = 0;
    int lineLength = 0;
    int wrapWidth = 0;
    int padding = 0;
    boolean urlSafe = false;
    boolean standardOnly = false;
    while (i < length) {
      final char c = text.charAt(i);
      if (isAnyBase64Char(c) && padding == 0) {
        urlSafe |= c == '-' || c == '_';
        standardOnly |= c == '+' || c == '/';
        encodedLength++;
        lineLength++;
        i++;
        continue;
      }
      if (c == '=' && padding < 2) {
        padding++;
        encodedLength++;
        lineLength++;
        i++;
        continue;
      }
      final int next = allowWrap && !urlSafe && padding == 0
          ? lineBreakEnd(text, i) : i;
      if (next > i && (lineLength == 64 || lineLength == 76)
          && (wrapWidth == 0 || wrapWidth == lineLength)
          && next < length && isBase64Char(text.charAt(next))) {
        wrapWidth = lineLength;
        lineLength = 0;
        i = next;
        continue;
      }
      break;
    }
    if (encodedLength % 4 == 1 && padding == 0) {
      i--;
      encodedLength--;
    }
    final boolean valid = encodedLength >= 2
        && !(urlSafe && (standardOnly || wrapWidth > 0))
        && (padding == 0 || encodedLength % 4 == 0)
        && (i == length || text.charAt(i) != '=');
    return new Payload(start, i, encodedLength, padding, urlSafe, valid);
  }

  /**
   * Decodes the leading payload characters into header bytes.
   * EMF candidates use up to 60 encoded characters, JPEG 2000 and encoded PEM
   * candidates use 48, and other headers use 32.
   *
   * @param text The text.
   * @param payload The scanned payload.
   * @return The decoded leading bytes, or {@code null} for an invalid or empty payload.
   */
  private byte[] decodeHeader(CharSequence text, Payload payload) {
    if (!payload.valid()) {
      return null;
    }
    final int limit;
    if (matchesBase64Prefix(text, payload.start(), EMF_PREFIX)) {
      limit = EMF_HEADER_ENCODED_LENGTH;
    } else if (matchesBase64Prefix(text, payload.start(), JPEG2000_PREFIX)) {
      limit = JPEG2000_HEADER_ENCODED_LENGTH;
    } else if (matchesBase64Prefix(text, payload.start(), PEM_PREFIX)) {
      limit = PEM_HEADER_ENCODED_LENGTH;
    } else {
      limit = HEADER_ENCODED_LENGTH;
    }
    final int usable = Math.min(payload.encodedLength() - payload.padding(), limit);
    final StringBuilder head = new StringBuilder(usable);
    for (int i = payload.start(); i < payload.end() && head.length() < usable; i++) {
      final char c = text.charAt(i);
      if (isAnyBase64Char(c)) {
        head.append(c);
      }
    }
    try {
      return (payload.urlSafe() ? Base64.getUrlDecoder() : Base64.getDecoder())
          .decode(head.toString());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Identifies the format and media type from the longest matching signature,
   * checking additional format fields where required.
   *
   * @param header The decoded leading bytes.
   * @param text The source text, used to inspect calendar and XML headers.
   * @param payload The scanned payload.
   * @return The format and media type, or {@code null} for an unknown header.
   */
  private KnownMagics.Format formatOf(byte[] header, CharSequence text, Payload payload) {
    final KnownMagics.Format known = KnownMagics.formatOf(header);
    if (known != null) {
      return switch (known.name()) {
        case "aiff" -> hasAiffFormType(header) ? known : null;
        case "dex" -> hasDexMagic(header) ? known : null;
        case "dwf" -> hasDwfHeader(header) ? known : null;
        case "emf" -> carries(header, EMF_SIGNATURE_OFFSET, EMF_SIGNATURE) ? known : null;
        case CALENDAR_FORMAT_NAME -> hasCalendarOpening(text, payload) ? known : null;
        case "jp2" -> jpeg2000Format(header, known);
        case "pcapng" -> hasPcapngByteOrderMagic(header) ? known : null;
        case SvgHeader.FORMAT_NAME -> svgHeader.matches(text, payload.start(), payload.end()) ? known : null;
        case "vtt" -> hasWebVttBoundary(header) ? known : null;
        case "xls" -> excelFormat(header, known);
        case "xml" -> svgHeader.matches(text, payload.start(), payload.end()) ? SvgHeader.FORMAT : known;
        default -> known;
      };
    }
    if (startsWithCalendar(text, payload.start()) && hasCalendarOpening(text, payload)) {
      return CALENDAR_FORMAT;
    }
    if (svgHeader.isCandidate(header) && svgHeader.matches(text, payload.start(), payload.end())) {
      return SvgHeader.FORMAT;
    }
    if (carries(header, 0, RIFF_MAGIC)) {
      if (carries(header, FORM_TYPE_OFFSET, "WEBP")) {
        return WEBP_FORMAT;
      }
      if (carries(header, FORM_TYPE_OFFSET, "WAVE")) {
        return WAV_FORMAT;
      }
      if (carries(header, FORM_TYPE_OFFSET, "AVI ")) {
        return AVI_FORMAT;
      }
    }
    return hasTimestampedDataType(header) ? TIMESTAMPED_DATA_FORMAT : null;
  }

  /**
   * Checks a case-insensitive calendar opening line, removing CRLF/SPACE and CRLF/TAB continuations.
   * Reads the encoded payload incrementally without allocating the complete decoded content.
   * Calendar properties and components are not validated.
   *
   * @param text The encoded source text.
   * @param payload The validated payload bounds.
   * @return Whether the first unfolded line is BEGIN:VCALENDAR followed by CRLF.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc5545.html#section-3.1">Content lines</a>
   */
  private boolean hasCalendarOpening(CharSequence text, Payload payload) {
    final Base64PayloadInputStream input = new Base64PayloadInputStream(
        text, payload.start(), payload.end(), Integer.MAX_VALUE);
    int matched = 0;
    boolean afterCr = false;
    boolean afterLf = false;
    int value;
    while ((value = input.read()) >= 0) {
      if (afterCr) {
        if (value != '\n') {
          return false;
        }
        afterCr = false;
        afterLf = true;
        continue;
      }
      if (afterLf) {
        if (value != ' ' && value != '\t') {
          return matched == CALENDAR_OPENING.length();
        }
        afterLf = false;
        continue;
      }
      if (value == '\r') {
        afterCr = true;
      } else {
        if (matched == CALENDAR_OPENING.length()) {
          return false;
        }
        final char expected = CALENDAR_OPENING.charAt(matched);
        if (value != expected && !(expected >= 'a' && expected <= 'z'
            && value == expected - ('a' - 'A'))) {
          return false;
        }
        matched++;
      }
    }
    return afterLf && matched == CALENDAR_OPENING.length();
  }

  /**
   * Checks the byte after a matched WebVTT identifier, with or without a UTF-8 BOM.
   * Header text, cue syntax and UTF-8 content are not validated.
   *
   * @param header The decoded header with a matched WebVTT prefix.
   * @return Whether the identifier ends at EOF or precedes TAB, LF, CR or SPACE.
   * @see <a href="https://www.w3.org/TR/2026/CRD-webvtt1-20260520/#iana-text-vtt">WebVTT magic</a>
   */
  private boolean hasWebVttBoundary(byte[] header) {
    final int end = header[0] == 'W' ? WEBVTT_MAGIC_LENGTH : WEBVTT_MAGIC_LENGTH + UTF8_BOM_LENGTH;
    return header.length == end || switch (header[end]) {
      case '\t', '\n', '\r', ' ' -> true;
      default -> false;
    };
  }

  /**
   * Checks a leading CMS content-type identifier within the decoded header.
   * BER definite and indefinite sequence lengths are supported. Timestamp evidence
   * and the remainder of the envelope are not validated.
   *
   * @param header The decoded leading bytes.
   * @return Whether the complete timestamped-data identifier is present.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc5544.html#section-2">TimeStampedData syntax</a>
   */
  private boolean hasTimestampedDataType(byte[] header) {
    if (header.length < 2 || header[0] != BER_SEQUENCE_TAG) {
      return false;
    }
    final int sequenceStart = berContentOffset(header, 1);
    if (sequenceStart < 0 || sequenceStart >= header.length
        || header[sequenceStart] != BER_OBJECT_IDENTIFIER_TAG) {
      return false;
    }
    final int oidStart = berContentOffset(header, sequenceStart + 1);
    if (oidStart < 0 || berLength(header, sequenceStart + 1, oidStart) != TIMESTAMPED_DATA_OID.length
        || oidStart + TIMESTAMPED_DATA_OID.length > header.length) {
      return false;
    }
    final int sequenceLength = berLength(header, 1, sequenceStart);
    final int oidEnd = oidStart + TIMESTAMPED_DATA_OID.length;
    return (sequenceLength < 0 || sequenceLength >= oidEnd - sequenceStart)
        && Arrays.equals(header, oidStart, oidEnd,
            TIMESTAMPED_DATA_OID, 0, TIMESTAMPED_DATA_OID.length);
  }

  /**
   * Locates the content after a complete BER length field, rejecting the reserved 0xff form.
   *
   * @param header The decoded leading bytes.
   * @param at The length field offset.
   * @return The content offset, or -1 for an incomplete or reserved length field.
   */
  private int berContentOffset(byte[] header, int at) {
    if (at >= header.length) {
      return -1;
    }
    final int descriptor = header[at] & 0xff;
    final int lengthBytes = descriptor < BER_INDEFINITE_LENGTH ? 0 : descriptor & 0x7f;
    if (descriptor == 0xff || lengthBytes > header.length - at - 1) {
      return -1;
    }
    return at + 1 + lengthBytes;
  }

  /**
   * Returns a checked BER length, limited to the header size plus one for comparison.
   * Extra leading zero length bytes are permitted by BER. Capping avoids overflow without
   * allocating storage for lengths that exceed the decoded header.
   *
   * @param header The bounded decoded header.
   * @param at The length field offset.
   * @param contentOffset The checked content offset.
   * @return The limited content length, or -1 for the indefinite form.
   * @see <a href="https://www.itu.int/rec/T-REC-X.690">X.690, section 8.1.3</a>
   */
  private int berLength(byte[] header, int at, int contentOffset) {
    final int descriptor = header[at] & 0xff;
    if (descriptor == BER_INDEFINITE_LENGTH) {
      return -1;
    }
    if (descriptor < BER_INDEFINITE_LENGTH) {
      return Math.min(header.length + 1, descriptor);
    }
    int length = 0;
    for (int i = at + 1; i < contentOffset; i++) {
      length = Math.min(header.length + 1, (length << Byte.SIZE) | (header[i] & 0xff));
    }
    return length;
  }

  /**
   * Checks the 3 decimal version digits and zero terminator of DEX magic.
   * The signature table checks the leading identifier. Version numbers are not
   * restricted to those supported by a particular Android runtime.
   *
   * @param header The decoded leading bytes.
   * @return Whether the complete DEX magic is present.
   * @see <a href="https://source.android.com/docs/core/runtime/dex-format#dex-file-magic">
   *     DEX file magic</a>
   */
  private boolean hasDexMagic(byte[] header) {
    return header.length >= DEX_MAGIC_LENGTH && header[DEX_MAGIC_LENGTH - 1] == 0
        && hasDecimalDigits(header, DEX_VERSION_OFFSET, DEX_MAGIC_LENGTH - 1);
  }

  /**
   * Checks the complete DWF version field and closing parenthesis after the matched prefix.
   * Drawing records and package contents are not validated.
   *
   * @param header The decoded leading bytes.
   * @return Whether the header has the form {@code (DWF Vdd.dd)} with ASCII decimal digits.
   * @see <a href="https://github.com/kveretennicov/dwf-toolkit/blob/fd1e8097b158ab3afef335484d7a111ab8da3d74/develop/global/src/dwf/whiptk/dwfhead.cpp">
   *     Autodesk DWF header reader and writer</a>
   */
  private boolean hasDwfHeader(byte[] header) {
    return header.length >= DWF_HEADER_LENGTH
        && header[DWF_DECIMAL_OFFSET] == '.' && header[DWF_HEADER_LENGTH - 1] == ')'
        && hasDecimalDigits(header, DWF_VERSION_OFFSET, DWF_DECIMAL_OFFSET)
        && hasDecimalDigits(header, DWF_DECIMAL_OFFSET + 1, DWF_HEADER_LENGTH - 1);
  }

  /**
   * Checks an available header field for ASCII decimal digits.
   *
   * @param header The decoded leading bytes.
   * @param start The first field byte.
   * @param end The exclusive field end, within the header.
   * @return Whether all field bytes are ASCII decimal digits.
   */
  private boolean hasDecimalDigits(byte[] header, int start, int end) {
    for (int i = start; i < end; i++) {
      if (header[i] < '0' || header[i] > '9') {
        return false;
      }
    }
    return true;
  }

  /**
   * Identifies JPEG 2000 file brands from the fixed fields of the initial file-type box.
   * The signature table checks the preceding signature box. Compatibility lists,
   * minor-version values and image data are not validated.
   *
   * @param header The decoded leading bytes.
   * @param jp2 The JP2 format from the signature table.
   * @return The format and media type, or {@code null} for incomplete or unsupported headers.
   * @see <a href="https://www.itu.int/rec/T-REC-T.800">JPEG 2000 core coding system, Annex I</a>
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3745.html#section-4">JPEG 2000 media types</a>
   */
  private KnownMagics.Format jpeg2000Format(byte[] header, KnownMagics.Format jp2) {
    if (header.length < JPEG2000_BOX_OFFSET + JPEG2000_MIN_BOX_LENGTH
        || !carries(header, JPEG2000_BOX_OFFSET + Integer.BYTES, "ftyp")) {
      return null;
    }
    long boxLength = Integer.toUnsignedLong(readInt(header, JPEG2000_BOX_OFFSET));
    int brandOffset = JPEG2000_BRAND_OFFSET;
    if (boxLength == 1) {
      if (header.length < JPEG2000_BOX_OFFSET + JPEG2000_MIN_BOX_LENGTH + Long.BYTES) {
        return null;
      }
      boxLength = (Integer.toUnsignedLong(readInt(header, brandOffset)) << Integer.SIZE)
          | Integer.toUnsignedLong(readInt(header, brandOffset + Integer.BYTES));
      if (Long.compareUnsigned(boxLength, JPEG2000_MIN_BOX_LENGTH + Long.BYTES) < 0) {
        return null;
      }
      brandOffset += Long.BYTES;
    } else if (boxLength != 0 && boxLength < JPEG2000_MIN_BOX_LENGTH) {
      return null;
    }
    return switch (readInt(header, brandOffset)) {
      case 0x6a703220 -> jp2; // jp2 with a trailing space
      case 0x6a707820 -> JPX_FORMAT; // jpx with a trailing space
      case 0x6a706d20 -> JPM_FORMAT; // jpm with a trailing space
      case 0x6d6a7032 -> MJ2_FORMAT; // mjp2
      default -> null;
    };
  }

  /**
   * Checks AIFF and AIFF-C form types.
   * The signature table checks the leading FORM identifier.
   *
   * @param header The decoded leading bytes.
   * @return Whether the complete audio form type appears at byte 8.
   * @see <a href="https://www.mmsp.ece.mcgill.ca/Documents/AudioFormats/AIFF/Docs/AIFF-1.3.pdf#page=5">
   *     AIFF FORM header</a>
   * @see <a href="https://www.mmsp.ece.mcgill.ca/Documents/AudioFormats/AIFF/Docs/AIFF-C.9.26.91.pdf#page=7">
   *     AIFF-C FORM header</a>
   */
  private boolean hasAiffFormType(byte[] header) {
    return carries(header, FORM_TYPE_OFFSET, "AIFF") || carries(header, FORM_TYPE_OFFSET, "AIFC");
  }

  /**
   * Checks the byte-order magic of a possible pcapng Section Header Block.
   * The signature table checks the block type.
   *
   * @param header The decoded leading bytes.
   * @return Whether the complete byte-order magic matches in either byte order.
   * @see <a href="https://www.ietf.org/archive/id/draft-ietf-opsawg-pcapng-05.html#name-section-header-block">
   *     pcapng Section Header Block</a>
   */
  private boolean hasPcapngByteOrderMagic(byte[] header) {
    if (header.length < PCAPNG_BYTE_ORDER_OFFSET + Integer.BYTES) {
      return false;
    }
    final int magic = readInt(header, PCAPNG_BYTE_ORDER_OFFSET);
    return magic == PCAPNG_BYTE_ORDER_MAGIC || magic == PCAPNG_REVERSED_BYTE_ORDER_MAGIC;
  }

  /**
   * Distinguishes BIFF2-BIFF4 worksheet and workspace document headers.
   * The signature table checks the record identifier and length. Unused BOF
   * fields do not affect the document type.
   *
   * @param header The decoded bytes matching an Excel signature.
   * @param worksheet The worksheet format for that BIFF version.
   * @return The document format, or {@code null} for incomplete or unsupported headers.
   * @see <a href="https://www.openoffice.org/sc/excelfileformat.pdf#page=135">
   *     OpenOffice Excel file format reference, BOF records</a>
   */
  private KnownMagics.Format excelFormat(byte[] header, KnownMagics.Format worksheet) {
    if (header.length < BIFF_DOCUMENT_TYPE_OFFSET + Short.BYTES
        || header.length < BIFF_RECORD_HEADER_SIZE + readUnsignedShortLE(header, Short.BYTES)) {
      return null;
    }
    return switch (readUnsignedShortLE(header, BIFF_DOCUMENT_TYPE_OFFSET)) {
      case 0x0010, 0x0020, 0x0040 -> worksheet;
      case 0x0100 -> switch (readUnsignedShortLE(header, 0)) {
        case 0x0209 -> EXCEL_3_WORKSPACE;
        case 0x0409 -> EXCEL_4_WORKSPACE;
        default -> null;
      };
      default -> null;
    };
  }

  /**
   * Takes the subtype of a media type as the format name for a declared type the
   * header does not identify.
   *
   * @param mediaType The media type.
   * @return The subtype.
   */
  private String subtype(String mediaType) {
    final int slash = mediaType.indexOf('/');
    final int parameter = mediaType.indexOf(';', slash + 1);
    final int end = parameter >= 0 ? parameter : mediaType.length();
    return slash >= 0 && slash + 1 < end ? mediaType.substring(slash + 1, end) : mediaType;
  }

  /**
   * Whether the text matches a literal at an index.
   *
   * @param text The text.
   * @param at The index.
   * @param literal The literal.
   * @return {@code true} on a full match within bounds.
   */
  private boolean matches(CharSequence text, int at, String literal) {
    if (at + literal.length() > text.length()) {
      return false;
    }
    for (int i = 0; i < literal.length(); i++) {
      if (text.charAt(at + i) != literal.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Matches a standard base64 prefix against either base64 alphabet.
   *
   * @param text The encoded text.
   * @param at The candidate prefix start.
   * @param standardPrefix The standard-base64 prefix.
   * @return {@code true} when all characters match, allowing {@code -} for {@code +}
   *         and {@code _} for {@code /}.
   */
  private boolean matchesBase64Prefix(CharSequence text, int at, String standardPrefix) {
    if (at + standardPrefix.length() > text.length()) {
      return false;
    }
    for (int i = 0; i < standardPrefix.length(); i++) {
      final char expected = standardPrefix.charAt(i);
      final char actual = text.charAt(at + i);
      if (actual != expected && !(expected == '+' && actual == '-')
          && !(expected == '/' && actual == '_')) {
        return false;
      }
    }
    return true;
  }

  /**
   * Tests whether the first decoded byte would be the ASN.1 SEQUENCE tag, 0x30.
   * Both base64 alphabets encode that byte as M followed by A through P.
   *
   * @param text The encoded text.
   * @param at The payload start.
   * @return Whether the payload is a sequence candidate requiring a full identifier check.
   */
  private boolean startsWithSequence(CharSequence text, int at) {
    return at + 1 < text.length() && text.charAt(at) == 'M'
        && text.charAt(at + 1) >= 'A' && text.charAt(at + 1) <= 'P';
  }

  /**
   * Checks for a decoded B or b, including a line continuation after the first letter.
   * The first byte uses Q or Y followed by g through v in either base64 alphabet.
   *
   * @param text The encoded text.
   * @param at The payload start.
   * @return Whether the payload can contain a calendar opening line.
   */
  private boolean startsWithCalendar(CharSequence text, int at) {
    return at + 1 < text.length() && (text.charAt(at) == 'Q' || text.charAt(at) == 'Y')
        && text.charAt(at + 1) >= 'g' && text.charAt(at + 1) <= 'v';
  }

  /**
   * Selects possible XML prefixes before decoding an untyped bare payload.
   *
   * @param text The encoded text.
   * @param at The payload start.
   * @return Whether the first decoded byte can begin XML or a byte-order mark.
   */
  private boolean startsWithXml(CharSequence text, int at) {
    if (at + 1 >= text.length()) {
      return false;
    }
    final int first = Base64PayloadInputStream.digit(text.charAt(at));
    final int second = Base64PayloadInputStream.digit(text.charAt(at + 1));
    if (first < 0 || second < 0) {
      return false;
    }
    return switch ((first << 2) | (second >>> 4)) {
      case '<', ' ', '\t', '\r', '\n', 0, 0xef, 0xfe, 0xff -> true;
      default -> false;
    };
  }

  /**
   * Returns the index behind one CRLF or LF line break.
   *
   * @param text The source text.
   * @param at The candidate line-break start.
   * @return The index behind the break, or {@code at} when none starts there.
   */
  private int lineBreakEnd(CharSequence text, int at) {
    if (at >= text.length()) {
      return at;
    }
    if (text.charAt(at) == '\n') {
      return at + 1;
    }
    if (text.charAt(at) == '\r' && at + 1 < text.length() && text.charAt(at + 1) == '\n') {
      return at + 2;
    }
    return at;
  }

  /**
   * Finds the next CR or LF, or the end of the text.
   *
   * @param text The source text.
   * @param start The line start.
   * @return The exclusive line end.
   */
  private int lineEnd(CharSequence text, int start) {
    int i = start;
    while (i < text.length() && text.charAt(i) != '\r' && text.charAt(i) != '\n') {
      i++;
    }
    return i;
  }

  /**
   * Tests the boundary before an RFC 7468 marker.
   *
   * @param text The source text.
   * @param at The marker start.
   * @return {@code true} at text start or immediately after a line break.
   */
  private boolean lineBoundaryBefore(CharSequence text, int at) {
    return at == 0 || text.charAt(at - 1) == '\r' || text.charAt(at - 1) == '\n';
  }

  /**
   * Tests the boundary after an RFC 7468 marker.
   *
   * @param text The source text.
   * @param at The index behind the marker.
   * @return {@code true} at text end or immediately before a line break.
   */
  private boolean lineBoundaryAfter(CharSequence text, int at) {
    return at == text.length() || text.charAt(at) == '\r' || text.charAt(at) == '\n';
  }

  /**
   * Tests the boundary before a compact token.
   *
   * @param text The source text.
   * @param at The token start.
   * @return {@code true} when no base64url or segment character precedes it.
   */
  private boolean tokenBoundaryBefore(CharSequence text, int at) {
    return at == 0 || (!isBase64UrlChar(text.charAt(at - 1)) && text.charAt(at - 1) != '.');
  }

  /**
   * Tests the boundary after a compact token.
   *
   * @param text The source text.
   * @param at The token end.
   * @return {@code true} when no base64url, segment, or padding character follows it.
   */
  private boolean tokenBoundaryAfter(CharSequence text, int at) {
    return at == text.length() || (!isBase64UrlChar(text.charAt(at))
        && text.charAt(at) != '.' && text.charAt(at) != '=');
  }

  /**
   * Computes decoded bytes from encoded character and padding counts.
   *
   * @param encodedLength The encoded character count, including padding.
   * @param padding The number of trailing padding characters.
   * @return The decoded byte count.
   */
  private long decodedLength(int encodedLength, int padding) {
    long decoded = encodedLength / 4L * 3 - padding;
    if (encodedLength % 4 == 2) {
      decoded++;
    } else if (encodedLength % 4 == 3) {
      decoded += 2;
    }
    return decoded;
  }

  /**
   * Whether the bytes carry an ASCII tag at an offset.
   *
   * @param bytes The bytes.
   * @param at The offset.
   * @param tag The expected characters, all of them ASCII.
   * @return {@code true} on a full match within bounds.
   */
  private boolean carries(byte[] bytes, int at, String tag) {
    if (at + tag.length() > bytes.length) {
      return false;
    }
    for (int i = 0; i < tag.length(); i++) {
      if ((bytes[at + i] & 0xFF) != tag.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Reads a big-endian int.
   *
   * @param bytes The bytes.
   * @param at The offset.
   * @return The value.
   */
  private int readInt(byte[] bytes, int at) {
    return ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16)
        | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
  }

  /**
   * Returns an unsigned little-endian 16-bit value within checked bounds.
   *
   * @param bytes The source bytes.
   * @param at The byte offset.
   * @return The value from 0 to 65535.
   */
  private int readUnsignedShortLE(byte[] bytes, int at) {
    return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << Byte.SIZE);
  }

  /**
   * Whether the character belongs to the base64 alphabet, padding excluded.
   *
   * @param c The character.
   * @return {@code true} for the 64 payload characters.
   */
  private boolean isBase64Char(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
        || c == '+' || c == '/';
  }

  /**
   * Whether the character belongs to the URL-safe base64 alphabet.
   *
   * @param c The character.
   * @return {@code true} for a URL-safe payload character.
   */
  private boolean isBase64UrlChar(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
        || c == '-' || c == '_';
  }

  /**
   * Whether the character belongs to either base64 alphabet.
   *
   * @param c The character.
   * @return {@code true} for a standard or URL-safe payload character.
   */
  private boolean isAnyBase64Char(char c) {
    return isBase64Char(c) || c == '-' || c == '_';
  }

  /**
   * Precomputes an RFC 7468 type's boundary markers once rather than during scans.
   *
   * @param label The textual envelope label.
   * @param format The asset format tag.
   * @param mediaType The asset media type.
   * @return The immutable type metadata.
   */
  private static PemType pemType(String label, String format, String mediaType) {
    return new PemType("-----BEGIN " + label + "-----", "-----END " + label + "-----",
        format, mediaType);
  }

  /**
   * One scanned encoded payload and the counts needed for exact decoding metadata.
   *
   * @param start The payload start.
   * @param end The payload end.
   * @param encodedLength The encoded character count, excluding line separators.
   * @param padding The trailing padding character count.
   * @param urlSafe Whether the payload uses a URL-only alphabet character.
   * @param valid Whether the scanned alphabet, wrapping, and padding can be decoded together.
   */
  private record Payload(int start, int end, int encodedLength, int padding,
                         boolean urlSafe, boolean valid) {
  }

  /**
   * One supported RFC 7468 boundary pair and the asset tags it implies.
   *
   * @param begin The complete begin boundary.
   * @param end The complete end boundary.
   * @param format The asset format tag.
   * @param mediaType The asset media type.
   */
  private record PemType(String begin, String end, String format, String mediaType) {
  }
}
