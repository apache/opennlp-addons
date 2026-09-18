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

import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Deterministic single-position mutation checks for every checksum family in the PII
 * extractors. These tests prove that recognition depends on the checksum, not only on a
 * prefix, alphabet, or length.
 */
public class ChecksumMutationTest {

  private static final String BASE58 =
      "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  private static final String BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

  @Test
  void testEveryCardDigitMutationIsRejected() {
    assertDigitMutationsRejected(new CursorPiiExtractor(Set.of(PiiMention.TYPE_CARD)),
        PiiMention.TYPE_CARD, "4111111111111111", "", "");
  }

  @Test
  void testEveryIbanCharacterMutationIsRejected() {
    final PiiExtractor extractor =
        new CursorPiiExtractor(Set.of(PiiMention.TYPE_IBAN));
    final String value = "DE89370400440532013000";
    for (int i = 0; i < value.length(); i++) {
      final char replacement = Ascii.isDigit(value.charAt(i))
          ? nextDigit(value.charAt(i)) : nextUpper(value.charAt(i));
      assertRejected(extractor, PiiMention.TYPE_IBAN, replace(value, i, replacement));
    }
  }

  @Test
  void testEveryLegacyBitcoinCharacterMutationIsRejected() {
    final PiiExtractor extractor =
        new CryptoPiiExtractor(Set.of(PiiMention.TYPE_BTC_ADDRESS));
    final String value = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa";
    for (int i = 0; i < value.length(); i++) {
      final int index = BASE58.indexOf(value.charAt(i));
      assertRejected(extractor, PiiMention.TYPE_BTC_ADDRESS,
          replace(value, i, BASE58.charAt((index + 1) % BASE58.length())));
    }
  }

  @Test
  void testEverySegwitDataCharacterMutationIsRejected() {
    final PiiExtractor extractor =
        new CryptoPiiExtractor(Set.of(PiiMention.TYPE_BTC_ADDRESS));
    final String value = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
    for (int i = 3; i < value.length(); i++) {
      final int index = BECH32.indexOf(value.charAt(i));
      assertRejected(extractor, PiiMention.TYPE_BTC_ADDRESS,
          replace(value, i, BECH32.charAt((index + 1) % BECH32.length())));
    }
  }

  @Test
  void testEveryEthereumDigitMutationIsRejected() {
    final PiiExtractor extractor =
        new CryptoPiiExtractor(Set.of(PiiMention.TYPE_ETH_ADDRESS));
    final String value = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed";
    for (int i = 2; i < value.length(); i++) {
      assertRejected(extractor, PiiMention.TYPE_ETH_ADDRESS,
          replace(value, i, nextHex(value.charAt(i))));
    }
  }

  @Test
  void testEveryNhsDigitMutationIsRejected() {
    assertDigitMutationsRejected(
        new EuIdentityPiiExtractor(Set.of(PiiMention.TYPE_UK_NHS)),
        PiiMention.TYPE_UK_NHS, "9434765919", "", "");
  }

  @Test
  void testEveryGermanTaxIdDigitMutationIsRejected() {
    assertDigitMutationsRejected(
        new EuIdentityPiiExtractor(Set.of(PiiMention.TYPE_DE_STEUER_ID)),
        PiiMention.TYPE_DE_STEUER_ID, "65929970489", "", "");
  }

  @Test
  void testEveryAbaRoutingDigitMutationIsRejected() {
    assertDigitMutationsRejected(new BankingPiiExtractor(), PiiMention.TYPE_ABA_ROUTING,
        "021000021", "", "");
  }

  @Test
  void testEveryImeiDigitMutationIsRejected() {
    assertDigitMutationsRejected(new DevicePiiExtractor(), PiiMention.TYPE_IMEI,
        "490154203237518", "IMEI: ", "");
  }

  @Test
  void testEveryCanadianSinDigitMutationIsRejected() {
    assertDigitMutationsRejected(new CaIdentityPiiExtractor(), PiiMention.TYPE_CA_SIN,
        "046454286", "SIN: ", "");
  }

  /**
   * Mutates every digit in a fixture and asserts that the selected type disappears.
   *
   * @param extractor The extractor under test.
   * @param type The type that must not be reported.
   * @param value The valid normalized fixture.
   * @param prefix Context placed before the value.
   * @param suffix Context placed after the value.
   */
  private void assertDigitMutationsRejected(PiiExtractor extractor, String type,
      String value, String prefix, String suffix) {
    for (int i = 0; i < value.length(); i++) {
      assertRejected(extractor, type,
          prefix + replace(value, i, nextDigit(value.charAt(i))) + suffix);
    }
  }

  /**
   * Asserts that an extractor does not report a selected type.
   *
   * @param extractor The extractor under test.
   * @param type The forbidden reported type.
   * @param text The mutated text.
   */
  private void assertRejected(PiiExtractor extractor, String type, String text) {
    Assertions.assertTrue(extractor.extract(text).stream()
        .noneMatch(mention -> type.equals(mention.type())), text);
  }

  /**
   * Returns the next decimal digit, wrapping nine to zero.
   *
   * @param c The current decimal digit.
   * @return A different decimal digit.
   */
  private char nextDigit(char c) {
    return c == '9' ? '0' : (char) (c + 1);
  }

  /**
   * Returns the next uppercase ASCII letter, wrapping Z to A.
   *
   * @param c The current uppercase ASCII letter.
   * @return A different uppercase ASCII letter.
   */
  private char nextUpper(char c) {
    return c == 'Z' ? 'A' : (char) (c + 1);
  }

  /**
   * Returns a different hexadecimal digit while preserving letter case.
   *
   * @param c The current hexadecimal digit.
   * @return A different hexadecimal digit.
   */
  private char nextHex(char c) {
    if (Ascii.isDigit(c)) {
      return nextDigit(c);
    }
    if (c == 'f') {
      return 'a';
    }
    if (c == 'F') {
      return 'A';
    }
    return (char) (c + 1);
  }

  /**
   * Returns a copy with one character replaced.
   *
   * @param value The source value.
   * @param index The replacement offset.
   * @param replacement The new character.
   * @return The mutated value.
   */
  private String replace(String value, int index, char replacement) {
    final StringBuilder mutated = new StringBuilder(value);
    mutated.setCharAt(index, replacement);
    return mutated.toString();
  }
}
