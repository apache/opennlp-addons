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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/**
 * Tests {@link HmacTokenizer}.
 */
public class HmacTokenizerTest {

  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final byte[] OTHER_KEY =
      "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
  private static final PiiExtractor EXTRACTOR = new CursorPiiExtractor();
  private static final HmacTokenizer TOKENIZER = new HmacTokenizer(KEY);

  private static PiiMention mention(String type, String normalized) {
    return new PiiMention(new Span(0, 1), type, normalized);
  }

  @Test
  void testTokenNamesTheTypeAndHidesTheValue() {
    final String token = TOKENIZER.token(PiiMention.TYPE_EMAIL, "jane@example.com");

    Assertions.assertTrue(token.startsWith("EMAIL-"), token);
    Assertions.assertFalse(token.contains("jane"), token);
    Assertions.assertEquals("EMAIL-".length() + 16, token.length(), token);
  }

  @ParameterizedTest
  @CsvSource({
      "email, EMAIL-",
      "phone, PHONE-",
      "card, CARD-",
      "us-ssn, US-SSN-",
      "aws-access-key, AWS-ACCESS-KEY-",
  })
  void testTokenPrefixIsTheUppercasedType(String type, String expected) {
    Assertions.assertTrue(TOKENIZER.token(type, "value").startsWith(expected));
  }

  @Test
  void testSameValueTokenizesTheSameEveryTime() {
    Assertions.assertEquals(TOKENIZER.token(PiiMention.TYPE_EMAIL, "jane@example.com"),
        TOKENIZER.token(PiiMention.TYPE_EMAIL, "jane@example.com"));
  }

  /**
   * Verifies the property that {@link Pseudonymizer} does not have: two tokenizers holding
   * the same key agree, which is what makes tokens comparable across documents and runs.
   */
  @Test
  void testTokensAgreeAcrossInstancesHoldingTheSameKey() {
    Assertions.assertEquals(new HmacTokenizer(KEY).token(PiiMention.TYPE_EMAIL, "a@b.com"),
        new HmacTokenizer(KEY.clone()).token(PiiMention.TYPE_EMAIL, "a@b.com"));
  }

  @Test
  void testDifferentKeysGiveDifferentTokens() {
    Assertions.assertNotEquals(TOKENIZER.token(PiiMention.TYPE_EMAIL, "a@b.com"),
        new HmacTokenizer(OTHER_KEY).token(PiiMention.TYPE_EMAIL, "a@b.com"));
  }

  @Test
  void testDifferentValuesGiveDifferentTokens() {
    Assertions.assertNotEquals(TOKENIZER.token(PiiMention.TYPE_EMAIL, "a@b.com"),
        TOKENIZER.token(PiiMention.TYPE_EMAIL, "c@d.com"));
  }

  /**
   * Pins a real collision produced by eight hexadecimal digits. The default
   * must preserve these distinct normalized values as distinct audit identities.
   */
  @Test
  void testDefaultDoesNotCollapseKnownThirtyTwoBitCollision() {
    Assertions.assertNotEquals(
        TOKENIZER.token(PiiMention.TYPE_EMAIL, "user210255@example.com"),
        TOKENIZER.token(PiiMention.TYPE_EMAIL, "user217053@example.com"));
  }

  @Test
  void testSameValueUnderTwoTypesGivesUnrelatedTokens() {
    final String asPhone = TOKENIZER.token(PiiMention.TYPE_PHONE, "5551234567");
    final String asCard = TOKENIZER.token(PiiMention.TYPE_CARD, "5551234567");

    Assertions.assertNotEquals(asPhone.substring(asPhone.indexOf('-') + 1),
        asCard.substring(asCard.lastIndexOf('-') + 1));
  }

  @Test
  void testTokenizesAMention() {
    Assertions.assertEquals(TOKENIZER.token(PiiMention.TYPE_EMAIL, "jane@example.com"),
        TOKENIZER.token(mention(PiiMention.TYPE_EMAIL, "jane@example.com")));
  }

  @ParameterizedTest
  @ValueSource(ints = {4, 5, 8, 16, 63, 64})
  void testTokenLengthIsRespected(int length) {
    final String token = new HmacTokenizer(KEY, length)
        .token(PiiMention.TYPE_EMAIL, "jane@example.com");

    Assertions.assertEquals("EMAIL-".length() + length, token.length(), token);
  }

  @Test
  void testShorterTokenIsAPrefixOfALongerOne() {
    final String shorter = new HmacTokenizer(KEY, 8).token(PiiMention.TYPE_EMAIL, "a@b.com");
    final String longer = new HmacTokenizer(KEY, 16).token(PiiMention.TYPE_EMAIL, "a@b.com");

    Assertions.assertTrue(longer.startsWith(shorter), longer + " should start with " + shorter);
  }

  @Test
  void testTokensAreLowercaseHexadecimal() {
    final String token = new HmacTokenizer(KEY, 64).token(PiiMention.TYPE_EMAIL, "a@b.com");
    final String digits = token.substring(token.indexOf('-') + 1);

    Assertions.assertEquals(64, digits.length());
    for (int i = 0; i < digits.length(); i++) {
      final char c = digits.charAt(i);
      Assertions.assertTrue((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'),
          "not a lowercase hexadecimal digit: " + c);
    }
  }

  @Test
  void testDistinctValuesRarelyCollide() {
    final Set<String> tokens = new HashSet<>();
    for (int i = 0; i < 2000; i++) {
      tokens.add(TOKENIZER.token(PiiMention.TYPE_EMAIL, "user" + i + "@example.com"));
    }

    Assertions.assertEquals(2000, tokens.size());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3, 65, 128, -1})
  void testRejectsATokenLengthOutOfRange(int length) {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new HmacTokenizer(KEY, length));
  }

  @Test
  void testRejectsAMissingKey() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> new HmacTokenizer(null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new HmacTokenizer(new byte[0]));
  }

  /**
   * Verifies rejection below the HMAC-SHA-256 output width.
   *
   * @param length The rejected key length.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 16, 31})
  void testRejectsKeysShorterThanSha256Output(int length) {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new HmacTokenizer(new byte[length]));
  }

  /** Verifies acceptance at the minimum key length. */
  @Test
  void testAcceptsAThirtyTwoByteKey() {
    Assertions.assertDoesNotThrow(() -> new HmacTokenizer(new byte[32]));
  }

  /**
   * Verifies that the key is copied, so a caller who wipes the array they passed does not
   * silently change every token afterwards.
   */
  @Test
  void testKeyIsCopiedOnConstruction() {
    final byte[] key = KEY.clone();
    final HmacTokenizer tokenizer = new HmacTokenizer(key);
    final String before = tokenizer.token(PiiMention.TYPE_EMAIL, "a@b.com");

    Arrays.fill(key, (byte) 0);
    Assertions.assertEquals(before, tokenizer.token(PiiMention.TYPE_EMAIL, "a@b.com"));
  }

  @Test
  void testRejectsAMissingTypeOrValue() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> TOKENIZER.token(null, "value"));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> TOKENIZER.token("  ", "value"));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> TOKENIZER.token(PiiMention.TYPE_EMAIL, null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> TOKENIZER.token(PiiMention.TYPE_EMAIL, ""));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> TOKENIZER.token((PiiMention) null));
  }

  @Test
  void testRewritesATextWithStableTokens() {
    final String text = "mail jane@example.com or jane@example.com";
    final PiiRewrite rewrite = TOKENIZER.rewrite(text, EXTRACTOR.extract(text));
    final String token = TOKENIZER.token(PiiMention.TYPE_EMAIL, "jane@example.com");

    Assertions.assertEquals("mail " + token + " or " + token, rewrite.text());
  }

  @Test
  void testRewriteOfTwoTextsAgreesOnTheSharedValue() {
    final String first = "from jane@example.com";
    final String second = "to jane@example.com please";

    final String token = TOKENIZER.token(PiiMention.TYPE_EMAIL, "jane@example.com");
    Assertions.assertTrue(TOKENIZER.rewrite(first, EXTRACTOR.extract(first)).text()
        .contains(token));
    Assertions.assertTrue(TOKENIZER.rewrite(second, EXTRACTOR.extract(second)).text()
        .contains(token));
  }

  @Test
  void testRewritesADocumentLayer() {
    final Document document = new PiiAnnotator(EXTRACTOR)
        .annotate(Document.of("mail jane@example.com now"));

    Assertions.assertEquals(
        "mail " + TOKENIZER.token(PiiMention.TYPE_EMAIL, "jane@example.com") + " now",
        TOKENIZER.rewrite(document).text());
  }

  @Test
  void testRejectsADocumentWithoutThePiiLayer() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> TOKENIZER.rewrite(Document.of("mail jane@example.com now")));
  }

  @Test
  void testRejectsNullRewriteArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> TOKENIZER.rewrite(null, List.of()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> TOKENIZER.rewrite("text", null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> TOKENIZER.rewrite((Document) null));
  }
}
