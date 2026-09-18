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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Document;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

/** Checks address encodings, original-text spans and normalized values. */
class CryptoAddressTest {

  private static final CryptoPiiExtractor EXTRACTOR = new CryptoPiiExtractor();
  private static final String LEGACY = "11111111111111111111BZbvjr";
  private static final String SEGWIT = "bc1pqqqqqcnjqs";
  private static final String ETH = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed";

  /**
   * Provides the display and normalized forms used by boundary tests.
   *
   * @return The address text, type and normalized value.
   */
  private static Stream<Arguments> addresses() {
    return Stream.of(
        Arguments.of(LEGACY, PiiMention.TYPE_BTC_ADDRESS, LEGACY),
        Arguments.of(SEGWIT, PiiMention.TYPE_BTC_ADDRESS, SEGWIT),
        Arguments.of(StringUtil.toUpperCase(SEGWIT), PiiMention.TYPE_BTC_ADDRESS, SEGWIT),
        Arguments.of(ETH, PiiMention.TYPE_ETH_ADDRESS, ETH),
        Arguments.of(StringUtil.toLowerCase(ETH), PiiMention.TYPE_ETH_ADDRESS, ETH),
        Arguments.of(StringUtil.toUpperCase(ETH), PiiMention.TYPE_ETH_ADDRESS, ETH));
  }

  /**
   * Provides permitted punctuation and whitespace around each display form.
   *
   * @return The address text, type, normalized value and delimiter.
   */
  private static Stream<Arguments> delimiters() {
    return addresses().flatMap(address -> Stream.of("", " ", "\t", "\n", "\u00a0", "😀",
        "\"", "'", ",", ";", ":", "(", ")", "[", "]", "_", "-", ".", "\u0000")
        .map(delimiter -> Arguments.of(address.get()[0], address.get()[1], address.get()[2], delimiter)));
  }

  /**
   * Checks complete spans and normalization independently of surrounding punctuation.
   *
   * @param address The display form.
   * @param type The expected address type.
   * @param normalized The expected normalized value.
   * @param delimiter The surrounding delimiter.
   */
  @ParameterizedTest
  @MethodSource("delimiters")
  void testDelimiter(String address, String type, String normalized, String delimiter) {
    Assertions.assertEquals(List.of(new PiiMention(
        new Span(delimiter.length(), delimiter.length() + address.length()), type, normalized)),
        EXTRACTOR.extract(delimiter + address + delimiter));
  }

  /**
   * Provides adjoining letters and digits, including supplementary code points.
   *
   * @return The address text and adjoining identifier character.
   */
  private static Stream<Arguments> wordBoundaries() {
    return addresses().flatMap(address -> Stream.of("a", "Z", "0", "é", "中", "𐐀", "١", "𝟙")
        .map(character -> Arguments.of(address.get()[0], character)));
  }

  /**
   * Rejects partial matches inside runs of letters or digits.
   *
   * @param address The candidate address.
   * @param character The adjoining letter or digit.
   */
  @ParameterizedTest
  @MethodSource("wordBoundaries")
  void testWordBoundary(String address, String character) {
    Assertions.assertTrue(EXTRACTOR.extract(character + address).isEmpty());
    Assertions.assertTrue(EXTRACTOR.extract(address + character).isEmpty());
  }

  /**
   * Checks all witness versions using synthetic 20-byte zero programs.
   * Checksums follow <a href="https://github.com/bitcoin/bips/blob/master/bip-0350.mediawiki">
   * BIP-350</a>.
   *
   * @param address The encoded zero program.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "bc1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9e75rs",
      "bc1pqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqmmente",
      "bc1zqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqxsfwuy",
      "bc1rqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdw7930",
      "bc1yqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq4xqamh",
      "bc19qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq7chkku",
      "bc1xqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqrn8tpp",
      "bc18qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqgdsqv2",
      "bc1gqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq6rjj4c",
      "bc1fqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq3a9ecn",
      "bc12qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqvk4y0w",
      "bc1tqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq8gz0z9",
      "bc1vqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqlquhga",
      "bc1dqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq57tu9k",
      "bc1wqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqf4mpjt",
      "bc10qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqztv2lq",
      "bc1sqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqyflvfx"})
  void testWitnessVersions(String address) {
    Assertions.assertEquals(List.of(address), EXTRACTOR.extract(address).stream()
        .map(PiiMention::normalized).toList());
    Assertions.assertEquals(List.of(address), EXTRACTOR.extract(StringUtil.toUpperCase(address)).stream()
        .map(PiiMention::normalized).toList());
  }

  /**
   * Checks valid payload limits and leading zero bytes with synthetic programs and hashes.
   *
   * @param address The checksum-valid address.
   */
  @ParameterizedTest
  @ValueSource(strings = {LEGACY, "1111111111111111111114oLvT2",
      "112D2adLM3UKy4Z4giRbReR6gjWuvHUqB", "31h1vYVSYuKP6AhS86fbRdMw9XHieotbST",
      "31h38a54tFMrR8kzBnP2241MFD2EUHtGha", "31h1vYVSYuKP6AhS86fbRdMw9XHiiQ93Mb",
      SEGWIT, "bc1sqqqqkfw08p", "bc1pqqqqqkzsz3c",
      "bc1pqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq2f6xj9",
      "bc1sqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqrue65v",
      "bc1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqthqst8"})
  void testPayloadLimits(String address) {
    Assertions.assertEquals(List.of(new PiiMention(new Span(0, address.length()),
        PiiMention.TYPE_BTC_ADDRESS, address)), EXTRACTOR.extract(address));
  }

  /**
   * Rejects checksum-valid encodings with invalid versions, program lengths or padding,
   * and encodings using the wrong checksum for the witness version.
   *
   * @param address The synthetic invalid address.
   */
  @ParameterizedTest
  @ValueSource(strings = {"bc1qqqqq399cqn", "bc1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqmql8k8",
      "bc1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqj9pecr",
      "bc1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqpqy20t",
      "bc1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq88p3kr",
      "bc1pqqlppvpg",
      "bc1pqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqepcyyg",
      "bc13qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq0hg8yd",
      "bc1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqs9wcxj",
      "bc1pqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqw8flwm", "bc1pqqqpaw88az", "bc1pqqqqqq90twsu"})
  void testInvalidWitnessEncoding(String address) {
    Assertions.assertTrue(EXTRACTOR.extract(address).isEmpty());
  }

  /** Checks the manual example masks original uppercase text using its original offsets. */
  @Test
  void testManualExample() {
    final String address = "0X5AAEB6053F3E94C9B9A09F33669435E7EF1BEAED";
    final Document result = new PiiAnnotator(PiiPacks.crypto()).annotate(
        Document.of("Address: " + address + "."));
    Assertions.assertEquals(ETH, result.get(PiiAnnotator.PII).getFirst().value().normalized());
    Assertions.assertEquals("Address: " + "*".repeat(address.length()) + ".",
        Masker.mask(result, PiiAnnotator.PII, '*'));
  }

  /**
   * Checks sharing an extractor across simultaneous checksum calculations.
   *
   * @throws Exception If a worker fails or does not finish in time.
   */
  @Test
  @Timeout(60)
  void testConcurrentExtraction() throws Exception {
    final CountDownLatch ready = new CountDownLatch(8);
    final List<Callable<Void>> calls = new ArrayList<>();
    for (int worker = 0; worker < 8; worker++) {
      calls.add(() -> {
        ready.countDown();
        Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
        for (int run = 0; run < 32; run++) {
          Assertions.assertEquals(List.of(LEGACY, SEGWIT, ETH),
              EXTRACTOR.extract(LEGACY + " " + StringUtil.toUpperCase(SEGWIT) + " "
                  + StringUtil.toLowerCase(ETH)).stream().map(PiiMention::normalized).toList());
        }
        return null;
      });
    }
    try (var executor = Executors.newFixedThreadPool(8)) {
      for (final var result : executor.invokeAll(calls, 30, TimeUnit.SECONDS)) {
        result.get();
      }
    }
  }
}
