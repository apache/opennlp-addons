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
import java.util.stream.IntStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Checks GitHub installation-token detection using synthetic credentials. */
class GithubInstallationTokenTest {

  private static final String PREFIX = "ghs_";
  private static final String JWT_PREFIX = PREFIX + "12345_eyJhbGciOiJIUzI1NiJ9.e30.";
  private static final SecretsPiiExtractor ALL = new SecretsPiiExtractor();
  private static final SecretsPiiExtractor GITHUB =
      new SecretsPiiExtractor(Set.of(PiiMention.TYPE_GITHUB_TOKEN));

  /**
   * Builds a synthetic installation token with a variable-length signature segment.
   *
   * @param repetitions The number of three-character signature groups.
   * @return The token-shaped value, without a valid signature.
   */
  private String token(int repetitions) {
    return JWT_PREFIX + "A-_".repeat(repetitions);
  }

  /**
   * Checks that installation tokens do not have a fixed upper length bound.
   *
   * @param length The body length, excluding the prefix.
   */
  @ParameterizedTest
  @ValueSource(ints = {36, 251, 252, 516, 1024, 65536})
  void testOpaqueLength(int length) {
    final String value = PREFIX + "a".repeat(length);
    assertToken(value);
  }

  /**
   * Checks complete dotted tokens with long signatures and base64url punctuation.
   *
   * @param repetitions The number of signature groups.
   */
  @ParameterizedTest
  @ValueSource(ints = {12, 64, 160, 1024, 21846})
  void testDottedLength(int repetitions) {
    assertToken(token(repetitions));
  }

  /**
   * Checks exact spans, normalization and type with both extractor configurations.
   *
   * @param value The expected complete token.
   */
  private void assertToken(String value) {
    final String prefix = "😀 Authorization: Bearer ";
    final List<PiiMention> expected = List.of(new PiiMention(
        new Span(prefix.length(), prefix.length() + value.length()),
        PiiMention.TYPE_GITHUB_TOKEN, value));
    Assertions.assertEquals(expected, GITHUB.extract(prefix + value + "\n"));
    Assertions.assertEquals(expected, ALL.extract(prefix + value + "\n"));
  }

  /**
   * Provides all ASCII characters for the token alphabet check.
   *
   * @return The ASCII character values.
   */
  private static IntStream asciiCharacters() {
    return IntStream.range(0, 128);
  }

  /**
   * Checks every ASCII character inside a candidate shorter than two separate tokens.
   *
   * @param character The inserted character.
   */
  @ParameterizedTest
  @MethodSource("asciiCharacters")
  void testAlphabet(int character) {
    final boolean allowed = character >= 'A' && character <= 'Z'
        || character >= 'a' && character <= 'z' || character >= '0' && character <= '9'
        || character == '.' || character == '-' || character == '_';
    final String value = PREFIX + "a".repeat(18) + (char) character + "B".repeat(18);
    Assertions.assertEquals(allowed ? List.of(new PiiMention(new Span(0, value.length()),
        PiiMention.TYPE_GITHUB_TOKEN, value)) : List.of(), GITHUB.extract(value));
  }

  /**
   * Checks the minimum candidate length without requiring JWT contents.
   *
   * @param length The number of body characters.
   * @param accepted Whether the candidate meets the minimum.
   */
  @ParameterizedTest
  @CsvSource({"0,false", "1,false", "35,false", "36,true", "37,true"})
  void testMinimumLength(int length, boolean accepted) {
    Assertions.assertEquals(accepted ? 1 : 0,
        GITHUB.extract(PREFIX + "-".repeat(length)).size());
  }

  /**
   * Checks opaque matching without parsing the app id, JSON, claims or signature.
   *
   * @param body The synthetic body matching the vendor's candidate alphabet.
   */
  @ParameterizedTest
  @ValueSource(strings = {"not_a_numeric_app_id_or_a_decodable_JWT",
      "app_noJsonHeader.noJsonClaims.notASignature",
      "letters.with.more.than.two.dots.and-hyphens"})
  void testOpaqueContents(String body) {
    assertToken(PREFIX + body);
  }

  /**
   * Rejects candidates adjoining Unicode letters, digits or an identifier prefix.
   *
   * @param adjoining The adjoining identifier content.
   */
  @ParameterizedTest
  @ValueSource(strings = {"é", "中", "𐐀", "١", "𝟙"})
  void testUnicodeBoundary(String adjoining) {
    final String value = token(160);
    Assertions.assertTrue(GITHUB.extract(adjoining + value).isEmpty());
    Assertions.assertTrue(GITHUB.extract(value + adjoining).isEmpty());
    Assertions.assertTrue(GITHUB.extract(value + "." + adjoining).isEmpty());
  }

  /**
   * Rejects candidate starts inside an ASCII identifier or with a changed prefix case.
   *
   * @param prefix The invalid prefix before a long body.
   */
  @ParameterizedTest
  @ValueSource(strings = {"xghs_", "_ghs_", "0ghs_", "GHS_", "Ghs_", "ghS_", "ghx_"})
  void testPrefix(String prefix) {
    Assertions.assertTrue(GITHUB.extract(prefix + "a".repeat(516)).isEmpty());
  }

  /**
   * Keeps punctuation outside the mask while retaining internal token punctuation.
   *
   * @param suffix The sentence delimiter.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", ".", "...", ". ", ";", ",", ")", "]", "\"", "'", "!", "?", "\n"})
  void testEndPunctuation(String suffix) {
    final String value = token(160);
    Assertions.assertEquals(List.of(new PiiMention(new Span(0, value.length()),
        PiiMention.TYPE_GITHUB_TOKEN, value)), GITHUB.extract(value + suffix));
  }

  /** Checks that trailing sentence dots do not count toward the minimum body length. */
  @Test
  void testDotsDoNotCompleteShortBody() {
    Assertions.assertTrue(GITHUB.extract(PREFIX + "a".repeat(35) + "...").isEmpty());
  }

  /**
   * Checks that the expanded alphabet and length are specific to installation tokens.
   *
   * @param prefix The other GitHub token prefix.
   */
  @ParameterizedTest
  @ValueSource(strings = {"ghp_", "gho_", "ghu_", "ghr_", "github_pat_"})
  void testOtherTokenLimits(String prefix) {
    Assertions.assertTrue(GITHUB.extract(prefix + "a".repeat(256)).isEmpty());
    Assertions.assertTrue(GITHUB.extract(prefix + "a".repeat(18) + "-" + "B".repeat(18)).isEmpty());
  }

  /**
   * Checks that selecting another secret type does not emit part of an installation token.
   *
   * @param type The selected type.
   */
  @ParameterizedTest
  @ValueSource(strings = {PiiMention.TYPE_AWS_ACCESS_KEY, PiiMention.TYPE_JWT,
      PiiMention.TYPE_URL_CREDENTIAL})
  void testTypeSelection(String type) {
    Assertions.assertTrue(new SecretsPiiExtractor(Set.of(type)).extract(token(160)).isEmpty());
  }

  /** Checks URL userinfo overlap with the entire installation token. */
  @Test
  void testUrlCredential() {
    final String value = token(160);
    final String credential = "user:" + value;
    final String prefix = "https://";
    final String text = prefix + credential + "@example.invalid/repo.git";
    Assertions.assertEquals(List.of(
        new PiiMention(new Span(prefix.length(), prefix.length() + credential.length()),
            PiiMention.TYPE_URL_CREDENTIAL, credential),
        new PiiMention(new Span(prefix.length() + "user:".length(),
            prefix.length() + credential.length()), PiiMention.TYPE_GITHUB_TOKEN, value)),
        ALL.extract(text));
    Assertions.assertEquals(List.of(value), GITHUB.extract(text).stream()
        .map(PiiMention::normalized).toList());
  }

  /** Checks recovery after an invalid candidate and extraction of two adjacent tokens. */
  @Test
  void testNextToken() {
    final String first = token(160);
    final String second = token(161);
    final String text = PREFIX + "a".repeat(516) + "é; " + first + " " + second;
    Assertions.assertEquals(List.of(first, second), GITHUB.extract(text).stream()
        .map(PiiMention::normalized).toList());
  }

  /** Checks the manual's complete-token masking example. */
  @Test
  void testManualExample() {
    final String installationToken = "ghs_12345_eyJhbGciOiJIUzI1NiJ9.e30." + "A-_".repeat(160);
    final Document credentials = new PiiAnnotator(PiiPacks.secrets()).annotate(
        Document.of("Bearer " + installationToken));
    Assertions.assertEquals("Bearer " + "*".repeat(installationToken.length()),
        Masker.mask(credentials, PiiAnnotator.PII, '*'));
    Assertions.assertEquals(installationToken,
        credentials.get(PiiAnnotator.PII).getFirst().value().normalized());
  }

  /**
   * Checks that shared extractors keep concurrent installation tokens separate.
   *
   * @throws Exception If a worker fails or does not finish in time.
   */
  @Test
  @Timeout(60)
  void testConcurrentTokens() throws Exception {
    final CountDownLatch ready = new CountDownLatch(8);
    final List<Callable<Void>> calls = new ArrayList<>();
    for (int worker = 0; worker < 8; worker++) {
      final String value = token(160 + worker);
      calls.add(() -> {
        ready.countDown();
        Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
        for (int run = 0; run < 32; run++) {
          assertToken(value);
          Assertions.assertTrue(GITHUB.extract(value + "é").isEmpty());
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
