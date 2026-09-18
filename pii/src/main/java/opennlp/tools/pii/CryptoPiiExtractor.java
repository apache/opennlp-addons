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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Extracts Bitcoin and Ethereum address candidates. This detector is opt-in.
 *
 * <p>Recognized forms:</p>
 * <ul>
 *   <li>Bitcoin, legacy: 26 to 35
 *   <a href="https://en.bitcoin.it/wiki/Base58Check_encoding">Base58Check</a> characters
 *   starting with {@code 1} for a public key hash or {@code 3} for a script hash.
 *   The decoded address must contain a mainnet version byte, a 20-byte hash and a valid
 *   4-byte double SHA-256 checksum.</li>
 *   <li>Bitcoin, segwit: the prefix {@code bc1}, witness version 0 through 16 and a
 *   2-to-40-byte program. Version 0 requires a 20-byte or 32-byte program. The checksum uses
 *   <a href="https://github.com/bitcoin/bips/blob/master/bip-0173.mediawiki">BIP-173</a>
 *   bech32 for witness version zero and
 *   <a href="https://github.com/bitcoin/bips/blob/master/bip-0350.mediawiki">BIP-350</a>
 *   bech32m for later versions. Padding bits must be zero, with at most 4 unused bits.
 *   All-lowercase and all-uppercase forms are accepted; mixed-case forms are rejected.</li>
 *   <li>Ethereum: {@code 0x} or {@code 0X} and 40 ASCII hexadecimal digits.
 *   A mixed-case candidate must satisfy
 *   the <a href="https://eips.ethereum.org/EIPS/eip-55">EIP-55</a> capitalization checksum;
 *   all-lowercase and all-uppercase candidates are accepted without a checksum check.</li>
 * </ul>
 *
 * <p>Bitcoin testnet and regtest addresses and the Ethereum zero address are excluded.
 * An Ethereum address does not identify the network. Detection does not verify ownership,
 * transactions, balances or whether an address is used. Single-case Ethereum values may
 * also be hexadecimal identifiers unrelated to a wallet.</p>
 *
 * <p>Normalized forms: a legacy Bitcoin address retains the input characters, since
 * Base58Check is case sensitive; a segwit address is lowercased, the form BIP-173
 * recommends; an Ethereum address becomes {@code 0x} and the EIP-55 capitalization, so
 * the same address written in any accepted case normalizes to one string.</p>
 *
 * <p>Both types are reported by default; the {@link #CryptoPiiExtractor(Set)} constructor
 * limits extraction to a subset.</p>
 *
 * <p>Word boundaries use Unicode letters and digits. Punctuation, including underscores,
 * separates candidates. Instances have no per-call state and may be shared between threads.</p>
 *
 * @since 3.0.0
 */
public final class CryptoPiiExtractor implements PiiExtractor {

  private static final Set<String> ALL_TYPES =
      Set.of(PiiMention.TYPE_BTC_ADDRESS, PiiMention.TYPE_ETH_ADDRESS);

  private static final int BTC_LEGACY_MIN_LENGTH = 26;
  private static final int BTC_LEGACY_MAX_LENGTH = 35;

  /** The main network version byte of a pay-to-public-key-hash address. */
  private static final int BTC_VERSION_P2PKH = 0x00;

  /** The main network version byte of a pay-to-script-hash address. */
  private static final int BTC_VERSION_P2SH = 0x05;

  /** The human-readable prefix of a main network segwit address. */
  private static final String BTC_SEGWIT_PREFIX = "bc";

  /** The longest bech32 string BIP-173 allows. */
  private static final int BTC_SEGWIT_MAX_LENGTH = 90;

  private static final String ETH_PREFIX = "0x";
  private static final int ETH_DIGITS = 40;

  /** The nibble value from which EIP-55 capitalizes the matching address character. */
  private static final int ETH_CHECKSUM_THRESHOLD = 8;

  private final Set<String> types;

  /**
   * Initializes an extractor that reports both types.
   */
  public CryptoPiiExtractor() {
    this.types = ALL_TYPES;
  }

  /**
   * Initializes an extractor limited to a subset of the types.
   *
   * @param types The types to report: {@link PiiMention#TYPE_BTC_ADDRESS} or
   *              {@link PiiMention#TYPE_ETH_ADDRESS}. Must be non-null and non-empty,
   *              without null or unrecognized entries.
   * @throws IllegalArgumentException Thrown if {@code types} is {@code null} or empty, or
   *         contains a null or unrecognized type.
   */
  public CryptoPiiExtractor(Set<String> types) {
    if (types == null || types.isEmpty()) {
      throw new IllegalArgumentException("types must not be null or empty");
    }
    for (final String type : types) {
      if (type == null || !ALL_TYPES.contains(type)) {
        throw new IllegalArgumentException("types contains an unrecognized type: " + type);
      }
    }
    this.types = Set.copyOf(types);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Enabled types are scanned independently and overlapping candidates are retained.</p>
   */
  @Override
  public List<PiiMention> extract(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    final List<Hits.Hit> hits = new ArrayList<>();
    if (types.contains(PiiMention.TYPE_BTC_ADDRESS)) {
      scanBitcoin(text, hits);
    }
    if (types.contains(PiiMention.TYPE_ETH_ADDRESS)) {
      scanEthereum(text, hits);
    }
    return Hits.resolve(hits);
  }

  /**
   * Finds Bitcoin addresses in the legacy and the segwit form.
   *
   * @param text The text to scan.
   * @param hits The candidate collector.
   */
  private void scanBitcoin(CharSequence text, List<Hits.Hit> hits) {
    for (int i = 0; i < text.length(); i++) {
      if (!Boundaries.onWordStart(text, i)) {
        continue;
      }
      final char first = text.charAt(i);
      int end = -1;
      String normalized = null;
      if (first == '1' || first == '3') {
        end = legacyEnd(text, i);
        if (end > 0) {
          normalized = text.subSequence(i, end).toString();
        }
      } else if (first == 'b' || first == 'B') {
        end = segwitEnd(text, i);
        if (end > 0) {
          normalized = Ascii.toLower(text.subSequence(i, end));
        }
      }
      if (end < 0) {
        continue;
      }
      Hits.add(hits, i, end, PiiMention.TYPE_BTC_ADDRESS, normalized);
      // The loop increment resumes the scan at the exclusive match end.
      i = end - 1;
    }
  }

  /**
   * Parses a legacy Base58Check address.
   *
   * @param text The text being scanned.
   * @param start The offset to read from.
   * @return The exclusive end offset, or {@code -1} if no address starts at {@code start}.
   */
  private int legacyEnd(CharSequence text, int start) {
    int p = start;
    while (p < text.length() && Base58Check.isBase58Char(text.charAt(p))) {
      p++;
    }
    final int length = p - start;
    if (length < BTC_LEGACY_MIN_LENGTH || length > BTC_LEGACY_MAX_LENGTH
        || !Boundaries.onEnd(text, p)) {
      return -1;
    }
    final int version = Base58Check.checkedVersion(text, start, p);
    return version == BTC_VERSION_P2PKH || version == BTC_VERSION_P2SH ? p : -1;
  }

  /**
   * Parses a segwit bech32 or bech32m address.
   *
   * @param text The text being scanned.
   * @param start The offset to read from.
   * @return The exclusive end offset, or {@code -1} if no address starts at {@code start}.
   */
  private int segwitEnd(CharSequence text, int start) {
    final int dataStart = start + BTC_SEGWIT_PREFIX.length() + 1;
    if (dataStart > text.length()
        || Ascii.toLower(text.charAt(start + 1)) != BTC_SEGWIT_PREFIX.charAt(1)
        || text.charAt(dataStart - 1) != '1') {
      return -1;
    }
    int p = dataStart;
    while (p < text.length() && Bech32.isDataChar(text.charAt(p))) {
      p++;
    }
    if (p - start > BTC_SEGWIT_MAX_LENGTH || !Boundaries.onEnd(text, p)
        || mixedCase(text, start, p)) {
      return -1;
    }
    return Bech32.checkedWitnessVersion(text, dataStart, p, BTC_SEGWIT_PREFIX) < 0 ? -1 : p;
  }

  /**
   * Checks for mixed case, which BIP-173 excludes independently of the checksum.
   *
   * @param text The text being scanned.
   * @param start The first character of the candidate.
   * @param end The exclusive end of the candidate.
   * @return {@code true} if the candidate contains both uppercase and lowercase letters.
   */
  private boolean mixedCase(CharSequence text, int start, int end) {
    boolean upper = false;
    boolean lower = false;
    for (int i = start; i < end; i++) {
      upper |= Ascii.isUpper(text.charAt(i));
      lower |= Ascii.isLower(text.charAt(i));
    }
    return upper && lower;
  }

  /**
   * Finds Ethereum addresses.
   *
   * @param text The text to scan.
   * @param hits The candidate collector.
   */
  private void scanEthereum(CharSequence text, List<Hits.Hit> hits) {
    for (int i = 0; i + ETH_PREFIX.length() + ETH_DIGITS <= text.length(); i++) {
      if (text.charAt(i) != '0' || Ascii.toLower(text.charAt(i + 1)) != ETH_PREFIX.charAt(1)
          || !Boundaries.onWordStart(text, i)) {
        continue;
      }
      final int digitsStart = i + ETH_PREFIX.length();
      final int end = digitsStart + ETH_DIGITS;
      final StringBuilder lowercase = new StringBuilder(ETH_DIGITS);
      boolean hexadecimal = true;
      boolean zero = true;
      for (int p = digitsStart; p < end && hexadecimal; p++) {
        final char c = text.charAt(p);
        hexadecimal = Ascii.isHexDigit(c);
        zero &= c == '0';
        lowercase.append(Ascii.toLower(c));
      }
      if (!hexadecimal || zero || !Boundaries.onEnd(text, end)) {
        continue;
      }
      final String checksummed = eip55(lowercase.toString());
      if (mixedCase(text, digitsStart, end)
          && !contentEquals(text, digitsStart, end, checksummed)) {
        continue;
      }
      Hits.add(hits, i, end, PiiMention.TYPE_ETH_ADDRESS, ETH_PREFIX + checksummed);
      // The loop increment resumes the scan at the exclusive match end.
      i = end - 1;
    }
  }

  /**
   * Applies the <a href="https://eips.ethereum.org/EIPS/eip-55">EIP-55</a> capitalization:
   * a hexadecimal letter becomes uppercase where the matching nibble of the Keccak-256 hash
   * of the lowercase address is {@code 8} or greater.
   *
   * @param lowercase The 40 lowercase hexadecimal digits of the address.
   * @return The capitalized digits.
   */
  private String eip55(String lowercase) {
    final byte[] hash = Keccak256.digest(lowercase.getBytes(StandardCharsets.US_ASCII));
    final StringBuilder out = new StringBuilder(ETH_DIGITS);
    for (int i = 0; i < ETH_DIGITS; i++) {
      final int nibble = i % 2 == 0 ? (hash[i / 2] >> 4) & 0xF : hash[i / 2] & 0xF;
      final char c = lowercase.charAt(i);
      out.append(nibble >= ETH_CHECKSUM_THRESHOLD ? Ascii.toUpper(c) : c);
    }
    return out.toString();
  }

  /**
   * Compares a range of the scanned text with a string.
   *
   * @param text The text being scanned.
   * @param start The first character to compare.
   * @param end The exclusive end of the range to compare.
   * @param value The string to compare with; must be as long as the range.
   * @return {@code true} if the range equals the string.
   */
  private boolean contentEquals(CharSequence text, int start, int end, String value) {
    for (int i = start; i < end; i++) {
      if (text.charAt(i) != value.charAt(i - start)) {
        return false;
      }
    }
    return true;
  }
}
