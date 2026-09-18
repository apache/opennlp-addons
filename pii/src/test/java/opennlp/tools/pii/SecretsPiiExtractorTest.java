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
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

public class SecretsPiiExtractorTest {

  /** Counts character reads so scanner complexity can be asserted without wall-clock timing. */
  private static final class CountingCharSequence implements CharSequence {

    private final String value;
    private int reads;

    /**
     * Initializes the counted sequence.
     *
     * @param value The wrapped text.
     */
    private CountingCharSequence(String value) {
      this.value = value;
    }

    /** {@inheritDoc} */
    @Override
    public int length() {
      return value.length();
    }

    /** {@inheritDoc} */
    @Override
    public char charAt(int index) {
      reads++;
      return value.charAt(index);
    }

    /** {@inheritDoc} */
    @Override
    public CharSequence subSequence(int start, int end) {
      return value.subSequence(start, end);
    }
  }

  /** The header of {@code {"alg":"HS256","typ":"JWT"}} in base64url. */
  private static final String HS256_HEADER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";

  /** The header of {@code {"alg":"RS256","kid":"abc123"}} in base64url. */
  private static final String RS256_HEADER = "eyJhbGciOiJSUzI1NiIsImtpZCI6ImFiYzEyMyJ9";

  /** The header of {@code {"typ":"JWT"}}, without an algorithm. */
  private static final String NO_ALGORITHM_HEADER = "eyJ0eXAiOiJKV1QifQ";

  /** The header of {@code {"notalg":"HS256"}}, without an exact algorithm member. */
  private static final String NOTALG_HEADER = "eyJub3RhbGciOiJIUzI1NiJ9";

  /** The header of {@code {"algorithm":"HS256"}}, without an exact algorithm member. */
  private static final String ALGORITHM_HEADER = "eyJhbGdvcml0aG0iOiJIUzI1NiJ9";

  /** The header of <code>{"alg" : "HS256"}</code>, with JSON whitespace. */
  private static final String SPACED_HEADER = "eyJhbGciIDogIkhTMjU2In0";

  private static final String PAYLOAD =
      "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ";

  private static final String SIGNATURE = "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

  private final SecretsPiiExtractor extractor = new SecretsPiiExtractor();

  /**
   * Verifies linear scanning for repeated token prefixes.
   *
   * @param prefix The repeated near-miss prefix.
   */
  @ParameterizedTest
  @ValueSource(strings = {"eyJ_", "ghp_"})
  void testPrefixHeavyNearMissesAreScannedLinearly(String prefix) {
    final CountingCharSequence text = new CountingCharSequence(prefix.repeat(1024));

    Assertions.assertTrue(extractor.extract(text).isEmpty());
    Assertions.assertTrue(text.reads <= text.length() * 20,
        () -> "read " + text.reads + " characters from an input of " + text.length());
  }

  /**
   * Checks character reads for repeated URL candidates without relying on timing.
   *
   * @param candidate The repeated incomplete or malformed URL.
   */
  @ParameterizedTest
  @ValueSource(strings = {"s3://", "1http://", "https://u:p%g0@example.invalid "})
  void testUrlCandidateReadCount(String candidate) {
    final CountingCharSequence text = new CountingCharSequence(candidate.repeat(1024));
    Assertions.assertTrue(extractor.extract(text).isEmpty());
    Assertions.assertTrue(text.reads <= text.length() * 40,
        () -> "read " + text.reads + " characters from an input of " + text.length());
  }

  /**
   * Checks complete recognition and linear reads for a long scheme name.
   *
   * @param length The number of letters before the scheme's final digit.
   */
  @ParameterizedTest
  @ValueSource(ints = {64, 1024, 65536})
  void testLongSchemeReadCount(int length) {
    final CountingCharSequence text = new CountingCharSequence(
        "a".repeat(length) + "1://u:pw@example.invalid");
    Assertions.assertEquals(List.of("u:pw"),
        extractor.extract(text).stream().map(PiiMention::normalized).toList());
    Assertions.assertTrue(text.reads <= text.length() * 40,
        () -> "read " + text.reads + " characters from an input of " + text.length());
  }

  /**
   * Checks that dotted candidate prefixes do not cause repeated suffix scans.
   *
   * @param prefix The repeated candidate prefix.
   */
  @ParameterizedTest
  @ValueSource(strings = {"e.e.", "I.I.", "C.C.", "D.D."})
  void testDottedJwtReadCount(String prefix) {
    final CountingCharSequence text = new CountingCharSequence(prefix.repeat(4096));
    Assertions.assertTrue(extractor.extract(text).isEmpty());
    Assertions.assertTrue(text.reads <= text.length() * 40,
        () -> "read " + text.reads + " characters from an input of " + text.length());
  }

  /**
   * Checks recognition and character-read bounds across a long header field.
   *
   * @param length The field length before the algorithm.
   */
  @ParameterizedTest
  @ValueSource(ints = {64, 4096, 65536})
  void testLongJwtReadCount(int length) {
    final String json = "{\"field\":\"" + "x".repeat(length) + "\",\"alg\":\"HS256\"}";
    final CountingCharSequence text = new CountingCharSequence(
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8))
            + "." + PAYLOAD + "." + SIGNATURE);
    Assertions.assertEquals(1, extractor.extract(text).size());
    Assertions.assertTrue(text.reads <= text.length() * 40,
        () -> "read " + text.reads + " characters from an input of " + text.length());
  }

  /**
   * Checks complete installation-token extraction with linear character reads.
   *
   * @param length The number of body groups.
   */
  @ParameterizedTest
  @ValueSource(ints = {128, 4096, 65536})
  void testLongGithubInstallationReadCount(int length) {
    final String value = "ghs_" + "a-_b.c".repeat(length);
    final CountingCharSequence text = new CountingCharSequence(value);
    Assertions.assertEquals(List.of(value),
        extractor.extract(text).stream().map(PiiMention::normalized).toList());
    Assertions.assertTrue(text.reads <= text.length() * 40,
        () -> "read " + text.reads + " characters from an input of " + text.length());
  }

  /**
   * Checks that a rejected run is not scanned again from each embedded prefix.
   *
   * @param group The repeated installation-token prefix and separator.
   */
  @ParameterizedTest
  @ValueSource(strings = {"ghs_.", "ghs_-", "ghs_a."})
  void testInvalidGithubInstallationReadCount(String group) {
    final CountingCharSequence text = new CountingCharSequence(group.repeat(4096) + "é");
    Assertions.assertTrue(extractor.extract(text).isEmpty());
    Assertions.assertTrue(text.reads <= text.length() * 40,
        () -> "read " + text.reads + " characters from an input of " + text.length());
  }

  /**
   * Checks supported AWS key prefixes, normalization and exact spans.
   *
   * @param text The synthetic access key identifier.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "AKIAIOSFODNN7EXAMPLE",
      "ASIAIOSFODNN7EXAMPLE",
      "AKIA1234567890ABCDEF",
      "ASIAZZZZZZZZZZZZZZZZ",
      "AKIA0000000000000000"})
  void testAcceptsAwsAccessKeys(String text) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_AWS_ACCESS_KEY, mentions.get(0).type());
    Assertions.assertEquals(text, mentions.get(0).normalized());
    Assertions.assertEquals(0, mentions.get(0).span().getStart());
    Assertions.assertEquals(text.length(), mentions.get(0).span().getEnd());
  }

  /**
   * Rejects invalid key lengths, alphabets and other AWS identifier prefixes.
   *
   * @param text The invalid key candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "AKIAIOSFODNN7EXAMPL",
      "AKIAIOSFODNN7EXAMPLEX",
      "akiaiosfodnn7example",
      "AKIAiosfodnn7EXAMPLE",
      "AIDAIOSFODNN7EXAMPLE",
      "AROAIOSFODNN7EXAMPLE",
      "ANPAIOSFODNN7EXAMPLE",
      "APKAIOSFODNN7EXAMPLE",
      "XAKIAIOSFODNN7EXAMPLE",
      "AKIA-IOSFODNN7EXAMPL",
      "AKIA_IOSFODNN7EXAMPL"})
  void testRejectsAwsAccessKeyNearMisses(String text) {
    Assertions.assertTrue(
        extractor.extract(text).stream()
            .noneMatch(m -> PiiMention.TYPE_AWS_ACCESS_KEY.equals(m.type())), text);
  }

  /** Checks the original span of a key identifier within a sentence. */
  @Test
  void testAwsAccessKeySpanInSentence() {
    final String text = "Rotate AKIAIOSFODNN7EXAMPLE right away.";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals("AKIAIOSFODNN7EXAMPLE", text.substring(
        mentions.get(0).span().getStart(), mentions.get(0).span().getEnd()));
  }

  /**
   * Checks all supported short GitHub token prefixes.
   *
   * @param text The synthetic token.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "ghp_1234567890abcdefghijklmnopqrstuvwxyz",
      "gho_1234567890abcdefghijklmnopqrstuvwxyz",
      "ghu_1234567890abcdefghijklmnopqrstuvwxyz",
      "ghs_1234567890abcdefghijklmnopqrstuvwxyz",
      "ghr_1234567890abcdefghijklmnopqrstuvwxyz",
      "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"})
  void testAcceptsGithubTokens(String text) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_GITHUB_TOKEN, mentions.get(0).type());
    Assertions.assertEquals(text, mentions.get(0).normalized());
  }

  /** Checks a fine-grained token at the scanner's minimum length. */
  @Test
  void testAcceptsFineGrainedGithubToken() {
    final String token = "github_pat_11ABCDEFG0abcdefghijkl_"
        + "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVW";
    Assertions.assertEquals(93, token.length());
    final List<PiiMention> mentions = extractor.extract("token: " + token);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_GITHUB_TOKEN, mentions.get(0).type());
    Assertions.assertEquals(token, mentions.get(0).normalized());
  }

  /**
   * Checks variable-length bodies containing underscores.
   *
   * @param prefix The token prefix.
   * @param minimum The scanner's minimum body length for the prefix.
   */
  @ParameterizedTest
  @CsvSource({"ghp_,36", "gho_,36", "ghu_,36", "ghr_,36", "github_pat_,82"})
  void testAcceptsVariableLengthGithubTokens(String prefix, int minimum) {
    final String value = prefix + "a".repeat(minimum) + "_" + "B".repeat(24);
    Assertions.assertEquals(value, extractor.extract(value).get(0).normalized());
  }

  /**
   * Checks the scanner limit for non-installation token prefixes.
   *
   * @param prefix The token prefix.
   */
  @ParameterizedTest
  @ValueSource(strings = {"ghp_", "gho_", "ghu_", "ghr_", "github_pat_"})
  void testGithubTokenMaximumLengthBoundary(String prefix) {
    final String accepted = prefix + "a".repeat(255 - prefix.length());
    final String rejected = accepted + "a";

    Assertions.assertEquals(255, accepted.length());
    Assertions.assertEquals(accepted, extractor.extract(accepted).get(0).normalized());
    Assertions.assertTrue(extractor.extract(rejected).isEmpty());
  }

  /**
   * Rejects short bodies, incorrect alphabets and unknown or embedded prefixes.
   *
   * @param text The invalid token candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "ghp_1234567890abcdefghijklmnopqrstuvwxy",
      "ghx_1234567890abcdefghijklmnopqrstuvwxyz",
      "GHP_1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ",
      "ghp1234567890abcdefghijklmnopqrstuvwxyz",
      "ghp_1234567890abcdefghijklmnopqrstuvwx-z",
      "xghp_1234567890abcdefghijklmnopqrstuvwxyz",
      "github_pat_tooshort"})
  void testRejectsGithubTokenNearMisses(String text) {
    Assertions.assertTrue(
        extractor.extract(text).stream()
            .noneMatch(m -> PiiMention.TYPE_GITHUB_TOKEN.equals(m.type())), text);
  }

  /** Checks a complete token inside an authorization header. */
  @Test
  void testAcceptsJsonWebToken() {
    final String token = HS256_HEADER + "." + PAYLOAD + "." + SIGNATURE;
    final List<PiiMention> mentions = extractor.extract("Authorization: Bearer " + token);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_JWT, mentions.get(0).type());
    Assertions.assertEquals(token, mentions.get(0).normalized());
  }

  /**
   * Checks algorithm values and permitted JSON whitespace.
   *
   * @param header The base64url header.
   */
  @ParameterizedTest
  @ValueSource(strings = {HS256_HEADER, RS256_HEADER, SPACED_HEADER})
  void testAcceptsJsonWebTokensOfSeveralAlgorithms(String header) {
    final String token = header + "." + PAYLOAD + "." + SIGNATURE;

    final List<PiiMention> mentions = extractor.extract(token);

    Assertions.assertEquals(1, mentions.size(), header);
    Assertions.assertEquals(PiiMention.TYPE_JWT, mentions.get(0).type());
    Assertions.assertEquals(0, mentions.get(0).span().getStart());
    Assertions.assertEquals(token.length(), mentions.get(0).span().getEnd());
  }

  /**
   * Rejects headers without an algorithm and incomplete or embedded compact tokens.
   *
   * @param text The invalid token candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      NO_ALGORITHM_HEADER + ".eyJzdWIiOiIxIn0.abcdefgh",
      NOTALG_HEADER + "." + PAYLOAD + "." + SIGNATURE,
      ALGORITHM_HEADER + "." + PAYLOAD + "." + SIGNATURE,
      "notbase64url." + PAYLOAD + "." + SIGNATURE,
      HS256_HEADER + "." + PAYLOAD,
      HS256_HEADER + "." + PAYLOAD + ".",
      HS256_HEADER + ".." + SIGNATURE,
      "eyJ." + PAYLOAD + "." + SIGNATURE,
      "x" + HS256_HEADER + "." + PAYLOAD + "." + SIGNATURE})
  void testRejectsJsonWebTokenNearMisses(String text) {
    Assertions.assertTrue(
        extractor.extract(text).stream()
            .noneMatch(m -> PiiMention.TYPE_JWT.equals(m.type())), text);
  }

  /**
   * Checks complete userinfo spans across URL schemes and encoded passwords.
   *
   * @param text The URL containing credentials.
   * @param credential The expected userinfo text.
   */
  @ParameterizedTest
  @CsvSource({
      "https://user:secret@example.com/path, user:secret",
      "http://admin:s3cr3t@10.0.0.1:8080/, admin:s3cr3t",
      "ftp://jane.doe:pw%21@files.example.org, jane.doe:pw%21",
      "postgres://app:hunter2@db.internal:5432/main, app:hunter2",
      "redis://default:abc-123_x@cache.example.net, default:abc-123_x",
      "amqp://guest:guest@broker/, guest:guest"
  })
  void testAcceptsUrlCredentials(String text, String credential) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_URL_CREDENTIAL, mentions.get(0).type());
    Assertions.assertEquals(credential, mentions.get(0).normalized());
    Assertions.assertEquals(credential, text.substring(
        mentions.get(0).span().getStart(), mentions.get(0).span().getEnd()));
  }

  /**
   * Rejects URLs without complete credentials or a valid scheme.
   *
   * @param text The URL or incomplete credential candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "https://example.com/path",
      "https://user@example.com",
      "https://:secret@example.com",
      "https://user:@example.com",
      "https://example.com/a?b=c@d",
      "mailto:jane@example.com",
      "://user:secret@example.com",
      "user:secret@example.com"})
  void testRejectsUrlCredentialNearMisses(String text) {
    Assertions.assertTrue(
        extractor.extract(text).stream()
            .noneMatch(m -> PiiMention.TYPE_URL_CREDENTIAL.equals(m.type())), text);
  }

  /** Checks that URL credentials and their embedded token are both retained. */
  @Test
  void testUrlCredentialContainingATokenRetainsBothMentions() {
    final String text = "https://oauth2:ghp_1234567890abcdefghijklmnopqrstuvwxyz@github.com/x.git";
    final List<PiiMention> mentions = extractor.extract(text);

    final String credential = "oauth2:ghp_1234567890abcdefghijklmnopqrstuvwxyz";
    final String token = "ghp_1234567890abcdefghijklmnopqrstuvwxyz";
    Assertions.assertEquals(List.of(
        new PiiMention(new Span(8, 8 + credential.length()),
            PiiMention.TYPE_URL_CREDENTIAL, credential),
        new PiiMention(new Span(15, 15 + token.length()),
            PiiMention.TYPE_GITHUB_TOKEN, token)), mentions);
  }

  /** Checks independent scans produce mentions in text order. */
  @Test
  void testFindsSeveralSecretsInOneText() {
    final String text = "key AKIAIOSFODNN7EXAMPLE token ghp_1234567890abcdefghijklmnopqrstuvwxyz "
        + "jwt " + HS256_HEADER + "." + PAYLOAD + "." + SIGNATURE
        + " url https://u:p@example.com/";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(List.of(PiiMention.TYPE_AWS_ACCESS_KEY,
            PiiMention.TYPE_GITHUB_TOKEN, PiiMention.TYPE_JWT, PiiMention.TYPE_URL_CREDENTIAL),
        mentions.stream().map(PiiMention::type).toList());
    int lastEnd = 0;
    for (final PiiMention mention : mentions) {
      Assertions.assertTrue(mention.span().getStart() >= lastEnd);
      lastEnd = mention.span().getEnd();
    }
  }

  /** Checks that scanning continues after the first token. */
  @Test
  void testTwoTokensSideBySideAreBothFound() {
    final String text = "ghp_1234567890abcdefghijklmnopqrstuvwxyz "
        + "ghs_abcdefghijklmnopqrstuvwxyz1234567890";
    Assertions.assertEquals(2, extractor.extract(text).size());
  }

  /** Checks extraction can be restricted to selected credential types. */
  @Test
  void testTypeSubsetLimitsWhatIsReported() {
    final String text = "AKIAIOSFODNN7EXAMPLE and https://u:p@example.com/";

    Assertions.assertEquals(List.of(PiiMention.TYPE_AWS_ACCESS_KEY),
        new SecretsPiiExtractor(Set.of(PiiMention.TYPE_AWS_ACCESS_KEY)).extract(text)
            .stream().map(PiiMention::type).toList());
    Assertions.assertEquals(List.of(PiiMention.TYPE_URL_CREDENTIAL),
        new SecretsPiiExtractor(Set.of(PiiMention.TYPE_URL_CREDENTIAL)).extract(text)
            .stream().map(PiiMention::type).toList());
  }

  /**
   * Checks empty text, ordinary prose and incomplete prefixes.
   *
   * @param text The text without a supported credential.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "nothing to see here",
      "AKIA",
      "ghp_",
      "eyJ",
      "https://",
      "",
      "The word algorithm contains alg but is no token"})
  void testTextWithoutASecretYieldsNoMention(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /** Checks constructor and extraction argument validation. */
  @Test
  void testRejectsUnrecognizedTypeAndMissingArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new SecretsPiiExtractor(Set.of(PiiMention.TYPE_EMAIL)));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new SecretsPiiExtractor(Set.of()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new SecretsPiiExtractor(null));
    Assertions.assertThrows(IllegalArgumentException.class, () -> extractor.extract(null));
  }
}
