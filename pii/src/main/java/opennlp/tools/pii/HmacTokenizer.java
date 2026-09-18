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

package opennlp.tools.pii;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import opennlp.tools.document.Document;

/**
 * Replaces PII mentions with HMAC tokens that are stable across documents for a given key.
 *
 * <p>A token contains the type with ASCII letters uppercased, followed by hexadecimal
 * digits from <a href="https://datatracker.ietf.org/doc/html/rfc2104">HMAC</a>-SHA-256.
 * The MAC input is the UTF-8 encoding of the case-sensitive type, a zero byte and the
 * mention's {@link PiiMention#normalized() normalized form}. Types and values must
 * contain valid UTF-16. Unpaired surrogates are rejected before encoding.</p>
 *
 * <p>Use at least 32 random key bytes and keep the key separate from the data. Tokens
 * made with the same key are linkable pseudonymous data and require access control.
 * Changing the key changes the tokens. Truncation increases collision risk; use longer
 * tokens for large collections.</p>
 *
 * <p>Tokens can change text length. {@link PiiRewrite} maps annotations to output
 * offsets.</p>
 *
 * <p>Text replacement rejects overlapping mentions. Use {@link #token(PiiMention)}
 * to tokenize those detections individually, or {@link Masker} for redaction.</p>
 *
 * <p>Instances are immutable and safe to share between threads.</p>
 *
 * @since 3.0.0
 */
public final class HmacTokenizer {

  /** The HMAC algorithm. */
  private static final String ALGORITHM = "HmacSha256";

  /** Default token length in hexadecimal digits. */
  private static final int DEFAULT_LENGTH = 16;

  /** Minimum key length recommended for HMAC-SHA-256. */
  private static final int MINIMUM_KEY_BYTES = 32;

  /** Hexadecimal digits, lowercase, indexed by value. */
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private final SecretKeySpec key;
  private final int length;

  /**
   * Initializes a tokenizer producing 16 hexadecimal digits (64 bits) per token.
   *
   * @param key The secret key. Must contain at least 32 bytes. The bytes are copied.
   * @throws IllegalArgumentException Thrown if {@code key} is {@code null} or the key
   *         length is below 32 bytes.
   */
  public HmacTokenizer(byte[] key) {
    this(key, DEFAULT_LENGTH);
  }

  /**
   * Initializes a tokenizer with an explicit token length. Shorter tokens increase
   * collision risk. The default is 16 hexadecimal digits.
   *
   * @param key The secret key. Must contain at least 32 bytes. The bytes are copied.
   * @param length The number of hexadecimal digits to show. Must be between {@code 4} and
   *               {@code 64}, the full width of a SHA-256 MAC.
   * @throws IllegalArgumentException Thrown if {@code key} is {@code null}, the key length
   *         is below 32 bytes, or {@code length} is out of range.
   */
  public HmacTokenizer(byte[] key, int length) {
    if (key == null) {
      throw new IllegalArgumentException("key must not be null");
    }
    if (key.length < MINIMUM_KEY_BYTES) {
      throw new IllegalArgumentException("key must contain at least 32 bytes");
    }
    if (length < 4 || length > 64) {
      throw new IllegalArgumentException("length must be between 4 and 64: " + length);
    }
    this.key = new SecretKeySpec(key.clone(), ALGORITHM);
    this.length = length;
  }

  /**
   * Tokenizes one mention.
   *
   * @param mention The mention. Must not be {@code null}. Type and normalized value must
   *                contain valid UTF-16.
   * @return The non-null token, for example {@code EMAIL-3f2a1c9d7e4b6a20}.
   * @throws IllegalArgumentException Thrown if {@code mention} is {@code null}, or the
   *         type or normalized value contains an unpaired surrogate.
   */
  public String token(PiiMention mention) {
    if (mention == null) {
      throw new IllegalArgumentException("mention must not be null");
    }
    return token(mention.type(), mention.normalized());
  }

  /**
   * Tokenizes a known normalized value and case-sensitive type.
   *
   * <p>Supply the same normalized value used by {@link #token(PiiMention)}, such as card
   * digits without separators. The type is included in the MAC input.</p>
   *
   * @param type The mention type, for example {@link PiiMention#TYPE_EMAIL}. Must not be
   *             {@code null} or blank, and must contain valid UTF-16.
   * @param value The normalized value. Must not be {@code null} or empty, and must
   *              contain valid UTF-16.
   * @return The non-null token.
   * @throws IllegalArgumentException Thrown if {@code type} is {@code null} or blank, or
   *         {@code value} is {@code null} or empty, or either contains an unpaired surrogate.
   */
  public String token(String type, String value) {
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("type must not be null or blank");
    }
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("value must not be null or empty");
    }
    if (hasUnpairedSurrogate(type)) {
      throw new IllegalArgumentException("type must not contain unpaired surrogates");
    }
    if (hasUnpairedSurrogate(value)) {
      throw new IllegalArgumentException("value must not contain unpaired surrogates");
    }
    return Ascii.toUpper(type) + '-' + digest(type + '\u0000' + value);
  }

  /**
   * Replaces each mention with a token.
   *
   * @param text The original text. Must not be {@code null}.
   * @param mentions The mentions to replace, as reported by a {@link PiiExtractor}. Must
   *                 not be {@code null} or contain {@code null}. All spans must be
   *                 within {@code text} and must not overlap. Mention types and
   *                 normalized values must contain valid UTF-16.
   * @return The non-null rewrite result.
   * @throws IllegalArgumentException Thrown if an argument is {@code null}, a mention is
   *         {@code null}, a span lies outside the text, spans overlap, or a mention's
   *         type or normalized value contains an unpaired surrogate.
   */
  public PiiRewrite rewrite(CharSequence text, List<PiiMention> mentions) {
    return PiiRewrite.replace(text, mentions, this::token);
  }

  /**
   * Replaces the mentions from a document's {@link PiiAnnotator#PII} layer.
   *
   * @param document The document to rewrite. Must be non-null and have a
   *                 {@link PiiAnnotator#PII} layer with matching annotation and mention
   *                 offsets. Mention types and normalized values must contain valid UTF-16.
   * @return The non-null rewrite result.
   * @throws IllegalArgumentException Thrown if {@code document} is null, lacks the PII
   *         layer, contains overlapping mentions, an annotation and mention have different
   *         offsets, or a mention's type or normalized value contains an unpaired surrogate.
   */
  public PiiRewrite rewrite(Document document) {
    final List<PiiMention> mentions = PiiLayer.mentions(document);
    return rewrite(document.text(), mentions);
  }

  /**
   * Checks for unmatched UTF-16 surrogates.
   *
   * @param value The non-null text to check.
   * @return Whether the text contains an unpaired surrogate.
   */
  private boolean hasUnpairedSurrogate(String value) {
    for (int offset = 0; offset < value.length();) {
      final int codePoint = value.codePointAt(offset);
      if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
        return true;
      }
      offset += Character.charCount(codePoint);
    }
    return false;
  }

  /**
   * Computes the leading hexadecimal digits of the MAC of a message.
   *
   * @param message The message to authenticate.
   * @return The digits, {@link #length} of them.
   */
  private String digest(String message) {
    final byte[] mac = mac(message.getBytes(StandardCharsets.UTF_8));
    final StringBuilder hex = new StringBuilder(length);
    for (int i = 0; hex.length() < length; i++) {
      hex.append(HEX[(mac[i] >> 4) & 0xf]);
      if (hex.length() < length) {
        hex.append(HEX[mac[i] & 0xf]);
      }
    }
    return hex.toString();
  }

  /**
   * Authenticates a message with a separate {@link Mac} instance for each call.
   *
   * @param message The message to authenticate.
   * @return The 32-byte MAC.
   * @throws IllegalStateException If the MAC algorithm is unavailable.
   */
  private byte[] mac(byte[] message) {
    try {
      final Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac.doFinal(message);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(ALGORITHM + " is required of every Java platform", e);
    }
  }
}
