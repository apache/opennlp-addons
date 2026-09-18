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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Document;

/**
 * Tests {@link PiiAuditReport}.
 */
public class PiiAuditReportTest {

  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final HmacTokenizer TOKENIZER = new HmacTokenizer(KEY);
  private static final PiiExtractor EXTRACTOR = new CursorPiiExtractor();

  private static final String TEXT = "jane@example.com wrote, bob@example.com replied, "
      + "then jane@example.com again, card 4111111111111111, call (555) 123-4567";

  private static PiiAuditReport report(String text) {
    return PiiAuditReport.of(EXTRACTOR.extract(text), TOKENIZER);
  }

  @Test
  void testCountsMentionsByType() {
    final PiiAuditReport report = report(TEXT);

    Assertions.assertEquals(Map.of(PiiMention.TYPE_EMAIL, 3, PiiMention.TYPE_CARD, 1,
        PiiMention.TYPE_PHONE, 1), report.counts());
    Assertions.assertEquals(5, report.total());
  }

  @Test
  void testCountsDistinctValuesSeparately() {
    final PiiAuditReport report = report(TEXT);

    Assertions.assertEquals(3, report.counts().get(PiiMention.TYPE_EMAIL));
    Assertions.assertEquals(2, report.distinctCounts().get(PiiMention.TYPE_EMAIL));
  }

  /** Verifies that display-token collisions do not collapse distinct value counts. */
  @Test
  void testCountsDistinctValuesEvenWhenShortTokensCollide() {
    final HmacTokenizer shortTokenizer = new HmacTokenizer(KEY, 4);
    final List<PiiMention> mentions = List.of(
        new PiiMention(new opennlp.tools.util.Span(0, 1), PiiMention.TYPE_EMAIL,
            "u165@example.com"),
        new PiiMention(new opennlp.tools.util.Span(2, 3), PiiMention.TYPE_EMAIL,
            "u488@example.com"));

    Assertions.assertEquals(shortTokenizer.token(mentions.get(0)),
        shortTokenizer.token(mentions.get(1)));
    Assertions.assertEquals(2,
        PiiAuditReport.of(mentions, shortTokenizer).distinctCounts().get(PiiMention.TYPE_EMAIL));
  }

  @Test
  void testNamesTheTypesItFound() {
    Assertions.assertEquals(
        List.of(PiiMention.TYPE_CARD, PiiMention.TYPE_EMAIL, PiiMention.TYPE_PHONE),
        List.copyOf(report(TEXT).types()));
  }

  @Test
  void testAbsentTypeIsAbsentRatherThanZero() {
    final PiiAuditReport report = report("mail jane@example.com");

    Assertions.assertFalse(report.counts().containsKey(PiiMention.TYPE_CARD));
    Assertions.assertEquals(List.of(), report.samples(PiiMention.TYPE_CARD));
  }

  @Test
  void testReportsNothingForATextWithoutPii() {
    final PiiAuditReport report = report("nothing to see here");

    Assertions.assertEquals(0, report.total());
    Assertions.assertEquals(Map.of(), report.counts());
    Assertions.assertEquals("no pii found", report.toString());
  }

  /**
   * The point of the whole class: whatever a report says, it must not say the values.
   */
  @Test
  void testHoldsNoRawValue() {
    final String rendered = report(TEXT).toString();

    for (final String value : new String[] {"jane", "example.com", "4111111111111111",
        "555", "123-4567", "bob"}) {
      Assertions.assertFalse(rendered.contains(value), "leaked " + value + ": " + rendered);
    }
  }

  @Test
  void testSamplesAreTheKeyedTokensOfTheValues() {
    final PiiAuditReport report = report(TEXT);

    Assertions.assertEquals(
        List.of(TOKENIZER.token(PiiMention.TYPE_EMAIL, "jane@example.com"),
            TOKENIZER.token(PiiMention.TYPE_EMAIL, "bob@example.com")),
        report.samples(PiiMention.TYPE_EMAIL));
  }

  /**
   * Verifies the property that makes a token worth reporting: the same value in a report and
   * in a tokenized copy of the text carries the same token, so the two can be lined up.
   */
  @Test
  void testSamplesAgreeWithATokenizedRewrite() {
    final List<PiiMention> mentions = EXTRACTOR.extract(TEXT);
    final String rewritten = TOKENIZER.rewrite(TEXT, mentions).text();

    for (final String sample : PiiAuditReport.of(mentions, TOKENIZER)
        .samples(PiiMention.TYPE_EMAIL)) {
      Assertions.assertTrue(rewritten.contains(sample), sample);
    }
  }

  @Test
  void testRepeatedValueIsSampledOnce() {
    Assertions.assertEquals(2, report(TEXT).samples(PiiMention.TYPE_EMAIL).size());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 10})
  void testKeepsAtMostTheRequestedNumberOfSamples(int limit) {
    final String text = "a1@example.com b1@example.com c1@example.com "
        + "d1@example.com e1@example.com";

    final PiiAuditReport report = PiiAuditReport.of(EXTRACTOR.extract(text), TOKENIZER, limit);
    Assertions.assertEquals(Math.min(limit, 5), report.samples(PiiMention.TYPE_EMAIL).size());
    Assertions.assertEquals(5, report.distinctCounts().get(PiiMention.TYPE_EMAIL));
  }

  @Test
  void testTwoReportsOfTheSameTextAreIdentical() {
    Assertions.assertEquals(report(TEXT).toString(), report(TEXT).toString());
  }

  @Test
  void testDifferentKeysGiveDifferentSamples() {
    final List<PiiMention> mentions = EXTRACTOR.extract(TEXT);
    final HmacTokenizer other = new HmacTokenizer(
        "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));

    Assertions.assertNotEquals(
        PiiAuditReport.of(mentions, TOKENIZER).samples(PiiMention.TYPE_EMAIL),
        PiiAuditReport.of(mentions, other).samples(PiiMention.TYPE_EMAIL));
  }

  @Test
  void testFormatsOneLinePerType() {
    final String[] lines = report(TEXT).toString().split(System.lineSeparator());

    Assertions.assertEquals(3, lines.length);
    Assertions.assertTrue(lines[1].startsWith("email: 3 mentions, 2 distinct"), lines[1]);
  }

  @Test
  void testReportsOnADocumentLayer() {
    final Document document = new PiiAnnotator(EXTRACTOR).annotate(Document.of(TEXT));

    Assertions.assertEquals(report(TEXT).counts(),
        PiiAuditReport.of(document, TOKENIZER).counts());
  }

  @Test
  void testReportsOnAWidePackWithoutSurprises() {
    final String text = "key AKIAIOSFODNN7EXAMPLE host 10.0.0.7 host 10.0.0.7";
    final PiiAuditReport report = PiiAuditReport.of(
        PiiPacks.allStructured().extract(text), TOKENIZER);

    Assertions.assertEquals(1, report.counts().get(PiiMention.TYPE_AWS_ACCESS_KEY));
    Assertions.assertEquals(2, report.counts().get(PiiMention.TYPE_IPV4));
    Assertions.assertEquals(1, report.distinctCounts().get(PiiMention.TYPE_IPV4));
  }

  @Test
  void testCountsAreImmutable() {
    final PiiAuditReport report = report(TEXT);

    Assertions.assertThrows(UnsupportedOperationException.class,
        () -> report.counts().put(PiiMention.TYPE_EMAIL, 99));
    Assertions.assertThrows(UnsupportedOperationException.class,
        () -> report.distinctCounts().put(PiiMention.TYPE_EMAIL, 99));
    Assertions.assertThrows(UnsupportedOperationException.class,
        () -> report.samples(PiiMention.TYPE_EMAIL).add("EMAIL-00000000"));
  }

  @Test
  void testRejectsBadArguments() {
    final List<PiiMention> withNull = new ArrayList<>();
    withNull.add(null);

    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PiiAuditReport.of((List<PiiMention>) null, TOKENIZER));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PiiAuditReport.of(List.of(), null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PiiAuditReport.of(withNull, TOKENIZER));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PiiAuditReport.of(List.of(), TOKENIZER, -1));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PiiAuditReport.of((Document) null, TOKENIZER));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PiiAuditReport.of(Document.of("no layer"), TOKENIZER));
  }

  @Test
  void testRejectsANullTypeInSamples() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> report(TEXT).samples(null));
  }
}
