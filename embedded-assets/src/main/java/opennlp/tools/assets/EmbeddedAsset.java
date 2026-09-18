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

import java.util.Base64;

import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

/**
 * An encoded payload with source spans, a format, and metadata from its header.
 *
 * <p>Format names are open strings; the constants identify selected formats.
 * Both spans refer to the original text. Callers can decode the payload or replace
 * the full span while retaining its source location.</p>
 *
 * @param span The full location in the original text, including any URI prefix around
 *             the payload. Must not be {@code null}.
 * @param payload The location of the encoded payload within {@code span}, including
 *                permitted line separators. Must not be {@code null} and must lie
 *                inside {@code span}.
 * @param format The format, for example {@link #FORMAT_PNG}: sniffed from the decoded
 *               header when it carries a known magic, otherwise as the carrying URI
 *               declared it. Must not be {@code null} or blank.
 * @param mediaType The media type, either as declared by the carrying URI or derived
 *                  from the format. Must not be {@code null} or blank.
 * @param decodedLength The length of the decoded bytes.
 * @param width The positive pixel width, or -1 when unknown.
 * @param height The positive pixel height, or -1 when unknown.
 *
 * @since 3.0.0
 */
public record EmbeddedAsset(Span span, Span payload, String format, String mediaType,
                            long decodedLength, int width, int height) {

  /** A PNG image. */
  public static final String FORMAT_PNG = "png";

  /** A JPEG image. */
  public static final String FORMAT_JPEG = "jpeg";

  /** A GIF image. */
  public static final String FORMAT_GIF = "gif";

  /** A WebP image. */
  public static final String FORMAT_WEBP = "webp";

  /** A RIFF wave audio file. */
  public static final String FORMAT_WAV = "wav";

  /** A PDF document. */
  public static final String FORMAT_PDF = "pdf";

  /** A zip archive, which is also the container of the common office formats. */
  public static final String FORMAT_ZIP = "zip";

  /** A TIFF image, in either byte order. */
  public static final String FORMAT_TIFF = "tiff";

  /** A gzip-compressed stream. */
  public static final String FORMAT_GZIP = "gzip";

  /** A 7z archive. */
  public static final String FORMAT_SEVEN_ZIP = "7z";

  /** A RAR archive. */
  public static final String FORMAT_RAR = "rar";

  /** A FLAC audio stream. */
  public static final String FORMAT_FLAC = "flac";

  /** An Ogg container, the usual carrier of Vorbis and Opus audio. */
  public static final String FORMAT_OGG = "ogg";

  /** A standard MIDI file. */
  public static final String FORMAT_MIDI = "midi";

  /** A SQLite database file. */
  public static final String FORMAT_SQLITE = "sqlite";

  /** An ELF executable or shared object. */
  public static final String FORMAT_ELF = "elf";

  /** A Windows Portable Executable. */
  public static final String FORMAT_PE = "pe";

  /** A Java class file. */
  public static final String FORMAT_CLASS = "class";

  /** A WOFF font. */
  public static final String FORMAT_WOFF = "woff";

  /** A WOFF2 font. */
  public static final String FORMAT_WOFF2 = "woff2";

  /** An MP3 audio stream carrying an ID3 metadata block. */
  public static final String FORMAT_MP3 = "mp3";

  /** An OLE2 compound document, the container of the legacy Office formats. */
  public static final String FORMAT_OLE2 = "ole2";

  /** A Zstandard-compressed stream. */
  public static final String FORMAT_ZSTD = "zstd";

  /** A WebAssembly module. */
  public static final String FORMAT_WASM = "wasm";

  /** A RIFF AVI video. */
  public static final String FORMAT_AVI = "avi";

  /** A JSON Web Token in compact serialization. */
  public static final String FORMAT_JWT = "jwt";

  /**
   * Validates the asset.
   *
   * @throws IllegalArgumentException Thrown if a span is {@code null}, {@code payload}
   *         lies outside {@code span}, {@code format} or {@code mediaType} is
   *         {@code null} or blank, {@code decodedLength} is negative, or a dimension
   *         is neither positive nor -1.
   */
  public EmbeddedAsset {
    if (span == null) {
      throw new IllegalArgumentException("span must not be null");
    }
    if (payload == null) {
      throw new IllegalArgumentException("payload must not be null");
    }
    if (payload.getStart() < span.getStart() || payload.getEnd() > span.getEnd()) {
      throw new IllegalArgumentException("payload must lie inside the span");
    }
    if (format == null || StringUtil.isBlank(format)) {
      throw new IllegalArgumentException("format must not be null or blank");
    }
    if (mediaType == null || StringUtil.isBlank(mediaType)) {
      throw new IllegalArgumentException("mediaType must not be null or blank");
    }
    if (decodedLength < 0) {
      throw new IllegalArgumentException("decodedLength must not be negative");
    }
    if (width != -1 && width <= 0) {
      throw new IllegalArgumentException("width must be positive or -1");
    }
    if (height != -1 && height <= 0) {
      throw new IllegalArgumentException("height must be positive or -1");
    }
  }

  /**
   * Decodes the payload from the text the asset was detected in.
   *
   * <p>Standard base64, CR/LF-wrapped MIME base64, and the base64url alphabet are
   * accepted. The alphabets may not be mixed.</p>
   *
   * @param text The original text this asset's spans refer to. Must not be
   *             {@code null}.
   * @return The decoded bytes. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code text} is {@code null}, shorter
   *         than the payload span, or the payload characters are not valid base64,
   *         MIME base64, or base64url.
   */
  public byte[] decode(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    if (text.length() < payload.getEnd()) {
      throw new IllegalArgumentException("text is shorter than the payload span");
    }
    final String encoded = text.subSequence(payload.getStart(), payload.getEnd()).toString();
    boolean standardOnly = false;
    boolean urlOnly = false;
    boolean wrapped = false;
    for (int i = 0; i < encoded.length(); i++) {
      final char c = encoded.charAt(i);
      if (c == '+' || c == '/') {
        standardOnly = true;
      } else if (c == '-' || c == '_') {
        urlOnly = true;
      } else if (c == '\r' || c == '\n') {
        wrapped = true;
      } else if (!isSharedBase64Char(c) && c != '=') {
        throw new IllegalArgumentException("payload contains a non-base64 character");
      }
    }
    if (standardOnly && urlOnly) {
      throw new IllegalArgumentException("payload mixes base64 and base64url alphabets");
    }
    if (wrapped && urlOnly) {
      throw new IllegalArgumentException("wrapped base64url payloads are not supported");
    }
    if (urlOnly) {
      return Base64.getUrlDecoder().decode(encoded);
    }
    if (wrapped) {
      return Base64.getMimeDecoder().decode(encoded);
    }
    return Base64.getDecoder().decode(encoded);
  }

  /**
   * Tests the characters shared by the standard and URL-safe base64 alphabets.
   *
   * @param c The character to test.
   * @return {@code true} for an ASCII letter or digit.
   */
  private boolean isSharedBase64Char(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
  }
}
