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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

public class CursorPiiExtractorTest {

  private final CursorPiiExtractor extractor = new CursorPiiExtractor();

  @Test
  void testEmail() {
    final String text = "Write to John.Doe+news@Example.co.uk today.";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size());
    final PiiMention mention = mentions.get(0);
    Assertions.assertEquals(PiiMention.TYPE_EMAIL, mention.type());
    Assertions.assertEquals("John.Doe+news@Example.co.uk", text.substring(
        mention.span().getStart(), mention.span().getEnd()));
    Assertions.assertEquals("John.Doe+news@example.co.uk", mention.normalized());
  }

  /** Verifies that normalization folds only the domain portion of a mailbox. */
  @Test
  void testEmailNormalizationPreservesLocalPartCase() {
    final List<PiiMention> mentions =
        extractor.extract("User@Example.com user@example.com");

    Assertions.assertEquals(List.of("User@example.com", "user@example.com"),
        mentions.stream().map(PiiMention::normalized).toList());
  }

  @Test
  void testEmailExcludesTrailingPunctuation() {
    final List<PiiMention> mentions = extractor.extract("Reach me at jane@example.com.");

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals("jane@example.com", mentions.get(0).normalized());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "no at sign here",
      "broken @example.com address",
      "missing tld user@localhost",
      "doubled dots user..name@example.com",
      "numeric tld user@example.c1"})
  void testEmailRejectsInvalidForms(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty());
  }

  /**
   * Verifies the SMTP mailbox length boundaries: 64 ASCII octets in the local part and
   * 254 characters overall are accepted, while one additional character is rejected.
   */
  @Test
  void testEmailLengthBoundaries() {
    final String localAtLimit = "a".repeat(64);
    final String domainForMaximumMailbox = domainOfLength(189, "com");
    final String maximumMailbox = localAtLimit + "@" + domainForMaximumMailbox;

    Assertions.assertEquals(254, maximumMailbox.length());
    Assertions.assertEquals(1, extractor.extract(maximumMailbox).size());
    Assertions.assertTrue(extractor.extract("a".repeat(65) + "@example.com").isEmpty());
    Assertions.assertTrue(
        extractor.extract(localAtLimit + "@" + domainOfLength(190, "com")).isEmpty());
  }

  /**
   * Verifies that well-formed but unregistered final labels are rejected, including
   * invented TLDs and common private-use suffixes that the IANA root zone does not
   * carry.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "user@example.notatld",
      "user@example.zz",
      "user@files.corp.internal",
      "user@host.local",
      "user@mail.corp",
      "user@box.lan",
      "user@svc.home",
      "user@node.intranet",
      "user@dev.testbed",
      "a@b.ccx",
      "admin@mail.invalidtld",
      "root@example.internal",
      "me@office.localdomain"})
  void testEmailRejectsUnregisteredAndPrivateUseTlds(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Verifies that a domain longer than the RFC 1035 presentation limit is rejected even
   * when every label is well formed and the final label is a registered TLD.
   */
  @ParameterizedTest
  @ValueSource(ints = {254, 255, 300})
  void testEmailRejectsDomainLongerThanRfc1035Limit(int domainLength) {
    final String domain = domainOfLength(domainLength, "com");
    Assertions.assertEquals(domainLength, domain.length());
    Assertions.assertTrue(domainLength > CursorPiiExtractor.DOMAIN_MAX_LENGTH);
    Assertions.assertTrue(extractor.extract("user@" + domain).isEmpty());
  }

  /**
   * Verifies punycode TLDs from the IANA root zone are accepted with exact spans and
   * normalized forms with local-part case preserved and domain case folded.
   */
  @ParameterizedTest
  @CsvSource({
      "user@example.xn--p1ai, user@example.xn--p1ai, 0, 21",
      "Contact USER@EXAMPLE.XN--P1AI please, USER@example.xn--p1ai, 8, 29",
      "mail@site.xn--node now, mail@site.xn--node, 0, 18",
      "a@b.xn--j1amh!, a@b.xn--j1amh, 0, 13"
  })
  void testEmailAcceptsPunycodeTldsWithExactSpans(String text, String normalized,
      int start, int end) {
    final List<PiiMention> mentions = extractor.extract(text);
    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_EMAIL, mentions.get(0).type());
    Assertions.assertEquals(normalized, mentions.get(0).normalized());
    Assertions.assertEquals(start, mentions.get(0).span().getStart());
    Assertions.assertEquals(end, mentions.get(0).span().getEnd());
    final int at = normalized.indexOf('@');
    Assertions.assertEquals(text.substring(start, start + at), normalized.substring(0, at));
    Assertions.assertEquals(
        text.substring(start + at, end).toLowerCase(java.util.Locale.ROOT),
        normalized.substring(at));
  }

  /**
   * Verifies that uppercase and mixed-case registered TLDs still match and normalize
   * to lowercase without the caller pre-folding the domain.
   */
  @ParameterizedTest
  @CsvSource({
      "user@EXAMPLE.COM, user@example.com",
      "user@Example.Org, user@example.org",
      "User@ExAmPle.NeT, User@example.net",
      "a@B.Io, a@b.io",
      "x@Y.AI, x@y.ai",
      "ops@Mail.APP, ops@mail.app",
      "me@Site.INFO, me@site.info",
      "dev@Host.DEV, dev@host.dev"
  })
  void testEmailAcceptsMixedCaseRegisteredTlds(String text, String normalized) {
    final List<PiiMention> mentions = extractor.extract(text);
    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(normalized, mentions.get(0).normalized());
  }

  /**
   * Verifies a sample of ordinary registered ASCII TLDs across short, long, and brand
   * labels so the baked table is exercised beyond {@code com}.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "a@b.co",
      "a@b.com",
      "a@b.org",
      "a@b.net",
      "a@b.edu",
      "a@b.gov",
      "a@b.mil",
      "a@b.int",
      "a@b.io",
      "a@b.ai",
      "a@b.app",
      "a@b.dev",
      "a@b.xyz",
      "a@b.museum",
      "a@b.travel",
      "a@b.photography",
      "a@b.accountant",
      "a@b.americanexpress",
      "mail@corp.uk",
      "mail@corp.de",
      "mail@corp.fr",
      "mail@corp.jp",
      "mail@corp.cn",
      "mail@corp.br",
      "mail@corp.au",
      "mail@corp.za",
      "mail@corp.tw"
  })
  void testEmailAcceptsRegisteredAsciiTlds(String text) {
    final List<PiiMention> mentions = extractor.extract(text);
    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_EMAIL, mentions.get(0).type());
    Assertions.assertEquals(text.toLowerCase(java.util.Locale.ROOT),
        mentions.get(0).normalized());
  }

  /**
   * Verifies that the complete mailbox cap applies before the independent domain cap.
   */
  @Test
  void testEmailRejectsDomainAtRfc1035LimitWhenMailboxIsTooLong() {
    final String domain = domainOfLength(CursorPiiExtractor.DOMAIN_MAX_LENGTH, "com");
    Assertions.assertEquals(CursorPiiExtractor.DOMAIN_MAX_LENGTH, domain.length());
    Assertions.assertTrue(extractor.extract("u@" + domain).isEmpty());
  }

  /**
   * Verifies the mailbox boundary from both sides for domains near the RFC 1035 cap.
   */
  @ParameterizedTest
  @CsvSource({
      "com, 252, true",
      "com, 253, false",
      "com, 254, false",
      "museum, 252, true",
      "museum, 253, false",
      "museum, 254, false",
      "xn--p1ai, 252, true",
      "xn--p1ai, 253, false",
      "xn--p1ai, 254, false"
  })
  void testEmailDomainLengthBoundaryAroundRfc1035Limit(String tld, int length,
      boolean accepted) {
    final String domain = domainOfLength(length, tld);
    Assertions.assertEquals(length, domain.length());
    final List<PiiMention> mentions = extractor.extract("u@" + domain);
    if (accepted) {
      Assertions.assertEquals(1, mentions.size(), domain);
      Assertions.assertEquals("u@" + domain, mentions.get(0).normalized());
    } else {
      Assertions.assertTrue(mentions.isEmpty(), domain);
    }
  }

  /**
   * Direct table checks for {@link IanaTlds}: registered labels hit, unregistered and
   * private-use labels miss, and ASCII case is ignored without requiring a folded copy
   * from the caller.
   */
  @ParameterizedTest
  @CsvSource({
      "COM, true",
      "com, true",
      "Com, true",
      "cOm, true",
      "XN--P1AI, true",
      "xn--p1ai, true",
      "Xn--P1Ai, true",
      "MUSEUM, true",
      "americanexpress, true",
      "NOTATLD, false",
      "zz, false",
      "internal, false",
      "local, false",
      "corp, false",
      "lan, false",
      "localhost, false",
      "COMX, false",
      "CO, true",
      "C, false",
      "AAA, true",
      "ZW, true"
  })
  void testIanaTldsRegisteredLookup(String tld, boolean expected) {
    Assertions.assertEquals(expected, IanaTlds.registered(tld, 0, tld.length()), tld);
  }

  /**
   * Verifies {@link IanaTlds#registered(CharSequence, int, int)} reads a final-label
   * slice out of a longer domain without requiring the caller to allocate a substring.
   */
  @ParameterizedTest
  @CsvSource({
      "example.com, 8, 11, true",
      "EXAMPLE.COM, 8, 11, true",
      "mail.example.xn--p1ai, 13, 21, true",
      "files.corp.internal, 11, 19, false",
      "host.local, 5, 10, false",
      "a.notatld, 2, 9, false"
  })
  void testIanaTldsRegisteredReadsFinalLabelSlice(String domain, int start, int end,
      boolean expected) {
    Assertions.assertEquals(expected, IanaTlds.registered(domain, start, end), domain);
  }

  /**
   * Verifies out-of-range and empty slices are rejected by the table lookup.
   */
  @Test
  void testIanaTldsRegisteredRejectsInvalidSlices() {
    Assertions.assertFalse(IanaTlds.registered("com", -1, 3));
    Assertions.assertFalse(IanaTlds.registered("com", 0, 4));
    Assertions.assertFalse(IanaTlds.registered("com", 2, 1));
    Assertions.assertFalse(IanaTlds.registered("com", 0, 0));
    Assertions.assertFalse(IanaTlds.registered(null, 0, 0));
  }

  /**
   * Builds a dotted domain of exactly {@code length} characters ending in {@code tld},
   * using 63-character labels where needed so every label stays inside the DNS label
   * limit.
   */
  private static String domainOfLength(int length, String tld) {
    if (tld.length() + 2 > length) {
      throw new IllegalArgumentException("length too small for tld " + tld);
    }
    final StringBuilder domain = new StringBuilder(length);
    int remaining = length - tld.length() - 1;
    while (remaining > 0) {
      if (domain.length() > 0) {
        domain.append('.');
        remaining--;
      }
      int labelLength = Math.min(63, remaining);
      // Keep at least one character for a following label when a separator will be needed.
      if (remaining > labelLength && remaining - labelLength == 1) {
        labelLength--;
      }
      if (labelLength < 1) {
        throw new IllegalArgumentException("cannot build domain of length " + length);
      }
      domain.append("a".repeat(labelLength));
      remaining -= labelLength;
    }
    domain.append('.').append(tld);
    if (domain.length() != length) {
      throw new IllegalStateException(
          "built domain length " + domain.length() + " != " + length);
    }
    return domain.toString();
  }

  @Test
  void testPhoneInternational() {
    final List<PiiMention> mentions = extractor.extract("Call +44 20 7946 0958 now.");

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_PHONE, mentions.get(0).type());
    Assertions.assertEquals("+442079460958", mentions.get(0).normalized());
  }

  @ParameterizedTest
  @CsvSource({
      "Call (555) 123-4567 today., 5551234567",
      "Call 555-123-4567 today., 5551234567",
      "Call 1 555 123 4567 today., 15551234567"})
  void testPhoneDomesticFormats(String text, String normalized) {
    Assertions.assertEquals(normalized, extractor.extract(text).get(0).normalized());
  }

  /** Checks an IBAN and the contained phone candidate. */
  @Test
  void testIban() {
    final String text = "Wire it to DE89 3704 0044 0532 0130 00 by Friday.";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(List.of(
        new PiiMention(new Span(11, 38), PiiMention.TYPE_IBAN,
            "DE89370400440532013000"),
        new PiiMention(new Span(26, 38), PiiMention.TYPE_PHONE, "0532013000")),
        mentions);
  }

  /** Verifies that the current SWIFT registry entry for Yemen is recognized. */
  @Test
  void testYemeniIban() {
    final String value = "YE15CBYE0001018861234567891234";
    final List<PiiMention> mentions = extractor.extract(value);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_IBAN, mentions.get(0).type());
    Assertions.assertEquals(value, mentions.get(0).normalized());
  }

  @ParameterizedTest
  @CsvSource({
      "account GB82WEST12345698765432 closed, GB82WEST12345698765432",
      "account GB82 WEST 1234 5698 7654 32 closed, GB82WEST12345698765432"})
  void testIbanCompactAndWithLetters(String text, String normalized) {
    Assertions.assertEquals(normalized, extractor.extract(text).get(0).normalized());
  }

  @Test
  void testIbanRejectsBadChecksum() {
    final List<PiiMention> mentions =
        extractor.extract("account DE89 3704 0044 0532 0130 02 closed");
    Assertions.assertTrue(mentions.stream()
        .noneMatch(m -> PiiMention.TYPE_IBAN.equals(m.type())
            || PiiMention.TYPE_CARD.equals(m.type())));
    Assertions.assertTrue(extractor.extract("plain WORDS IN CAPITALS here").isEmpty());
  }

  /**
   * A candidate can pass the mod-97 check by chance while its length does not match
   * what the ISO 13616 registry assigns to its country, or while its country code is
   * not registered at all. Both fixtures below are mod-97 valid by construction:
   * a 16-character DE candidate where the registry assigns Germany 22, and a
   * ZZ-prefixed candidate with an unregistered country code.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "wire to DE45500105170123 today",
      "wire to ZZ591234567890123456 today"})
  void testIbanRejectsRegistryViolations(String text) {
    Assertions.assertTrue(extractor.extract(text).stream()
        .noneMatch(m -> PiiMention.TYPE_IBAN.equals(m.type())));
  }

  /**
   * Checks registered lengths from Norway's 15 characters through Russia's 33.
   *
   * @param text The text containing the IBAN.
   * @param normalized The compact number.
   */
  @ParameterizedTest
  @CsvSource({
      "wire to NO9386011117947 today, NO9386011117947",
      "wire to MT84MALT011000012345MTLCAST001S today, MT84MALT011000012345MTLCAST001S",
      "wire to FR1420041010050500013M02606 today, FR1420041010050500013M02606",
      "wire to LC55HEMM000100010012001200023015, LC55HEMM000100010012001200023015",
      "wire to RU0304452522540817810538091310419, RU0304452522540817810538091310419"})
  void testIbanRegistryLengthExtremes(String text, String normalized) {
    final List<PiiMention> mentions = extractor.extract(text);
    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_IBAN, mentions.get(0).type());
    Assertions.assertEquals(normalized, mentions.get(0).normalized());
  }

  @Test
  void testCard() {
    final List<PiiMention> mentions =
        extractor.extract("Charged to 4111 1111 1111 1111 yesterday.");

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_CARD, mentions.get(0).type());
    Assertions.assertEquals("4111111111111111", mentions.get(0).normalized());
  }

  @ParameterizedTest
  @CsvSource({
      "card 4111-1111-1111-1111 on file, 4111111111111111",
      "amex 378282246310005 on file, 378282246310005"})
  void testCardAcceptedFormats(String text, String normalized) {
    Assertions.assertEquals(normalized, extractor.extract(text).get(0).normalized());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "card 4111 1111 1111 1112 on file",
      "serial 1111 2222 3333 4444 here"})
  void testCardRejectsFailedChecksumAndUnassignedLeadingDigit(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty());
  }

  /** Checks ordered retention of a contained phone and a later phone. */
  @Test
  void testOverlappingIbanAndPhoneAreRetainedInTextOrder() {
    final List<PiiMention> mentions =
        extractor.extract("Pay DE89 3704 0044 0532 0130 00 or call +1 555 123 4567.");

    Assertions.assertEquals(List.of(
        new PiiMention(new Span(4, 31), PiiMention.TYPE_IBAN,
            "DE89370400440532013000"),
        new PiiMention(new Span(19, 31), PiiMention.TYPE_PHONE, "0532013000"),
        new PiiMention(new Span(40, 55), PiiMention.TYPE_PHONE, "+15551234567")),
        mentions);
  }

  /** Checks that a phone candidate inside an email remains available. */
  @Test
  void testPhoneDigitsInsideEmailAreRetained() {
    final List<PiiMention> mentions =
        extractor.extract("mail +15551234567@voip.example.com please");

    Assertions.assertEquals(List.of(
        new PiiMention(new Span(5, 34), PiiMention.TYPE_EMAIL,
            "+15551234567@voip.example.com"),
        new PiiMention(new Span(5, 17), PiiMention.TYPE_PHONE, "+15551234567")),
        mentions);
  }

  @Test
  void testMentionsComeBackInTextOrder() {
    final List<PiiMention> mentions = extractor.extract(
        "jane@example.com, +44 20 7946 0958, and 4111 1111 1111 1111.");

    Assertions.assertEquals(3, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_EMAIL, mentions.get(0).type());
    Assertions.assertEquals(PiiMention.TYPE_PHONE, mentions.get(1).type());
    Assertions.assertEquals(PiiMention.TYPE_CARD, mentions.get(2).type());
    Assertions.assertTrue(mentions.get(0).span().getEnd() <= mentions.get(1).span().getStart());
    Assertions.assertTrue(mentions.get(1).span().getEnd() <= mentions.get(2).span().getStart());
  }

  @Test
  void testTypeSubsetLimitsWhatIsReported() {
    final String text = "Mail jane@example.com or call (555) 123-4567.";
    final CursorPiiExtractor emailOnly =
        new CursorPiiExtractor(java.util.Set.of(PiiMention.TYPE_EMAIL));

    final List<PiiMention> mentions = emailOnly.extract(text);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_EMAIL, mentions.get(0).type());
  }

  @Test
  void testTypeSubsetValidation() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CursorPiiExtractor(null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CursorPiiExtractor(java.util.Set.of()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CursorPiiExtractor(java.util.Set.of("ssn")));
  }

  @Test
  void testInvalidArguments() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> extractor.extract(null));
  }

  /**
   * Verifies the behavior on an IBAN-shaped candidate whose check digits fail the
   * mod-97 test: no IBAN and no card is reported, and in the spaced form the scan falls
   * through to the formatted ten-digit tail, which qualifies as a domestic phone
   * number. The compact form offers no formatting evidence, so it yields nothing.
   */
  @Test
  void testIbanShapedTextWithBadCheckDigits() {
    final String text = "Wire to DE21 3704 0044 0532 0130 00 today.";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size());
    final PiiMention mention = mentions.get(0);
    Assertions.assertEquals(PiiMention.TYPE_PHONE, mention.type());
    Assertions.assertEquals(23, mention.span().getStart());
    Assertions.assertEquals(35, mention.span().getEnd());
    Assertions.assertEquals("0532 0130 00", text.substring(23, 35));
    Assertions.assertEquals("0532013000", mention.normalized());

    Assertions.assertTrue(extractor.extract("Wire to DE21370400440532013000 today.").isEmpty());
  }

  /**
   * Verifies that the Luhn check alone decides bare sixteen-digit runs: a passing run
   * is reported as a card with its exact span, and flipping the final digit so the
   * checksum fails suppresses the mention entirely.
   */
  @Test
  void testCardBareDigitRunLuhnDecides() {
    final String text = "card 4111111111111111 on file";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_CARD, mentions.get(0).type());
    Assertions.assertEquals(5, mentions.get(0).span().getStart());
    Assertions.assertEquals(21, mentions.get(0).span().getEnd());
    Assertions.assertEquals("4111111111111111", mentions.get(0).normalized());

    Assertions.assertTrue(extractor.extract("card 4111111111111112 on file").isEmpty());
  }

  /**
   * Verifies that an email address is found with its exact span when it is the very
   * first token of the text and when it is the very last, with no character following
   * it.
   */
  @Test
  void testEmailAtTextStartAndEnd() {
    final List<PiiMention> atStart = extractor.extract("jane@example.com wrote back.");
    Assertions.assertEquals(1, atStart.size());
    Assertions.assertEquals(0, atStart.get(0).span().getStart());
    Assertions.assertEquals(16, atStart.get(0).span().getEnd());
    Assertions.assertEquals("jane@example.com", atStart.get(0).normalized());

    final List<PiiMention> atEnd = extractor.extract("Please write to jane@example.com");
    Assertions.assertEquals(1, atEnd.size());
    Assertions.assertEquals(16, atEnd.get(0).span().getStart());
    Assertions.assertEquals(32, atEnd.get(0).span().getEnd());
    Assertions.assertEquals("jane@example.com", atEnd.get(0).normalized());
  }

  /**
   * Verifies that a sentence period directly after an email address stays outside the
   * reported span, checked against the exact offsets and the exact covered text.
   */
  @Test
  void testEmailTrailingSentencePeriodOutsideSpan() {
    final String text = "Ask jane@example.com.";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(4, mentions.get(0).span().getStart());
    Assertions.assertEquals(20, mentions.get(0).span().getEnd());
    Assertions.assertEquals("jane@example.com", text.substring(4, 20));
  }

  /**
   * Verifies accepted phone formats with exact spans: a compact international number
   * with a plus prefix, a hyphen-separated domestic number, and a parenthesized area
   * code at the very start of the text.
   */
  @Test
  void testPhoneAcceptedFormsWithExactSpans() {
    final List<PiiMention> international = extractor.extract("Call +14155550123 now.");
    Assertions.assertEquals(1, international.size());
    Assertions.assertEquals(5, international.get(0).span().getStart());
    Assertions.assertEquals(17, international.get(0).span().getEnd());
    Assertions.assertEquals("+14155550123", international.get(0).normalized());

    final List<PiiMention> domestic = extractor.extract("Call 415-555-0123 today.");
    Assertions.assertEquals(1, domestic.size());
    Assertions.assertEquals(5, domestic.get(0).span().getStart());
    Assertions.assertEquals(17, domestic.get(0).span().getEnd());
    Assertions.assertEquals("4155550123", domestic.get(0).normalized());

    final List<PiiMention> parenthesized = extractor.extract("(415) 555-0123");
    Assertions.assertEquals(1, parenthesized.size());
    Assertions.assertEquals(0, parenthesized.get(0).span().getStart());
    Assertions.assertEquals(14, parenthesized.get(0).span().getEnd());
    Assertions.assertEquals("4155550123", parenthesized.get(0).normalized());
  }

  /**
   * Verifies that a phone mention that ends exactly where the next phone begins does not
   * skip the second candidate: the scan must resume at the exclusive end of the first
   * match, not one character past it.
   */
  @Test
  void testAdjacentPhonesAreBothReported() {
    final List<PiiMention> mentions =
        extractor.extract("(555) 123-4567(555) 123-4568");
    Assertions.assertEquals(2, mentions.size());
    Assertions.assertEquals("5551234567", mentions.get(0).normalized());
    Assertions.assertEquals("5551234568", mentions.get(1).normalized());
  }

  /**
   * Verifies rejected phone-like forms: bare digit runs without any formatting, a
   * separated run with only nine digits, decimal and grouped numbers, and international
   * candidates whose digits admit no split into an assigned calling code and a plausible
   * national length.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "id 5551234567 in the table",
      "Order 4155550123 shipped.",
      "Ref 415-555-012 code.",
      "value 1234567890.25 total",
      "pi is 3.1415926535",
      "+123 is too short",
      "+1234567 is short"})
  void testPhoneRejectedForms(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty());
  }

  /**
   * Verifies that international candidates are judged against the per-calling-code
   * national lengths: a four-digit national number under a code that assigns that
   * length is accepted even though the whole number is short, while a
   * plausible-looking number under an unassigned code and a valid code with a
   * national length no territory assigns are both rejected.
   */
  @Test
  void testPhoneInternationalLengthsFollowCallingCode() {
    final List<PiiMention> shortValid = extractor.extract("Call +683 4002 now.");
    Assertions.assertEquals(1, shortValid.size());
    Assertions.assertEquals(PiiMention.TYPE_PHONE, shortValid.get(0).type());
    Assertions.assertEquals("+6834002", shortValid.get(0).normalized());

    final List<PiiMention> greenland = extractor.extract("Call +299 123456 today.");
    Assertions.assertEquals(1, greenland.size());
    Assertions.assertEquals("+299123456", greenland.get(0).normalized());

    Assertions.assertTrue(extractor.extract("Call +999 1234 5678 now.").isEmpty());
    Assertions.assertTrue(extractor.extract("Call +65 123 456 now.").isEmpty());
  }

  /**
   * Verifies that an IBAN and a contained phone candidate are both retained with their
   * original spans and normalized values.
   */
  @Test
  void testOverlappingIbanAndPhoneCandidates() {
    final List<PiiMention> alone = extractor.extract("num 3704 0044 0532 0130 00 end");
    Assertions.assertEquals(1, alone.size());
    Assertions.assertEquals(PiiMention.TYPE_PHONE, alone.get(0).type());
    Assertions.assertEquals(14, alone.get(0).span().getStart());
    Assertions.assertEquals(26, alone.get(0).span().getEnd());
    Assertions.assertEquals("0532013000", alone.get(0).normalized());

    final List<PiiMention> withIban =
        extractor.extract("Wire to DE89 3704 0044 0532 0130 00 today.");
    Assertions.assertEquals(List.of(
        new PiiMention(new Span(8, 35), PiiMention.TYPE_IBAN,
            "DE89370400440532013000"),
        new PiiMention(new Span(23, 35), PiiMention.TYPE_PHONE, "0532013000")),
        withIban);
  }

  /**
   * Verifies that a card followed by a space-separated expiry date is reported at the
   * exact card span: the greedy candidate swallows the expiry digits and fails the
   * Luhn check, so the scanner must back off to the separator boundary instead of
   * dropping the card. Flipping the final card digit makes every prefix fail the
   * checksum, so nothing may be reported in that case.
   */
  @Test
  void testCardFollowedByExpiryBacksOffToTheCard() {
    final String text = "Card 4111111111111111 12/26";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size());
    final PiiMention mention = mentions.get(0);
    Assertions.assertEquals(PiiMention.TYPE_CARD, mention.type());
    Assertions.assertEquals(5, mention.span().getStart());
    Assertions.assertEquals(21, mention.span().getEnd());
    Assertions.assertEquals("4111111111111111", mention.normalized());
    Assertions.assertEquals("4111111111111111", text.substring(5, 21));

    Assertions.assertTrue(extractor.extract("Card 4111111111111112 12/26").isEmpty());
  }

  /**
   * Verifies that number boundaries are judged in code points: a supplementary-plane
   * letter glued to a digit run continues a word exactly like a basic-plane letter,
   * although its trailing surrogate alone reads as a non-letter, so neither a card
   * nor a phone is reported inside such a word, while a space restores the boundary.
   */
  @Test
  void testSupplementaryLetterNeighborBlocksNumberBoundaries() {
    // U+10428, DESERET SMALL LETTER LONG I, a supplementary-plane letter
    final String letter = "\uD801\uDC28";
    Assertions.assertTrue(extractor.extract(letter + "4111111111111111").isEmpty());
    Assertions.assertTrue(extractor.extract("4111111111111111" + letter).isEmpty());
    Assertions.assertTrue(extractor.extract(letter + "555-123-4567").isEmpty());

    final List<PiiMention> spaced = extractor.extract(letter + " 4111111111111111");
    Assertions.assertEquals(1, spaced.size());
    Assertions.assertEquals(PiiMention.TYPE_CARD, spaced.get(0).type());
    // the supplementary letter is two chars, so the card starts at offset 3
    Assertions.assertEquals(3, spaced.get(0).span().getStart());
  }

  /**
   * Verifies that a separator directly before or after a number candidate stays outside
   * the reported span: a leading hyphen does not join the mention, and a trailing
   * hyphen neither extends nor suppresses it.
   *
   * @param text The text to scan.
   * @param covered The exact text the single reported span must cover.
   */
  @ParameterizedTest
  @CsvSource({
      "call -555-123-4567 now, 555-123-4567",
      "call 555-123-4567- now, 555-123-4567",
      "card 4111 1111 1111 1111- end, 4111 1111 1111 1111"})
  void testSeparatorNeighborsStayOutsideTheSpan(String text, String covered) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(covered, text.substring(
        mentions.get(0).span().getStart(), mentions.get(0).span().getEnd()));
  }

  /**
   * Verifies the multi-boundary backoff path on a card that itself contains
   * separators: the greedy candidate ends in a trailing three-digit group, forming a
   * nineteen-digit candidate that is in range but fails the Luhn check, and the scan
   * then accepts the sixteen-digit candidate at the previous separator boundary. The
   * card uses the original span, and the phone candidate spanning the trailing group is
   * retained separately.
   */
  @Test
  void testSeparatedCardBackoffRetainsTheOverlappingPhone() {
    final String text = "Card 4111 1111 1111 1111 123 on file";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(List.of(
        new PiiMention(new Span(5, 24), PiiMention.TYPE_CARD, "4111111111111111"),
        new PiiMention(new Span(15, 28), PiiMention.TYPE_PHONE, "11111111123")),
        mentions);
  }

  /**
   * Verifies that the phone scan backs off at separator boundaries the way the card
   * scan does: a domestic number followed by another separated digit group would make
   * the greedy candidate twelve digits and be rejected as a whole, so the ten-digit
   * candidate at the previous boundary is accepted, and the trailing group is not
   * swallowed and does not suppress the phone.
   */
  @Test
  void testPhoneFollowedBySeparatedDigitGroupBacksOffToThePhone() {
    final String text = "call 555-123-4567 99 times";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size());
    final PiiMention mention = mentions.get(0);
    Assertions.assertEquals(PiiMention.TYPE_PHONE, mention.type());
    Assertions.assertEquals(5, mention.span().getStart());
    Assertions.assertEquals(17, mention.span().getEnd());
    Assertions.assertEquals("555-123-4567", text.substring(5, 17));
    Assertions.assertEquals("5551234567", mention.normalized());
  }

  /**
   * Verifies the backoff on the parenthesized domestic form: the candidate cut at the
   * separator boundary keeps its balanced parentheses and its visible separation, so
   * the phone is found in front of a trailing digit group.
   */
  @Test
  void testParenthesizedPhoneBacksOffBeforeTrailingGroup() {
    final String text = "dial (212) 555-0199 77 now";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size());
    final PiiMention mention = mentions.get(0);
    Assertions.assertEquals(PiiMention.TYPE_PHONE, mention.type());
    Assertions.assertEquals("(212) 555-0199", text.substring(
        mention.span().getStart(), mention.span().getEnd()));
    Assertions.assertEquals("2125550199", mention.normalized());
  }

  /**
   * Verifies that a nineteen-digit card, the maximum accepted length, still matches as
   * a whole, alone and with a space-separated expiry date after it, so prefix backoff
   * never shortens a candidate that is valid at its full length.
   */
  @Test
  void testNineteenDigitCardMatchesWhole() {
    final List<PiiMention> alone = extractor.extract("card 4111111111111111110 on file");
    Assertions.assertEquals(1, alone.size());
    Assertions.assertEquals(PiiMention.TYPE_CARD, alone.get(0).type());
    Assertions.assertEquals(5, alone.get(0).span().getStart());
    Assertions.assertEquals(24, alone.get(0).span().getEnd());
    Assertions.assertEquals("4111111111111111110", alone.get(0).normalized());

    final List<PiiMention> withExpiry = extractor.extract("card 4111111111111111110 12/26");
    Assertions.assertEquals(1, withExpiry.size());
    Assertions.assertEquals(PiiMention.TYPE_CARD, withExpiry.get(0).type());
    Assertions.assertEquals(5, withExpiry.get(0).span().getStart());
    Assertions.assertEquals(24, withExpiry.get(0).span().getEnd());
    Assertions.assertEquals("4111111111111111110", withExpiry.get(0).normalized());
  }

  /**
   * Verifies that two Luhn-valid cards separated by a single space are reported as two
   * separate mentions: backoff accepts the first card at the separator boundary and
   * the scan resumes after it in time to find the second card.
   */
  @Test
  void testTwoSpaceSeparatedCardsBothReported() {
    final String text = "cards 4111111111111111 5500005555555559 on file";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(2, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_CARD, mentions.get(0).type());
    Assertions.assertEquals(6, mentions.get(0).span().getStart());
    Assertions.assertEquals(22, mentions.get(0).span().getEnd());
    Assertions.assertEquals("4111111111111111", mentions.get(0).normalized());
    Assertions.assertEquals(PiiMention.TYPE_CARD, mentions.get(1).type());
    Assertions.assertEquals(23, mentions.get(1).span().getStart());
    Assertions.assertEquals(39, mentions.get(1).span().getEnd());
    Assertions.assertEquals("5500005555555559", mentions.get(1).normalized());
  }

  /**
   * Verifies that empty text and text without any PII both yield an empty result
   * rather than {@code null} or an exception.
   */
  @Test
  void testEmptyAndPiiFreeText() {
    Assertions.assertTrue(extractor.extract("").isEmpty());
    Assertions.assertTrue(
        extractor.extract("The quick brown fox jumps over the lazy dog.").isEmpty());
  }
}
