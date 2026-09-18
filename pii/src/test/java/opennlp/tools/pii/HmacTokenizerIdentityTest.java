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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Tests lossless token input and stable UTF-8 token encoding. */
class HmacTokenizerIdentityTest {

  private static final String TYPE = "id";
  private static final String VALUE = "private-value";
  private static final HmacTokenizer TOKENIZER = new HmacTokenizer(new byte[32], 64);

  private enum EntryPoint {
    DIRECT, MENTION, TEXT, DOCUMENT, AUDIT
  }

  /**
   * Provides malformed UTF-16 at the beginning, middle and end of each field.
   *
   * @return Case description, malformed field, field selection and entry point.
   */
  private static Stream<Arguments> malformedInputs() {
    final List<Arguments> cases = new ArrayList<>();
    final List<String> fragments = List.of("\ud800", "\udbff", "\udc00", "\udfff",
        "\ud800\ud800", "\udc00\ud800", "\udfff\udfff", "\ud800x\udc00");
    for (int i = 0; i < fragments.size(); i++) {
      final String fragment = fragments.get(i);
      final List<String> inputs = List.of(fragment + VALUE, VALUE + fragment,
          "private-" + fragment + "-value");
      for (int position = 0; position < inputs.size(); position++) {
        for (final boolean inType : new boolean[] {false, true}) {
          for (final EntryPoint entry : EntryPoint.values()) {
            cases.add(Arguments.of(i + ":" + position, inputs.get(position), inType, entry));
          }
        }
      }
    }
    return cases.stream();
  }

  /**
   * Rejects malformed fields before they become a token, rewrite or audit sample.
   *
   * @param description The printable case identifier.
   * @param malformed The malformed field.
   * @param inType Whether the type contains the malformed field.
   * @param entry The entry point.
   */
  @ParameterizedTest(name = "{0}, type={2}, via={3}")
  @MethodSource("malformedInputs")
  void testRejectsUnpairedSurrogates(String description, String malformed, boolean inType, EntryPoint entry) {
    final String type = inType ? malformed : TYPE;
    final String value = inType ? VALUE : malformed;
    final PiiMention mention = new PiiMention(new Span(0, 1), type, value);
    final Document document = Document.of("x").with(PiiAnnotator.PII,
        List.of(new Annotation<>(mention.span(), mention)));

    final IllegalArgumentException failure = Assertions.assertThrows(IllegalArgumentException.class, () -> {
      switch (entry) {
        case DIRECT -> TOKENIZER.token(type, value);
        case MENTION -> TOKENIZER.token(mention);
        case TEXT -> TOKENIZER.rewrite("x", List.of(mention));
        case DOCUMENT -> TOKENIZER.rewrite(document);
        case AUDIT -> PiiAuditReport.of(document, TOKENIZER);
        default -> throw new AssertionError(entry);
      }
    }, description);
    Assertions.assertFalse(failure.getMessage().contains("private"));
  }

  /**
   * Pins malformed inputs that otherwise have the same UTF-8 bytes as a question mark.
   *
   * @param unit The unpaired UTF-16 code unit.
   */
  @ParameterizedTest
  @ValueSource(ints = {0xd800, 0xdbff, 0xdc00, 0xdfff})
  void testRejectsReplacementByteAliases(int unit) {
    final String malformed = "a" + (char) unit + "b";
    Assertions.assertArrayEquals("a?b".getBytes(StandardCharsets.UTF_8),
        malformed.getBytes(StandardCharsets.UTF_8));
    Assertions.assertDoesNotThrow(() -> TOKENIZER.token(TYPE, "a?b"));
    Assertions.assertThrows(IllegalArgumentException.class, () -> TOKENIZER.token(TYPE, malformed));
  }

  /**
   * Provides valid UTF-16 boundaries, noncharacters, controls and normalization variants.
   *
   * @return The type, normalized value, displayed prefix and token length.
   */
  private static Stream<Arguments> validInputs() {
    final List<Arguments> cases = new ArrayList<>();
    final List<String> values = List.of("value", "?", "\ufffd", "\ud7ff", "\ue000", "\uffff",
        "\ud800\udc00", "\udbff\udfff", "😀𝕒", "é", "e\u0301", "a\u0000b", "a\nb");
    final List<String> prefixes = List.of("VALUE", "?", "\ufffd", "\ud7ff", "\ue000", "\uffff",
        "\ud800\udc00", "\udbff\udfff", "😀𝕒", "é", "E\u0301", "A\u0000B", "A\nB");
    for (int i = 0; i < values.size(); i++) {
      for (final int length : new int[] {4, 5, 16, 63, 64}) {
        cases.add(Arguments.of(TYPE, values.get(i), "ID", length));
        cases.add(Arguments.of(values.get(i), VALUE, prefixes.get(i), length));
      }
    }
    return cases.stream();
  }

  /**
   * Compares valid token output with independently assembled JDK HMAC input.
   *
   * @param type The valid type.
   * @param value The valid normalized value.
   * @param prefix The displayed type prefix.
   * @param length The token length.
   * @throws Exception If the JDK MAC provider cannot initialize.
   */
  @ParameterizedTest(name = "valid input {index}, length={3}")
  @MethodSource("validInputs")
  void testValidTokensKeepTheirEncoding(String type, String value, String prefix, int length)
      throws Exception {
    final Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
    mac.update(type.getBytes(StandardCharsets.UTF_8));
    mac.update((byte) 0);
    final String digest = HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    final HmacTokenizer tokenizer = new HmacTokenizer(new byte[32], length);
    Assertions.assertEquals(prefix + '-' + digest.substring(0, length), tokenizer.token(type, value));
  }

  /**
   * Checks that reports validate token input only for the values they sample.
   *
   * @param unit The unpaired code unit in an unsampled mention.
   */
  @ParameterizedTest
  @ValueSource(ints = {0xd800, 0xdbff, 0xdc00, 0xdfff})
  void testUnsampledValuesDoNotRequireEncoding(int unit) {
    final PiiMention valid = new PiiMention(new Span(0, 1), TYPE, VALUE);
    final PiiMention malformed = new PiiMention(new Span(2, 3), TYPE, "a" + (char) unit);
    final List<PiiMention> mentions = List.of(valid, malformed);
    final PiiAuditReport sampled = PiiAuditReport.of(mentions, TOKENIZER, 1);
    final PiiAuditReport countsOnly = PiiAuditReport.of(mentions, TOKENIZER, 0);

    Assertions.assertEquals(2, sampled.distinctCounts().get(TYPE));
    Assertions.assertEquals(List.of(TOKENIZER.token(valid)), sampled.samples(TYPE));
    Assertions.assertEquals(sampled.counts(), countsOnly.counts());
    Assertions.assertEquals(sampled.distinctCounts(), countsOnly.distinctCounts());
    Assertions.assertEquals(List.of(), countsOnly.samples(TYPE));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PiiAuditReport.of(mentions, TOKENIZER));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PiiAuditReport.of(mentions, TOKENIZER, 2));
  }

  /**
   * Checks token, document and audit calls on a shared tokenizer.
   *
   * @throws Exception If a worker fails or does not finish within the time limit.
   */
  @Test
  @Timeout(60)
  void testConcurrentTokenization() throws Exception {
    final CountDownLatch ready = new CountDownLatch(8);
    final List<Callable<Void>> calls = new ArrayList<>();
    for (int worker = 0; worker < 8; worker++) {
      final String value = VALUE + worker + "😀";
      final PiiMention mention = new PiiMention(new Span(0, 1), TYPE, value);
      final Document document = Document.of("x").with(PiiAnnotator.PII,
          List.of(new Annotation<>(mention.span(), mention)));
      final String expected = TOKENIZER.token(mention);
      calls.add(() -> {
        ready.countDown();
        Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
        for (int run = 0; run < 32; run++) {
          Assertions.assertEquals(expected, TOKENIZER.token(mention));
          Assertions.assertEquals(expected, TOKENIZER.rewrite(document).text());
          Assertions.assertEquals(List.of(expected), PiiAuditReport.of(document, TOKENIZER).samples(TYPE));
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
