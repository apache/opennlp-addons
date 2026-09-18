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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Checks that adding detectors preserves the text covered by a contact match. */
class PiiPackCoverageTest {

  private static final HmacTokenizer TOKENIZER = new HmacTokenizer(
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

  /**
   * Provides overlapping URL-credential/email matches and a separate-email control.
   *
   * @return The input text and complete contact address.
   */
  private static Stream<Arguments> examples() {
    return Stream.of("", "Contact: ", "😀 ", "e\u0301 ").flatMap(prefix -> Stream.of(
        Arguments.of(prefix + "https://john:pass@example.com/path", "pass@example.com"),
        Arguments.of(prefix + "https://john:pa.ss@example.com/path", "pa.ss@example.com"),
        Arguments.of(prefix + "https://john:pass@example.com/path jane@example.org",
            "jane@example.org")));
  }

  /**
   * Checks exact narrow detection and all covered characters after adding other packs.
   *
   * @param text The text to annotate.
   * @param address The email address that the contact pack detects.
   */
  @ParameterizedTest
  @MethodSource("examples")
  void testAllStructuredPreservesContactCoverage(String text, String address) {
    final int start = text.indexOf(address);
    final PiiMention expected = new PiiMention(new Span(start, start + address.length()),
        PiiMention.TYPE_EMAIL, address);
    final List<PiiMention> contacts = PiiPacks.contact().extract(text);
    Assertions.assertTrue(contacts.contains(expected), () -> "contact matches: " + contacts);

    final Document document = new PiiAnnotator(PiiPacks.allStructured())
        .annotate(Document.of(text));
    final List<PiiMention> all = document.get(PiiAnnotator.PII).stream()
        .map(Annotation::value).toList();
    Assertions.assertTrue(all.contains(expected), () -> "allStructured matches: " + all);
    final String masked = Masker.mask(document, PiiAnnotator.PII, '*');
    Assertions.assertEquals("*".repeat(address.length()),
        masked.substring(start, start + address.length()),
        () -> "allStructured left contact text visible: " + masked);
  }

  /** Checks that masking retains coverage when both original mentions are available. */
  @Test
  void testMaskerCoversCrossingMentions() {
    final Document document = crossingMentions();
    Assertions.assertEquals("https://" + "*".repeat("john:pass@example.com".length()) + "/path",
        Masker.mask(document, PiiAnnotator.PII, '*'));
  }

  /** Checks extraction, annotation, masking and audit counts for overlapping detections. */
  @Test
  void testAllStructuredRetainsCrossingMentionsEndToEnd() {
    final String text = "https://john:pass@example.com/path";
    final Span credential = new Span(8, 17);
    final Span email = new Span(13, 29);
    final List<PiiMention> expected = List.of(
        new PiiMention(credential, PiiMention.TYPE_URL_CREDENTIAL, "john:pass"),
        new PiiMention(email, PiiMention.TYPE_EMAIL, "pass@example.com"));

    final List<PiiMention> extracted = PiiPacks.allStructured().extract(text);
    final Document document = new PiiAnnotator(PiiPacks.allStructured())
        .annotate(Document.of(text));

    Assertions.assertEquals(expected, extracted);
    Assertions.assertEquals(expected, document.get(PiiAnnotator.PII).stream()
        .map(Annotation::value).toList());
    Assertions.assertEquals("https://" + "*".repeat("john:pass@example.com".length())
        + "/path", Masker.mask(document, PiiAnnotator.PII, '*'));
    final PiiAuditReport report = PiiAuditReport.of(document, TOKENIZER);
    Assertions.assertEquals(2, report.total());
    Assertions.assertEquals(Map.of(PiiMention.TYPE_URL_CREDENTIAL, 1,
        PiiMention.TYPE_EMAIL, 1), report.counts());
  }

  /** Checks that all label replacements reject overlapping detections. */
  @Test
  void testLabelReplacementRequiresNonOverlappingMentions() {
    final Document document = crossingMentions();
    final List<PiiMention> mentions = document.get(PiiAnnotator.PII).stream()
        .map(Annotation::value).toList();
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new Pseudonymizer().rewrite(document));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> TOKENIZER.rewrite(document));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PiiRewrite.replace(document.text(), mentions, ignored -> "LABEL"));
  }

  /**
   * Creates a document with the original credential and email spans.
   *
   * @return The annotated document.
   */
  private Document crossingMentions() {
    final String text = "https://john:pass@example.com/path";
    final Span credential = new Span(8, 17);
    final Span email = new Span(13, 29);
    return Document.of(text).with(PiiAnnotator.PII, List.of(
        new Annotation<>(credential,
            new PiiMention(credential, PiiMention.TYPE_URL_CREDENTIAL, "john:pass")),
        new Annotation<>(email,
            new PiiMention(email, PiiMention.TYPE_EMAIL, "pass@example.com"))));
  }
}
