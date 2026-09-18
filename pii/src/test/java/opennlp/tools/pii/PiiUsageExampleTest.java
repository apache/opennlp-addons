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
import java.security.SecureRandom;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.LayerKey;
import opennlp.tools.util.Span;

/**
 * Demonstrates the end-to-end PII flow on one realistic text that contains an email
 * address, a phone number, and a payment card number: annotate the document, read the
 * PII layer back with exact spans, types, and normalized forms, and finally produce a
 * masked copy of the text in which only the detected spans are replaced.
 */
public class PiiUsageExampleTest {

  /**
   * The example text; it holds exactly one mention of each of the three types.
   */
  private static final String TEXT =
      "Contact jane@example.com, call (555) 123-4567, or charge card 4111 1111 1111 1111.";

  private static final String REPEATED_ADDRESSES =
      "Contact jane@example.com; jane@example.com replied to bob@example.com.";

  private final PiiAnnotator annotator = new PiiAnnotator(new CursorPiiExtractor());

  /**
   * Annotates the example text and verifies the complete PII layer: three mentions in
   * text order, each with its exact span offsets, the exact text the span covers, the
   * expected type constant, and the exact normalized form.
   */
  @Test
  void testAnnotateAndReadPiiLayer() {
    final Document document = annotator.annotate(Document.of(TEXT));

    Assertions.assertTrue(document.layers().contains(PiiAnnotator.PII));
    final List<Annotation<PiiMention>> mentions = document.get(PiiAnnotator.PII);
    Assertions.assertEquals(3, mentions.size());
    assertMention(mentions.get(0), 8, 24,
        PiiMention.TYPE_EMAIL, "jane@example.com", "jane@example.com");
    assertMention(mentions.get(1), 31, 45,
        PiiMention.TYPE_PHONE, "(555) 123-4567", "5551234567");
    assertMention(mentions.get(2), 62, 81,
        PiiMention.TYPE_CARD, "4111 1111 1111 1111", "4111111111111111");
  }

  /**
   * Masks the annotated document and verifies the exact redacted string: every
   * character inside a detected span becomes the mask character, every character
   * outside the spans is unchanged, and the overall length is preserved.
   */
  @Test
  void testMaskProducesExactRedactedText() {
    final Document document = annotator.annotate(Document.of(TEXT));

    final String masked = Masker.mask(document, PiiAnnotator.PII, '*');

    Assertions.assertEquals(TEXT.length(), masked.length());
    Assertions.assertEquals(
        "Contact ****************, call **************, or charge card *******************.",
        masked);
  }

  /**
   * Masks the same document under a policy that keeps separators visible and leaves
   * the last four letters or digits of each span readable, the customary receipt
   * style, and verifies the exact redacted string.
   */
  @Test
  void testMaskWithAReceiptStylePolicy() {
    final Document document = annotator.annotate(Document.of(TEXT));

    final String masked = Masker.mask(document, PiiAnnotator.PII,
        MaskPolicy.of('*').keepingFormat().keepingTrailing(4));

    Assertions.assertEquals(TEXT.length(), masked.length());
    Assertions.assertEquals(
        "Contact ****@******e.com, call (***) ***-4567, or charge card **** **** **** 1111.",
        masked);
  }

  /**
   * Masks the same document with the type-aware defaults, which choose a policy per
   * mention: the card keeps the last four digits a receipt needs while the address and the
   * phone number keep only their shape.
   */
  @Test
  void testMaskWithTypeAwareDefaults() {
    final Document document = annotator.annotate(Document.of(TEXT));

    final String masked = Masker.mask(document, PiiAnnotator.PII, MaskPolicies.byType());

    Assertions.assertEquals(TEXT.length(), masked.length());
    Assertions.assertEquals(
        "Contact ****@*******.***, call (***) ***-****, or charge card **** **** **** 1111.",
        masked);
  }

  /** Checks the manual's example of overlapping custom span layers. */
  @Test
  void testMaskOverlappingLayers() {
    final LayerKey<String> left = LayerKey.of("left", String.class);
    final LayerKey<String> right = LayerKey.of("right", String.class);
    final Document overlaps = Document.of("123456")
        .with(left, List.of(new Annotation<>(new Span(0, 4), "1234")))
        .with(right, List.of(new Annotation<>(new Span(2, 6), "3456")));
    final MaskPolicy policy = MaskPolicy.of('*').keepingTrailing(2);

    Assertions.assertEquals("****56", Masker.mask(overlaps, List.of(right, left), policy));
    Assertions.assertEquals("****56", Masker.mask(overlaps, List.of(left, right), policy));
    Assertions.assertEquals("123456", overlaps.text());
    Assertions.assertEquals(new Span(0, 4), overlaps.get(left).getFirst().span());
    Assertions.assertEquals(new Span(2, 6), overlaps.get(right).getFirst().span());
  }

  /**
   * Turns on a detector that is off by default. The opt-in packs are composed with the
   * default extractor, and the result reports every type in one pass.
   */
  @Test
  void testOptInPackFindsWhatTheDefaultExtractorDoesNot() {
    final String text = "Deploy from 10.0.0.7 using AKIAIOSFODNN7EXAMPLE, ask jane@example.com.";

    final List<PiiMention> mentions = PiiPacks.allStructured().extract(text);

    Assertions.assertEquals(List.of(PiiMention.TYPE_IPV4, PiiMention.TYPE_AWS_ACCESS_KEY,
        PiiMention.TYPE_EMAIL), mentions.stream().map(PiiMention::type).toList());
    Assertions.assertEquals(List.of(), new CursorPiiExtractor().extract(text).stream()
        .map(PiiMention::type).filter(type -> !PiiMention.TYPE_EMAIL.equals(type)).toList());
  }

  /**
   * Replaces the mentions with numbered labels instead of masking them. The text stays
   * readable and the repeated address keeps one label, which masking would hide.
   */
  @Test
  void testPseudonymizeKeepsTheTextReadable() {
    final String text = REPEATED_ADDRESSES;

    final PiiRewrite rewrite = new Pseudonymizer()
        .rewrite(text, new CursorPiiExtractor().extract(text));

    Assertions.assertEquals("Contact EMAIL-1; EMAIL-1 replied to EMAIL-2.", rewrite.text());
    Assertions.assertEquals(rewrite.text().indexOf("EMAIL-2"),
        rewrite.mapOffset(text.indexOf("bob@example.com")));
  }

  /** Builds the manual's PII layer from replacement labels and their new offsets. */
  @Test
  void testBuildsLabelLayerAfterRewriting() {
    final String text = REPEATED_ADDRESSES;
    final PiiRewrite rewrite = new Pseudonymizer()
        .rewrite(text, new CursorPiiExtractor().extract(text));
    final Document labelled = Document.of(rewrite.text()).with(PiiAnnotator.PII,
        rewrite.mentions().stream()
            .map(mention -> new Annotation<>(mention.span(), mention)).toList());

    Assertions.assertEquals("Contact EMAIL-1; EMAIL-1 replied to EMAIL-2.", labelled.text());
    Assertions.assertEquals(List.of("EMAIL-1", "EMAIL-1", "EMAIL-2"),
        labelled.get(PiiAnnotator.PII).stream().map(annotation -> annotation.value().normalized()).toList());
    for (final Annotation<PiiMention> annotation : labelled.get(PiiAnnotator.PII)) {
      Assertions.assertEquals(annotation.span(), annotation.value().span());
      Assertions.assertEquals(annotation.value().normalized(),
          rewrite.text().substring(annotation.span().getStart(), annotation.span().getEnd()));
    }
    Assertions.assertEquals(rewrite.text(), new Pseudonymizer().rewrite(labelled).text());
  }

  /** Checks the manual's custom type numbering example. */
  @Test
  void testNumberingForCustomTypes() {
    final List<PiiMention> custom = List.of(
        new PiiMention(new Span(0, 1), "id", "a"),
        new PiiMention(new Span(2, 3), "ID", "b"));

    Assertions.assertEquals("ID-1 ID-2", new Pseudonymizer().rewrite("a b", custom).text());
  }

  /** Checks the manual's generated-key example across documents and audit samples. */
  @Test
  void testStableTokensAcrossDocuments() {
    final byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    final HmacTokenizer tokenizer = new HmacTokenizer(key);
    final PiiAnnotator emailAnnotator = new PiiAnnotator(new CursorPiiExtractor());
    final Document first = emailAnnotator.annotate(Document.of("From jane@example.com"));
    final Document second = emailAnnotator.annotate(Document.of("To jane@example.com"));
    final String stable = tokenizer.token(PiiMention.TYPE_EMAIL, "jane@example.com");

    Assertions.assertEquals("From " + stable, tokenizer.rewrite(first).text());
    Assertions.assertEquals("To " + stable, tokenizer.rewrite(second).text());
    final PiiAuditReport report = PiiAuditReport.of(first, tokenizer);
    Assertions.assertEquals(1, report.total());
    Assertions.assertEquals(1, report.counts().get(PiiMention.TYPE_EMAIL));
    Assertions.assertEquals(1, report.distinctCounts().get(PiiMention.TYPE_EMAIL));
    Assertions.assertEquals(List.of(stable), report.samples(PiiMention.TYPE_EMAIL));
    Assertions.assertEquals(List.of(stable),
        PiiAuditReport.of(second, tokenizer).samples(PiiMention.TYPE_EMAIL));
    Assertions.assertEquals(stable,
        new HmacTokenizer(key).token(PiiMention.TYPE_EMAIL, "jane@example.com"));
  }

  /**
   * Reports on a scan without holding any of the values, the artefact a review can be given.
   */
  @Test
  void testAuditReportCountsWithoutRevealing() {
    final HmacTokenizer tokenizer = new HmacTokenizer(
        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    final Document document = annotator.annotate(Document.of(TEXT));

    final PiiAuditReport report = PiiAuditReport.of(document, tokenizer);

    Assertions.assertEquals(3, report.total());
    Assertions.assertEquals(1, report.counts().get(PiiMention.TYPE_CARD));
    Assertions.assertFalse(report.toString().contains("jane"));
    Assertions.assertFalse(report.toString().contains("4111"));
  }

  /**
   * Verifies one annotation of the PII layer against its expected span offsets in
   * {@link #TEXT}, the text those offsets cover, and the type and normalized form of
   * the carried {@link PiiMention}. Also verifies that the annotation span and the
   * mention span agree, since downstream consumers may read either one.
   *
   * @param annotation The annotation to verify. Must not be {@code null}.
   * @param start The expected span start, inclusive.
   * @param end The expected span end, exclusive.
   * @param type The expected mention type.
   * @param covered The exact text the span is expected to cover.
   * @param normalized The exact expected normalized form.
   */
  private void assertMention(Annotation<PiiMention> annotation, int start, int end,
      String type, String covered, String normalized) {
    Assertions.assertEquals(start, annotation.span().getStart());
    Assertions.assertEquals(end, annotation.span().getEnd());
    Assertions.assertEquals(annotation.span(), annotation.value().span());
    Assertions.assertEquals(covered, TEXT.substring(start, end));
    Assertions.assertEquals(type, annotation.value().type());
    Assertions.assertEquals(normalized, annotation.value().normalized());
  }
}
