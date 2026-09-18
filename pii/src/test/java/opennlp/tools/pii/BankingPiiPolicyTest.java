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
import java.util.Set;
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

/** Checks routing-prefix policy using synthetic, checksum-valid numbers. */
class BankingPiiPolicyTest {

  /**
   * Prefixes allocated by the
   * <a href="https://www.aba.com/news-research/analysis-guides/routing-number-policy-procedures">
   * ABA Routing Number Policy, section IV</a>.
   */
  private static final Set<Integer> PREFIXES = Set.of(
      0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
      21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
      61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 80);

  private static final String GOVERNMENT_EXAMPLE = "001234561";

  private final PiiExtractor extractor = new BankingPiiExtractor();

  /**
   * Provides all two-digit prefixes with two synthetic institution identifiers.
   *
   * @return The generated number and whether its prefix is allocated.
   */
  private static Stream<Arguments> routingPrefixes() {
    final List<Arguments> cases = new ArrayList<>();
    for (int prefix = 0; prefix < 100; prefix++) {
      for (final String body : List.of("123456", "987654")) {
        cases.add(Arguments.of(routingNumber(prefix, body), PREFIXES.contains(prefix)));
      }
    }
    return cases.stream();
  }

  /**
   * Provides one changed digit at each position for each allocated prefix.
   *
   * @return The valid number, changed position and invalid number.
   */
  private static Stream<Arguments> digitChanges() {
    final List<Arguments> cases = new ArrayList<>();
    for (final int prefix : PREFIXES.stream().sorted().toList()) {
      final String value = routingNumber(prefix, "123456");
      for (int index = 0; index < value.length(); index++) {
        final StringBuilder changed = new StringBuilder(value);
        changed.setCharAt(index, (char) ('0' + (value.charAt(index) - '0' + 1) % 10));
        cases.add(Arguments.of(value, index, changed.toString()));
      }
    }
    return cases.stream();
  }

  /**
   * Appends the check digit using the complementary 7, 3, 9 weights.
   *
   * @param prefix The two-digit routing prefix.
   * @param body The six remaining digits before the check digit.
   * @return The synthetic routing number, without a claim of actual assignment.
   */
  private static String routingNumber(int prefix, String body) {
    final String value = (prefix < 10 ? "0" : "") + prefix + body;
    final int a = value.charAt(0) + value.charAt(3) + value.charAt(6) - 3 * '0';
    final int b = value.charAt(1) + value.charAt(4) + value.charAt(7) - 3 * '0';
    final int c = value.charAt(2) + value.charAt(5) - 2 * '0';
    return value + (7 * a + 3 * b + 9 * c) % 10;
  }

  /**
   * Checks every prefix against the policy, independently of the production range table.
   *
   * @param number The generated number with a valid check digit.
   * @param allocated Whether the policy allocates its prefix.
   */
  @ParameterizedTest
  @MethodSource("routingPrefixes")
  void testPrefixAllocation(String number, boolean allocated) {
    final List<PiiMention> expected = allocated
        ? List.of(new PiiMention(new Span(0, number.length()), PiiMention.TYPE_ABA_ROUTING, number))
        : List.of();

    Assertions.assertEquals(expected, extractor.extract(number));
  }

  /**
   * Checks that a changed digit fails checksum validation, including the government range.
   *
   * @param valid The original checksum-valid number.
   * @param index The changed digit position.
   * @param invalid The number after changing the digit.
   */
  @ParameterizedTest(name = "{0}, changed position={1}")
  @MethodSource("digitChanges")
  void testRejectsChangedDigits(String valid, int index, String invalid) {
    Assertions.assertEquals(1, extractor.extract(valid).size());
    Assertions.assertTrue(extractor.extract(invalid).isEmpty(), "position " + index);
  }

  /**
   * Checks the manual example through standalone, payment and complete extractors.
   *
   * @param pack The extractor configuration.
   */
  @ParameterizedTest
  @ValueSource(strings = {"standalone", "payment", "all"})
  void testGovernmentRoutingInDocument(String pack) {
    Assertions.assertEquals(GOVERNMENT_EXAMPLE, routingNumber(0, "123456"));
    final PiiExtractor selected = switch (pack) {
      case "standalone" -> extractor;
      case "payment" -> PiiPacks.payment();
      case "all" -> PiiPacks.allStructured();
      default -> throw new AssertionError(pack);
    };
    final Document source = Document.of("Routing " + GOVERNMENT_EXAMPLE + ".");
    final Document result = new PiiAnnotator(selected).annotate(source);

    Assertions.assertEquals(List.of(new PiiMention(new Span(8, 17),
        PiiMention.TYPE_ABA_ROUTING, GOVERNMENT_EXAMPLE)),
        result.get(PiiAnnotator.PII).stream().map(annotation -> annotation.value()).toList());
    Assertions.assertEquals("Routing *********.", Masker.mask(result, PiiAnnotator.PII, '*'));
    Assertions.assertEquals("Routing ABA-ROUTING-1.", new Pseudonymizer().rewrite(result).text());
    Assertions.assertTrue(source.layers().isEmpty());
  }

  /**
   * Checks text boundaries, including supplementary letters and digits.
   *
   * @param adjacent The adjoining character or numeric continuation.
   */
  @ParameterizedTest
  @ValueSource(strings = {"x", "é", "𐐀", "𝟙", "١", "1", "1.", "1,"})
  void testRejectsNumericAndWordContinuations(String adjacent) {
    Assertions.assertTrue(extractor.extract(adjacent + GOVERNMENT_EXAMPLE).isEmpty());
    final String suffix = new StringBuilder(adjacent).reverse().toString();
    Assertions.assertTrue(extractor.extract(GOVERNMENT_EXAMPLE + suffix).isEmpty());
  }

  /**
   * Checks exact offsets after punctuation, spaces and supplementary symbols.
   *
   * @param prefix The text before the routing number.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", " ", "(", "😀 ", "𝕒 ", "e\u0301 "})
  void testKeepsOriginalOffsets(String prefix) {
    final String text = prefix + GOVERNMENT_EXAMPLE + ")";
    Assertions.assertEquals(List.of(new PiiMention(
        new Span(prefix.length(), prefix.length() + GOVERNMENT_EXAMPLE.length()),
        PiiMention.TYPE_ABA_ROUTING, GOVERNMENT_EXAMPLE)), extractor.extract(text));
  }

  /**
   * Keeps all-zero values, separated digits and overlong numeric runs out of the results.
   *
   * @param text The invalid numeric text.
   */
  @ParameterizedTest
  @ValueSource(strings = {"000000000", "0000000000", "000 000 000", "00-1234561",
      "0012345610", "0001234561", "001 234 561", "00", "", "001234561.5", "1,001234561"})
  void testRejectsInvalidNumericForms(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty());
  }

  /**
   * Checks shared extraction using different input text for each concurrent worker.
   *
   * @throws Exception If a worker fails or does not finish in time.
   */
  @Test
  @Timeout(60)
  void testConcurrentRoutingExtraction() throws Exception {
    final CountDownLatch ready = new CountDownLatch(8);
    final List<Callable<Void>> calls = new ArrayList<>();
    for (int worker = 0; worker < 8; worker++) {
      final String number = routingNumber(worker, "123456");
      final String text = "😀 " + number + ".";
      final List<PiiMention> expected = List.of(new PiiMention(new Span(3, 12),
          PiiMention.TYPE_ABA_ROUTING, number));
      calls.add(() -> {
        ready.countDown();
        Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
        for (int run = 0; run < 32; run++) {
          Assertions.assertEquals(expected, extractor.extract(text));
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
