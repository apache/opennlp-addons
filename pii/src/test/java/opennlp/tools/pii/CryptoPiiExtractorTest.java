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

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.StringUtil;

public class CryptoPiiExtractorTest {

  private final CryptoPiiExtractor extractor = new CryptoPiiExtractor();

  /**
   * Checks mainnet Base58Check addresses and their original spans.
   *
   * @param text The address candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
      "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2",
      "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
      "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy",
      "3QJmV3qfvL9SuYo34YihAf3sRCW3qSinyC"})
  void testAcceptsLegacyBitcoinAddresses(String text) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_BTC_ADDRESS, mentions.get(0).type());
    Assertions.assertEquals(text, mentions.get(0).normalized());
    Assertions.assertEquals(0, mentions.get(0).span().getStart());
    Assertions.assertEquals(text.length(), mentions.get(0).span().getEnd());
  }

  /**
   * Rejects addresses with an altered checksum despite valid length and alphabet.
   *
   * @param text The invalid address.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb",
      "1NdB761LmTmrJixxp93nz7pEhLP3fTHG5N",
      "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN3",
      "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLz",
      "1BoatSLRHtKNngkdXEeobR76b53LETtpyU"})
  void testRejectsLegacyBitcoinAddressesWithABrokenChecksum(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Rejects invalid encodings, adjoining identifiers and Bitcoin testnet addresses.
   *
   * @param text The excluded candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "1A1zP1eP5QGefi2DMPTfTL5SLmv7Divf",
      "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNaXX",
      "2A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
      "1A1zP1eP5QGefi2DMPT0TL5SLmv7DivfNa",
      "1A1zP1eP5QGefi2DMPTfTL5SLmvIDivfNa",
      "x1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
      "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"})
  void testRejectsLegacyBitcoinNearMisses(String text) {
    Assertions.assertTrue(
        extractor.extract(text).stream()
            .noneMatch(m -> PiiMention.TYPE_BTC_ADDRESS.equals(m.type())), text);
  }

  /**
   * Checks mainnet bech32 and bech32m addresses.
   *
   * @param text The address candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
      "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
      "bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3",
      "bc1pmfr3p9j00pfxjh0zmgp99y8zftmd3s5pmedqhyptwy6lm87hf5sspknck9",
      "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"})
  void testAcceptsSegwitBitcoinAddresses(String text) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_BTC_ADDRESS, mentions.get(0).type());
    Assertions.assertEquals(text, mentions.get(0).normalized());
  }

  /** Checks normalization of uppercase segwit text. */
  @Test
  void testUppercaseSegwitAddressNormalizesToLowercase() {
    final String address = "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4";
    final List<PiiMention> mentions = extractor.extract(address);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(StringUtil.toLowerCase(address), mentions.get(0).normalized());
  }

  /**
   * Rejects invalid segwit checksums, mixed case, lengths and network prefixes.
   *
   * @param text The excluded address candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5",
      "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t",
      "bc1Qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
      "bc1qqqqsyydq4q",
      "bc1pqqq3g00lg3",
      "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx",
      "bc1q",
      "bc1",
      "bcqw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
      "xbc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"})
  void testRejectsSegwitBitcoinNearMisses(String text) {
    Assertions.assertTrue(
        extractor.extract(text).stream()
            .noneMatch(m -> PiiMention.TYPE_BTC_ADDRESS.equals(m.type())), text);
  }

  /**
   * Checks the example addresses from <a href="https://eips.ethereum.org/EIPS/eip-55">
   * EIP-55</a>, including single-case forms.
   *
   * @param text The example address.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
      "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
      "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
      "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
      "0x52908400098527886E0F7030069857D2E4169EE7",
      "0x8617E340B3D01FA5F11F306F4090FD50E238070D",
      "0xde709f2102306220921060314715629080e2fb77",
      "0x27b1fdb04752bbc536007a920d24acb045561c26"})
  void testAcceptsEthereumAddresses(String text) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_ETH_ADDRESS, mentions.get(0).type());
    Assertions.assertEquals(0, mentions.get(0).span().getStart());
    Assertions.assertEquals(text.length(), mentions.get(0).span().getEnd());
  }

  /**
   * Checks capitalization normalization across accepted display forms.
   *
   * @param text The original address text.
   * @param normalized The expected EIP-55 form.
   */
  @ParameterizedTest
  @CsvSource({
      "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed, 0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
      "0x5AAEB6053F3E94C9B9A09F33669435E7EF1BEAED, 0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
      "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed, 0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
      "0X5aaeb6053f3e94c9b9a09f33669435e7ef1beaed, 0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
      "0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359, 0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
  })
  void testEthereumAddressNormalizesToTheEip55Form(String text, String normalized) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(normalized, mentions.get(0).normalized());
  }

  /**
   * Rejects mixed-case candidates with incorrect checksum capitalization.
   *
   * @param text The invalid address.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD",
      "0x5AAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
      "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5D359",
      "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6Fb"})
  void testRejectsEthereumAddressesWithABrokenChecksum(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Rejects invalid prefixes, lengths, alphabets and the Ethereum zero address.
   *
   * @param text The excluded candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beae",
      "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaedd",
      "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaeg",
      "5aaeb6053f3e94c9b9a09f33669435e7ef1beaed",
      "0x0000000000000000000000000000000000000000",
      "x0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed",
      "0y5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"})
  void testRejectsEthereumNearMisses(String text) {
    Assertions.assertTrue(
        extractor.extract(text).stream()
            .noneMatch(m -> PiiMention.TYPE_ETH_ADDRESS.equals(m.type())), text);
  }

  /** Checks original-text spans for both address types in a sentence. */
  @Test
  void testSpansInSentence() {
    final String text = "Send to 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa or "
        + "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed, thanks.";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(2, mentions.size());
    Assertions.assertEquals("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", text.substring(
        mentions.get(0).span().getStart(), mentions.get(0).span().getEnd()));
    Assertions.assertEquals("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", text.substring(
        mentions.get(1).span().getStart(), mentions.get(1).span().getEnd()));
  }

  /** Checks scanning continues after the first address. */
  @Test
  void testTwoAddressesSideBySideAreBothFound() {
    final String text = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa 3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(2, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_BTC_ADDRESS, mentions.get(0).type());
    Assertions.assertEquals(PiiMention.TYPE_BTC_ADDRESS, mentions.get(1).type());
  }

  /** Checks extraction is limited to selected address types. */
  @Test
  void testTypeSubsetLimitsWhatIsReported() {
    final String text = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa and "
        + "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed";

    Assertions.assertEquals(List.of(PiiMention.TYPE_BTC_ADDRESS),
        new CryptoPiiExtractor(Set.of(PiiMention.TYPE_BTC_ADDRESS)).extract(text)
            .stream().map(PiiMention::type).toList());
    Assertions.assertEquals(List.of(PiiMention.TYPE_ETH_ADDRESS),
        new CryptoPiiExtractor(Set.of(PiiMention.TYPE_ETH_ADDRESS)).extract(text)
            .stream().map(PiiMention::type).toList());
  }

  /**
   * Checks empty input, ordinary text and incomplete candidates.
   *
   * @param text The input without a supported address.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "no address here",
      "commit 8f14e45fceea167a5a36dedd4bea2543",
      "0x1234",
      "1234567890",
      "",
      "13 items in 3 boxes"})
  void testTextWithoutAnAddressYieldsNoMention(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Rejects a 40-digit Git object name without an Ethereum prefix.
   */
  @Test
  void testGitObjectNameIsNotAnAddress() {
    Assertions.assertTrue(
        extractor.extract("commit da39a3ee5e6b4b0d3255bfef95601890afd80709").isEmpty());
  }

  /** Checks constructor and extraction argument validation. */
  @Test
  void testRejectsUnrecognizedTypeAndMissingArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CryptoPiiExtractor(Set.of(PiiMention.TYPE_EMAIL)));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CryptoPiiExtractor(Set.of()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CryptoPiiExtractor(null));
    Assertions.assertThrows(IllegalArgumentException.class, () -> extractor.extract(null));
  }
}
