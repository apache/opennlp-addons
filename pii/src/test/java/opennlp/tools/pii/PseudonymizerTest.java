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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.LayerKey;
import opennlp.tools.util.Span;

/**
 * Tests {@link Pseudonymizer} and the {@link PiiRewrite} it produces.
 */
public class PseudonymizerTest {

  private static final PiiExtractor EXTRACTOR = new CursorPiiExtractor();
  private static final Pseudonymizer PSEUDONYMIZER = new Pseudonymizer();

  private static PiiRewrite rewrite(String text) {
    return PSEUDONYMIZER.rewrite(text, EXTRACTOR.extract(text));
  }

  @Test
  void testReplacesAMentionWithALabel() {
    Assertions.assertEquals("write to EMAIL-1 today",
        rewrite("write to jane@example.com today").text());
  }

  @Test
  void testLeavesATextWithoutPiiUntouched() {
    final String text = "nothing to see here";

    final PiiRewrite rewrite = rewrite(text);
    Assertions.assertEquals(text, rewrite.text());
    Assertions.assertEquals(List.of(), rewrite.mentions());
    Assertions.assertEquals(7, rewrite.mapOffset(7));
  }

  @Test
  void testNumbersEachTypeSeparately() {
    Assertions.assertEquals("EMAIL-1 EMAIL-2 PHONE-1 EMAIL-3",
        rewrite("jane@example.com bob@example.com (555) 123-4567 eve@example.com").text());
  }

  @Test
  void testRepeatedValueKeepsItsLabel() {
    Assertions.assertEquals("EMAIL-1 wrote, EMAIL-2 replied, then EMAIL-1 again",
        rewrite("jane@example.com wrote, bob@example.com replied, "
            + "then jane@example.com again").text());
  }

  /**
   * Verifies that formatting does not split a label: the same card written two ways is one
   * value, since the label follows the normalized form.
   */
  @Test
  void testDifferentlyFormattedSameValueSharesALabel() {
    Assertions.assertEquals("CARD-1 and CARD-1",
        rewrite("4111 1111 1111 1111 and 4111-1111-1111-1111").text());
  }

  /** Verifies that structured type-value identities cannot alias at a delimiter. */
  @Test
  void testTypeAndValuePairsCannotAliasThroughTheirSeparator() {
    final List<PiiMention> mentions = List.of(
        new PiiMention(new Span(0, 1), "a", "b\u0000c"),
        new PiiMention(new Span(2, 3), "a\u0000b", "c"));

    final PiiRewrite rewrite = PSEUDONYMIZER.rewrite("x y", mentions);
    Assertions.assertNotEquals(rewrite.mentions().get(0).normalized(),
        rewrite.mentions().get(1).normalized());
  }

  @Test
  void testDifferentValuesOfOneTypeGetDifferentLabels() {
    final PiiRewrite rewrite = rewrite("4111111111111111 and 5500005555555559");

    Assertions.assertEquals("CARD-1 and CARD-2", rewrite.text());
  }

  /** Verifies that mailbox local-part case remains part of pseudonym identity. */
  @Test
  void testCaseDistinctMailboxLocalPartsGetDifferentLabels() {
    Assertions.assertEquals("EMAIL-1 EMAIL-2",
        rewrite("User@example.com user@example.com").text());
  }

  @ParameterizedTest
  @CsvSource({
      "'mail jane@example.com', EMAIL-1",
      "'call (555) 123-4567', PHONE-1",
      "'card 4111111111111111', CARD-1",
      "'iban DE89370400440532013000', IBAN-1",
  })
  void testLabelNamesTheType(String text, String expected) {
    Assertions.assertEquals(expected, rewrite(text).mentions().get(0).normalized());
  }

  @Test
  void testNumberingRestartsWithEveryRewrite() {
    final String text = "jane@example.com";

    Assertions.assertEquals("EMAIL-1", rewrite(text).text());
    Assertions.assertEquals("EMAIL-1", rewrite(text).text());
  }

  @Test
  void testSurroundsLabelsWhenAsked() {
    final String text = "write to jane@example.com today";

    Assertions.assertEquals("write to [EMAIL-1] today",
        new Pseudonymizer("[", "]").rewrite(text, EXTRACTOR.extract(text)).text());
  }

  @Test
  void testRejectsNullSurroundings() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> new Pseudonymizer(null, ""));
    Assertions.assertThrows(IllegalArgumentException.class, () -> new Pseudonymizer("", null));
  }

  @Test
  void testMentionsPointAtTheLabelsInTheRewrittenText() {
    final PiiRewrite rewrite = rewrite("mail jane@example.com or bob@example.com now");

    Assertions.assertEquals(2, rewrite.mentions().size());
    for (final PiiMention mention : rewrite.mentions()) {
      Assertions.assertEquals(mention.normalized(), rewrite.text().substring(
          mention.span().getStart(), mention.span().getEnd()));
      Assertions.assertEquals(PiiMention.TYPE_EMAIL, mention.type());
    }
  }

  /**
   * Verifies that the rewritten mentions can be annotated back onto the rewritten text,
   * which is what makes the labels usable as a layer.
   */
  @Test
  void testRewrittenMentionsAreValidForTheRewrittenText() {
    final PiiRewrite rewrite = rewrite("jane@example.com called from (555) 123-4567");
    final Document document = Document.of(rewrite.text());

    final List<Annotation<PiiMention>> annotations = new ArrayList<>();
    for (final PiiMention mention : rewrite.mentions()) {
      annotations.add(new Annotation<>(mention.span(), mention));
    }
    final Document annotated = document.with(PiiAnnotator.PII, annotations);
    Assertions.assertEquals(2, annotated.get(PiiAnnotator.PII).size());
  }

  @Test
  void testRewritesADocumentLayer() {
    final Document document = new PiiAnnotator(EXTRACTOR)
        .annotate(Document.of("mail jane@example.com now"));

    Assertions.assertEquals("mail EMAIL-1 now", PSEUDONYMIZER.rewrite(document).text());
  }

  @Test
  void testRejectsADocumentWithoutThePiiLayer() {
    final Document document = Document.of("mail jane@example.com now");

    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PSEUDONYMIZER.rewrite(document));
  }

  @Test
  void testRejectsNullArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PSEUDONYMIZER.rewrite(null, List.of()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PSEUDONYMIZER.rewrite("text", null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PSEUDONYMIZER.rewrite((Document) null));
  }

  @Test
  void testRejectsAMentionListContainingNull() {
    final List<PiiMention> mentions = new ArrayList<>();
    mentions.add(null);

    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PSEUDONYMIZER.rewrite("text", mentions));
  }

  @Test
  void testRejectsASpanOutsideTheText() {
    final List<PiiMention> mentions = List.of(
        new PiiMention(new Span(2, 40), PiiMention.TYPE_EMAIL, "a@b.com"));

    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PSEUDONYMIZER.rewrite("short", mentions));
  }

  @Test
  void testRejectsOverlappingMentions() {
    final List<PiiMention> mentions = List.of(
        new PiiMention(new Span(0, 10), PiiMention.TYPE_EMAIL, "a@b.com"),
        new PiiMention(new Span(5, 15), PiiMention.TYPE_PHONE, "5551234567"));

    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PSEUDONYMIZER.rewrite("0123456789012345678", mentions));
  }

  @Test
  void testAcceptsAdjacentMentions() {
    final List<PiiMention> mentions = List.of(
        new PiiMention(new Span(0, 5), PiiMention.TYPE_EMAIL, "a@b.com"),
        new PiiMention(new Span(5, 10), PiiMention.TYPE_EMAIL, "c@d.com"));

    Assertions.assertEquals("EMAIL-1EMAIL-2",
        PSEUDONYMIZER.rewrite("0123456789", mentions).text());
  }

  @Test
  void testAcceptsMentionsInAnyOrder() {
    final List<PiiMention> mentions = List.of(
        new PiiMention(new Span(6, 11), PiiMention.TYPE_PHONE, "5551234567"),
        new PiiMention(new Span(0, 5), PiiMention.TYPE_EMAIL, "a@b.com"));

    Assertions.assertEquals("EMAIL-1 PHONE-1",
        PSEUDONYMIZER.rewrite("00000 11111", mentions).text());
  }

  @Test
  void testMapsOffsetsBeforeAndAfterAShorteningLabel() {
    final String text = "to jane@example.com now";
    final PiiRewrite rewrite = rewrite(text);

    Assertions.assertEquals("to EMAIL-1 now", rewrite.text());
    Assertions.assertEquals(0, rewrite.mapOffset(0));
    Assertions.assertEquals(3, rewrite.mapOffset(3));
    Assertions.assertEquals(11, rewrite.mapOffset(text.indexOf("now")));
    Assertions.assertEquals(rewrite.text().length(), rewrite.mapOffset(text.length()));
  }

  @Test
  void testMapsAnOffsetInsideAReplacedValueToTheLabelStart() {
    final String text = "to jane@example.com now";
    final PiiRewrite rewrite = rewrite(text);

    Assertions.assertEquals(3, rewrite.mapOffset(4));
    Assertions.assertEquals(3, rewrite.mapOffset(text.indexOf('@')));
  }

  @Test
  void testMapsOffsetsAcrossSeveralLabels() {
    final String text = "a jane@example.com b (555) 123-4567 z";
    final PiiRewrite rewrite = rewrite(text);

    Assertions.assertEquals("a EMAIL-1 b PHONE-1 z", rewrite.text());
    Assertions.assertEquals(rewrite.text().indexOf('b'), rewrite.mapOffset(text.indexOf('b')));
    Assertions.assertEquals(rewrite.text().indexOf('z'), rewrite.mapOffset(text.indexOf('z')));
  }

  /** Verifies binary-search mapping against a direct scan for every source offset. */
  @Test
  void testMapsEveryOffsetAcrossManyReplacements() {
    final String text = "xx ".repeat(1024);
    final List<PiiMention> mentions = new ArrayList<>();
    for (int start = 0; start < text.length(); start += 3) {
      mentions.add(new PiiMention(new Span(start, start + 2), PiiMention.TYPE_EMAIL,
          "value" + start));
    }
    final PiiRewrite rewrite = PSEUDONYMIZER.rewrite(text, mentions);

    for (int offset = 0; offset <= text.length(); offset++) {
      int expected = offset;
      int shift = 0;
      for (int i = 0; i < mentions.size(); i++) {
        final Span source = mentions.get(i).span();
        if (offset <= source.getStart()) {
          break;
        }
        if (offset < source.getEnd()) {
          expected = rewrite.mentions().get(i).span().getStart();
          break;
        }
        shift += rewrite.mentions().get(i).span().length() - source.length();
        expected = offset + shift;
      }
      Assertions.assertEquals(expected, rewrite.mapOffset(offset), "offset " + offset);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 24})
  void testRejectsAnOffsetOutsideTheOriginalText(int offset) {
    final PiiRewrite rewrite = rewrite("to jane@example.com now");

    Assertions.assertThrows(IndexOutOfBoundsException.class, () -> rewrite.mapOffset(offset));
  }

  @Test
  void testMapsASpanThatSurroundsAReplacedValue() {
    final String text = "to jane@example.com now";
    final PiiRewrite rewrite = rewrite(text);

    final Span mapped = rewrite.mapSpan(new Span(0, text.length()));
    Assertions.assertEquals(0, mapped.getStart());
    Assertions.assertEquals(rewrite.text().length(), mapped.getEnd());
  }

  @Test
  void testMapsASpanThatIsTheReplacedValue() {
    final String text = "to jane@example.com now";
    final PiiRewrite rewrite = rewrite(text);

    final Span mapped = rewrite.mapSpan(new Span(3, 19));
    Assertions.assertEquals("EMAIL-1", rewrite.text().substring(mapped.getStart(),
        mapped.getEnd()));
  }

  @Test
  void testMapsASpanInsideTheReplacedValueOntoTheWholeLabel() {
    final PiiRewrite rewrite = rewrite("to jane@example.com now");

    final Span mapped = rewrite.mapSpan(new Span(4, 6));
    Assertions.assertEquals("EMAIL-1", rewrite.text().substring(mapped.getStart(),
        mapped.getEnd()));
  }

  @Test
  void testMappedSpanKeepsTypeAndProbability() {
    final PiiRewrite rewrite = rewrite("to jane@example.com now");

    final Span mapped = rewrite.mapSpan(new Span(0, 2, "token", 0.5));
    Assertions.assertEquals("token", mapped.getType());
    Assertions.assertEquals(0.5, mapped.getProb());
  }

  @Test
  void testRejectsANullSpan() {
    final PiiRewrite rewrite = rewrite("to jane@example.com now");

    Assertions.assertThrows(IllegalArgumentException.class, () -> rewrite.mapSpan(null));
  }

  @Test
  void testRemapsAnnotationsOntoTheRewrittenText() {
    final LayerKey<String> tokens = LayerKey.of("test:tokens", String.class);
    final String text = "to jane@example.com now";
    final PiiRewrite rewrite = rewrite(text);

    final List<Annotation<String>> remapped = rewrite.remap(List.of(
        new Annotation<>(new Span(0, 2), "to"),
        new Annotation<>(new Span(3, 19), "address"),
        new Annotation<>(new Span(20, 23), "now")));
    final Document document = Document.of(rewrite.text()).with(tokens, remapped);
    Assertions.assertEquals(3, document.get(tokens).size());
    Assertions.assertEquals("now", rewrite.text().substring(
        remapped.get(2).span().getStart(), remapped.get(2).span().getEnd()));
  }

  @Test
  void testRemapKeepsAnnotationValues() {
    final PiiRewrite rewrite = rewrite("to jane@example.com now");

    final List<Annotation<String>> remapped = rewrite.remap(List.of(
        new Annotation<>(new Span(0, 2), "keep me")));
    Assertions.assertEquals("keep me", remapped.get(0).value());
  }

  @Test
  void testRemapRejectsNull() {
    final PiiRewrite rewrite = rewrite("to jane@example.com now");
    final List<Annotation<String>> withNull = new ArrayList<>();
    withNull.add(null);

    Assertions.assertThrows(IllegalArgumentException.class, () -> rewrite.remap(null));
    Assertions.assertThrows(IllegalArgumentException.class, () -> rewrite.remap(withNull));
  }

  @Test
  void testRewritingIsIdempotentOnItsOwnOutput() {
    final PiiRewrite once = rewrite("mail jane@example.com now");
    final PiiRewrite twice = rewrite(once.text());

    Assertions.assertEquals(once.text(), twice.text());
  }
}
