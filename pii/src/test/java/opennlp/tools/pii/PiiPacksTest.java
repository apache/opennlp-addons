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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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

public class PiiPacksTest {

  /** Input containing all supported types; credential tokens are synthetic. */
  private static final String EVERYTHING = "mail jane@example.com call (555) 123-4567 "
      + "iban DE89 3704 0044 0532 0130 00 card 4111 1111 1111 1111 routing 021000021 "
      + "host 10.1.2.3 peer 2001:db8::1 mac 00:1b:44:11:3a:b7 "
      + "key AKIAIOSFODNN7EXAMPLE url https://u:p@example.com/ "
      + "github ghp_" + "a".repeat(36) + " jwt eyJhbGciOiJIUzI1NiJ9.e30.YQ "
      + "btc 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa eth 0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed "
      + "ssn 123-45-6789 itin 900-70-1234 nhs 943 476 5919 idnr 65929970489 "
      + "SIN: 046 454 286 IMEI: 490154203237518";

  /**
   * Provides the named detector factories.
   *
   * @return The pack names and factories.
   */
  private static Stream<Arguments> packs() {
    return Stream.of(
        Arguments.of("payment", (Supplier<PiiExtractor>) PiiPacks::payment),
        Arguments.of("contact", (Supplier<PiiExtractor>) PiiPacks::contact),
        Arguments.of("network", (Supplier<PiiExtractor>) PiiPacks::network),
        Arguments.of("secrets", (Supplier<PiiExtractor>) PiiPacks::secrets),
        Arguments.of("crypto", (Supplier<PiiExtractor>) PiiPacks::crypto),
        Arguments.of("usIdentity", (Supplier<PiiExtractor>) PiiPacks::usIdentity),
        Arguments.of("euIdentity", (Supplier<PiiExtractor>) PiiPacks::euIdentity),
        Arguments.of("caIdentity", (Supplier<PiiExtractor>) PiiPacks::caIdentity),
        Arguments.of("device", (Supplier<PiiExtractor>) PiiPacks::device),
        Arguments.of("allStructured", (Supplier<PiiExtractor>) PiiPacks::allStructured));
  }

  /**
   * Checks the documented start, end and type ordering.
   *
   * @param name The pack name.
   * @param pack The extractor factory.
   */
  @ParameterizedTest
  @MethodSource("packs")
  void testEveryPackReportsMentionsInDefinedOrder(String name,
      Supplier<PiiExtractor> pack) {
    final List<PiiMention> mentions = pack.get().extract(EVERYTHING);

    Assertions.assertFalse(mentions.isEmpty(), name);
    final List<PiiMention> ordered = new ArrayList<>(mentions);
    ordered.sort(Comparator
        .comparingInt((PiiMention mention) -> mention.span().getStart())
        .thenComparing((first, second) -> Integer.compare(
            second.span().getEnd(), first.span().getEnd()))
        .thenComparingInt(mention -> PiiTypePriority.rank(mention.type())));
    Assertions.assertEquals(ordered, mentions, name);
  }

  @ParameterizedTest
  @MethodSource("packs")
  void testEveryPackIsFreshAndFindsNothingInPlainText(String name,
      Supplier<PiiExtractor> pack) {
    Assertions.assertNotSame(pack.get(), pack.get(), name);
    Assertions.assertTrue(pack.get().extract("nothing to find in this sentence").isEmpty(),
        name);
  }

  @ParameterizedTest
  @MethodSource("packs")
  void testEveryPackRejectsNullText(String name, Supplier<PiiExtractor> pack) {
    final PiiExtractor extractor = pack.get();
    Assertions.assertThrows(IllegalArgumentException.class, () -> extractor.extract(null),
        name);
  }

  @Test
  void testPaymentPackReportsPaymentTypesOnly() {
    final Set<String> types = typesOf(PiiPacks.payment());

    Assertions.assertEquals(Set.of(PiiMention.TYPE_IBAN, PiiMention.TYPE_CARD,
        PiiMention.TYPE_ABA_ROUTING), types);
  }

  @Test
  void testContactPackReportsContactTypesOnly() {
    Assertions.assertEquals(Set.of(PiiMention.TYPE_EMAIL, PiiMention.TYPE_PHONE),
        typesOf(PiiPacks.contact()));
  }

  @Test
  void testNetworkPackReportsAddressTypesOnly() {
    Assertions.assertEquals(
        Set.of(PiiMention.TYPE_IPV4, PiiMention.TYPE_IPV6, PiiMention.TYPE_MAC),
        typesOf(PiiPacks.network()));
  }

  /** Checks all supported credential types in the fixture. */
  @Test
  void testSecretsPackReportsCredentialTypesOnly() {
    Assertions.assertEquals(
        Set.of(PiiMention.TYPE_AWS_ACCESS_KEY, PiiMention.TYPE_URL_CREDENTIAL,
            PiiMention.TYPE_GITHUB_TOKEN, PiiMention.TYPE_JWT),
        typesOf(PiiPacks.secrets()));
  }

  @Test
  void testCryptoPackReportsWalletTypesOnly() {
    Assertions.assertEquals(
        Set.of(PiiMention.TYPE_BTC_ADDRESS, PiiMention.TYPE_ETH_ADDRESS),
        typesOf(PiiPacks.crypto()));
  }

  @Test
  void testUsIdentityPackReportsUnitedStatesTypesOnly() {
    Assertions.assertEquals(Set.of(PiiMention.TYPE_US_SSN, PiiMention.TYPE_US_ITIN),
        typesOf(PiiPacks.usIdentity()));
  }

  @Test
  void testEuIdentityPackReportsEuropeanTypesOnly() {
    Assertions.assertEquals(Set.of(PiiMention.TYPE_UK_NHS, PiiMention.TYPE_DE_STEUER_ID),
        typesOf(PiiPacks.euIdentity()));
  }

  @Test
  void testCaIdentityPackReportsCanadianTypesOnly() {
    Assertions.assertEquals(Set.of(PiiMention.TYPE_CA_SIN),
        typesOf(PiiPacks.caIdentity()));
  }

  @Test
  void testDevicePackReportsDeviceTypesOnly() {
    Assertions.assertEquals(Set.of(PiiMention.TYPE_IMEI), typesOf(PiiPacks.device()));
  }

  /**
   * Checks that the fixture exercises all public type constants.
   *
   * @throws IllegalAccessException If a public constant cannot be read.
   */
  @Test
  void testAllStructuredPackReportsEveryType() throws IllegalAccessException {
    final Set<String> types = typesOf(PiiPacks.allStructured());

    Assertions.assertEquals(PiiTestSupport.declaredTypes(), types);
  }

  /**
   * Checks a shared built-in pack with distinct texts on concurrent threads.
   *
   * @param name The pack name.
   * @param pack The extractor factory.
   * @throws Exception If a worker fails or does not finish within the time limit.
   */
  @ParameterizedTest
  @MethodSource("packs")
  @Timeout(60)
  void testConcurrentPack(String name, Supplier<PiiExtractor> pack) throws Exception {
    final PiiExtractor shared = pack.get();
    final CountDownLatch ready = new CountDownLatch(4);
    final List<Callable<Void>> calls = new ArrayList<>();
    for (int worker = 0; worker < 4; worker++) {
      final String text = "😀 ".repeat(worker + 1) + EVERYTHING;
      final List<PiiMention> expected = shared.extract(text);
      Assertions.assertFalse(expected.isEmpty(), name);
      calls.add(() -> {
        ready.countDown();
        Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
        for (int run = 0; run < 16; run++) {
          Assertions.assertEquals(expected, shared.extract(text), name);
          Assertions.assertEquals(expected, shared.extract(new StringBuilder(text)), name);
        }
        return null;
      });
    }
    try (var executor = Executors.newFixedThreadPool(4)) {
      for (final var result : executor.invokeAll(calls, 30, TimeUnit.SECONDS)) {
        result.get();
      }
    }
  }

  /**
   * Checks IMEI and card retention when payment and device packs overlap.
   *
   * @param reverse Whether to reverse the pack order.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testImeiPriorityExample(boolean reverse) {
    final PiiExtractor selected = reverse
        ? new CompositePiiExtractor(PiiPacks.device(), PiiPacks.payment())
        : new CompositePiiExtractor(PiiPacks.payment(), PiiPacks.device());
    final Document deviceDocument = new PiiAnnotator(selected)
        .annotate(Document.of("IMEI: 490154203237518."));

    Assertions.assertEquals(List.of(
        new PiiMention(new Span(6, 21), PiiMention.TYPE_IMEI, "490154203237518"),
        new PiiMention(new Span(6, 21), PiiMention.TYPE_CARD, "490154203237518")),
        deviceDocument.get(PiiAnnotator.PII).stream()
        .map(annotation -> annotation.value()).toList());
    Assertions.assertEquals("IMEI: ***************.",
        Masker.mask(deviceDocument, PiiAnnotator.PII, '*'));
  }

  /**
   * Verifies the promise that matters most: the default extractor never reports a national
   * identifier, a credential, or an address, whatever the text holds.
   */
  @Test
  void testDefaultExtractorReportsOnlyTheFourClassicTypes() {
    final Set<String> types = typesOf(new CursorPiiExtractor());

    Assertions.assertEquals(Set.of(PiiMention.TYPE_EMAIL, PiiMention.TYPE_PHONE,
        PiiMention.TYPE_IBAN, PiiMention.TYPE_CARD), types);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "ssn 123-45-6789",
      "itin 900-70-1234",
      "idnr 65929970489",
      "SIN: 046 454 286",
      "IMEI: 490154203237518",
      "routing 021000021",
      "key AKIAIOSFODNN7EXAMPLE",
      "btc 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"})
  void testNarrowPacksDoNotReportWhatTheyDoNotCover(String text) {
    Assertions.assertTrue(PiiPacks.contact().extract(text).isEmpty(), text);
  }

  @Test
  void testPacksCombineIntoOneComposite() {
    final PiiExtractor extractor =
        new CompositePiiExtractor(PiiPacks.contact(), PiiPacks.network());
    final String text = "mail jane@example.com from 10.1.2.3";

    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(List.of(PiiMention.TYPE_EMAIL, PiiMention.TYPE_IPV4),
        mentions.stream().map(PiiMention::type).toList());
  }

  /** Checks that repeating a pack does not repeat equal mentions. */
  @Test
  void testRepeatingAPackDoesNotInflateResults() {
    final String text = "mail jane@example.com call (555) 123-4567";
    final List<PiiMention> expected = PiiPacks.contact().extract(text);

    Assertions.assertEquals(expected,
        new CompositePiiExtractor(PiiPacks.contact(), PiiPacks.contact()).extract(text));
  }

  /**
   * Verifies that a broader search retains all exact mentions reported by a narrow pack.
   */
  @ParameterizedTest
  @MethodSource("packs")
  void testAllStructuredRetainsEveryMentionTheNarrowPacksReport(String name,
      Supplier<PiiExtractor> pack) {
    final List<PiiMention> wide = PiiPacks.allStructured().extract(EVERYTHING);

    for (final PiiMention mention : pack.get().extract(EVERYTHING)) {
      Assertions.assertTrue(wide.contains(mention), name + ": " + mention);
    }
  }

  /** Checks that a shared span retains both NHS-number and phone interpretations. */
  @Test
  void testWidePackRetainsBothTypesOnASharedSpan() {
    final String text = "record 943 476 5919 today";

    Assertions.assertEquals(PiiMention.TYPE_PHONE,
        PiiPacks.contact().extract(text).get(0).type());
    Assertions.assertEquals(List.of(PiiMention.TYPE_UK_NHS, PiiMention.TYPE_PHONE),
        PiiPacks.allStructured().extract(text).stream().map(PiiMention::type).toList());
  }

  /**
   * Extracts the fixture's mention types.
   *
   * @param extractor The extractor under test.
   * @return The reported type set.
   */
  private Set<String> typesOf(PiiExtractor extractor) {
    return extractor.extract(EVERYTHING).stream().map(PiiMention::type)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }
}
