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

import java.net.URI;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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

/** Checks token boundaries and URL userinfo with synthetic credential values. */
class SecretsBoundaryTest {

  private static final SecretsPiiExtractor ALL = new SecretsPiiExtractor();
  private static final SecretsPiiExtractor URL_ONLY =
      new SecretsPiiExtractor(Set.of(PiiMention.TYPE_URL_CREDENTIAL));
  private static final String SCHEME_SEPARATOR = "://";
  private static final String AUTHORITY_END = "@example.invalid";
  private static final String CREDENTIAL = "user:pw%40:demo";
  private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.dGVzdA";

  /**
   * A credential candidate used by the boundary tests.
   *
   * @param type The expected mention type.
   * @param text The synthetic candidate.
   */
  private record Token(String type, String text) { }

  /**
   * Provides all supported AWS and GitHub prefixes and one JWT candidate.
   *
   * @return The synthetic token values.
   */
  private static Stream<Token> tokens() {
    final List<Token> values = new ArrayList<>();
    for (final String prefix : List.of("AKIA", "ASIA")) {
      values.add(new Token(PiiMention.TYPE_AWS_ACCESS_KEY, prefix + "A".repeat(16)));
    }
    for (final String prefix : List.of("ghp_", "gho_", "ghu_", "ghs_", "ghr_")) {
      values.add(new Token(PiiMention.TYPE_GITHUB_TOKEN, prefix + "a".repeat(36)));
    }
    values.add(new Token(PiiMention.TYPE_GITHUB_TOKEN, "github_pat_" + "a".repeat(82)));
    values.add(new Token(PiiMention.TYPE_JWT, JWT));
    return values.stream();
  }

  /**
   * Provides preceding ASCII/Unicode letters, digits and underscores.
   *
   * @return The token and adjoining prefix.
   */
  private static Stream<Arguments> wordPrefixes() {
    return tokens().flatMap(token -> Stream.of("a", "Z", "0", "é", "中", "𐐀", "١", "𝟙", "_")
        .map(prefix -> Arguments.of(token, prefix)));
  }

  /**
   * Rejects a token suffix inside a longer identifier.
   *
   * @param token The candidate token.
   * @param prefix The identifier prefix.
   */
  @ParameterizedTest
  @MethodSource("wordPrefixes")
  void testWordPrefix(Token token, String prefix) {
    Assertions.assertTrue(ALL.extract(prefix + token.text()).isEmpty());
  }

  /**
   * Provides Unicode letters and digits after each token.
   *
   * @return The token and adjoining suffix.
   */
  private static Stream<Arguments> wordSuffixes() {
    return tokens().flatMap(token -> Stream.of("é", "中", "𐐀", "١", "𝟙")
        .map(suffix -> Arguments.of(token, suffix)));
  }

  /**
   * Checks that no token prefix is reported before a Unicode letter or digit.
   *
   * @param token The candidate token.
   * @param suffix The following letter or digit.
   */
  @ParameterizedTest
  @MethodSource("wordSuffixes")
  void testWordSuffix(Token token, String suffix) {
    Assertions.assertTrue(ALL.extract(token.text() + suffix).isEmpty());
  }

  /**
   * Provides punctuation and whitespace surrounding tokens.
   *
   * @return The token and surrounding delimiter.
   */
  private static Stream<Arguments> delimiters() {
    return tokens().flatMap(token -> Stream.of("", " ", "\t", "\n", "\u00a0", "😀", "\"", "'",
        ",", ";", ":", "(", ")", "[", "]", "\u0000")
        .map(delimiter -> Arguments.of(token, delimiter)));
  }

  /**
   * Checks accepted boundaries, normalized values and UTF-16 spans.
   *
   * @param token The candidate token.
   * @param delimiter The surrounding character or empty string.
   */
  @ParameterizedTest
  @MethodSource("delimiters")
  void testDelimiter(Token token, String delimiter) {
    final String text = delimiter + token.text() + delimiter;
    Assertions.assertEquals(List.of(new PiiMention(
        new Span(delimiter.length(), delimiter.length() + token.text().length()),
        token.type(), token.text())), ALL.extract(text));
  }

  /**
   * Keeps the existing distinction between hyphens and the token alphabets.
   *
   * @param token The candidate token.
   */
  @ParameterizedTest
  @MethodSource("tokens")
  void testHyphenPrefix(Token token) {
    Assertions.assertEquals(PiiMention.TYPE_JWT.equals(token.type()) ? 0 : 1,
        ALL.extract("-" + token.text()).size());
  }

  /**
   * Checks tokens after an assignment operator.
   *
   * @param token The candidate token.
   */
  @ParameterizedTest
  @MethodSource("tokens")
  void testAssignment(Token token) {
    final String prefix = "value=";
    Assertions.assertEquals(List.of(new PiiMention(
        new Span(prefix.length(), prefix.length() + token.text().length()), token.type(), token.text())),
        ALL.extract(prefix + token.text() + " "));
  }

  /**
   * Checks complete scheme syntax, including non-letter endings.
   *
   * @param scheme The valid scheme name.
   */
  @ParameterizedTest
  @ValueSource(strings = {"s3", "h2", "HTTP", "a", "a+", "a-", "a.", "a0", "a1b2",
      "git+ssh", "web+demo2", "a.b-3+4", "x-custom-7"})
  void testScheme(String scheme) {
    final String url = scheme + SCHEME_SEPARATOR + CREDENTIAL + AUTHORITY_END;
    Assertions.assertEquals(CREDENTIAL, URI.create(url).getRawUserInfo());
    final String prefix = "😀 (";
    final int start = prefix.length() + scheme.length() + SCHEME_SEPARATOR.length();
    Assertions.assertEquals(List.of(new PiiMention(new Span(start, start + CREDENTIAL.length()),
        PiiMention.TYPE_URL_CREDENTIAL, CREDENTIAL)), URL_ONLY.extract(prefix + url + ")"));
  }

  /**
   * Rejects malformed scheme names instead of starting at their final letter.
   *
   * @param scheme The invalid scheme candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "1http", "0ftp", "123abc", "9", "+http", "-http", ".http",
      "éhttp", "中http", "𐐀http", "١http", "𝟙http", "_http", "ht_tp"})
  void testInvalidScheme(String scheme) {
    Assertions.assertTrue(URL_ONLY.extract(scheme + SCHEME_SEPARATOR + CREDENTIAL
        + AUTHORITY_END).isEmpty());
  }

  /**
   * Provides every percent-encoded byte value.
   *
   * @return The byte values from zero through 255.
   */
  private static IntStream octets() {
    return IntStream.range(0, 256);
  }

  /**
   * Checks lowercase and uppercase hexadecimal escapes without decoding their value.
   *
   * @param octet The escaped byte.
   */
  @ParameterizedTest
  @MethodSource("octets")
  void testPercentEncodedByte(int octet) {
    final String userinfo = "u%" + HexFormat.of().toHexDigits((byte) octet)
        + ":p%" + HexFormat.of().withUpperCase().toHexDigits((byte) octet);
    final String url = "https" + SCHEME_SEPARATOR + userinfo + AUTHORITY_END;
    Assertions.assertEquals(userinfo, URI.create(url).getRawUserInfo());
    Assertions.assertEquals(userinfo, URL_ONLY.extract(url).getFirst().normalized());
  }

  /**
   * Provides malformed percent escapes in both username and password.
   *
   * @return The malformed userinfo value.
   */
  private static Stream<String> invalidEscapes() {
    return Stream.of("%", "%0", "%x", "%gg", "%0g", "%g0", "%000%", "%%00", "%é0",
        "%𝟙0", "%١0").flatMap(escape -> Stream.of("u" + escape + ":pw", "user:pw" + escape));
  }

  /**
   * Rejects incomplete and non-hexadecimal percent escapes.
   *
   * @param userinfo The malformed credential component.
   */
  @ParameterizedTest
  @MethodSource("invalidEscapes")
  void testInvalidEscape(String userinfo) {
    Assertions.assertTrue(URL_ONLY.extract("https" + SCHEME_SEPARATOR + userinfo
        + AUTHORITY_END).isEmpty());
  }

  /**
   * Checks malformed candidates do not prevent detection of a later URL.
   *
   * @param malformed The earlier malformed URL.
   */
  @ParameterizedTest
  @ValueSource(strings = {"1http://u:p@example.invalid", "https://u:p%@example.invalid",
      "https://u:p%", "https://u:p", "https://"})
  void testResumeAfterInvalidUrl(String malformed) {
    final String prefix = malformed + " 😀 ";
    final String scheme = "s3" + SCHEME_SEPARATOR;
    final int start = prefix.length() + scheme.length();
    Assertions.assertEquals(List.of(new PiiMention(new Span(start, start + CREDENTIAL.length()),
        PiiMention.TYPE_URL_CREDENTIAL, CREDENTIAL)),
        URL_ONLY.extract(prefix + scheme + CREDENTIAL + AUTHORITY_END));
  }

  /** Checks the manual example preserves the scheme, host and path during masking. */
  @Test
  void testManualExample() {
    final Document result = new PiiAnnotator(PiiPacks.secrets()).annotate(Document.of(
        "Fetch s3://user:demo%40pass@example.invalid/model.bin"));
    Assertions.assertEquals("Fetch s3://****************@example.invalid/model.bin",
        Masker.mask(result, PiiAnnotator.PII, '*'));
  }

  /**
   * Checks shared extraction with per-request credentials and offsets.
   *
   * @throws Exception If a worker fails or does not finish in time.
   */
  @Test
  @Timeout(60)
  void testConcurrentExtraction() throws Exception {
    final List<Callable<Void>> calls = new ArrayList<>();
    final CountDownLatch ready = new CountDownLatch(8);
    for (int worker = 0; worker < 8; worker++) {
      final String prefix = "😀".repeat(worker) + " s3://";
      final String userinfo = "u" + worker + ":p%40" + worker;
      calls.add(() -> {
        ready.countDown();
        Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
        for (int run = 0; run < 32; run++) {
          final String text = prefix + userinfo + AUTHORITY_END + " " + JWT;
          final var expected = List.of(
              new PiiMention(new Span(prefix.length(), prefix.length() + userinfo.length()),
                  PiiMention.TYPE_URL_CREDENTIAL, userinfo),
              new PiiMention(new Span(text.length() - JWT.length(), text.length()),
                  PiiMention.TYPE_JWT, JWT));
          Assertions.assertEquals(expected, ALL.extract(text));
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
