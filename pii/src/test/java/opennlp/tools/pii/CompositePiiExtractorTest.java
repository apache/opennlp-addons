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
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

public class CompositePiiExtractorTest {

  /**
   * A stub extractor that reports exactly the mentions it was built with, so overlap
   * resolution can be driven with spans no real scanner would produce together.
   */
  private static final class Fixed implements PiiExtractor {

    private final List<PiiMention> mentions;

    private Fixed(PiiMention... mentions) {
      this.mentions = List.of(mentions);
    }

    @Override
    public List<PiiMention> extract(CharSequence text) {
      return mentions;
    }
  }

  private static PiiMention mention(int start, int end, String type) {
    return new PiiMention(new Span(start, end), type, type + ":" + start);
  }

  @Test
  void testMergesMentionsOfSeveralExtractorsInTextOrder() {
    final PiiExtractor extractor = new CompositePiiExtractor(
        new CursorPiiExtractor(Set.of(PiiMention.TYPE_EMAIL)),
        new CursorPiiExtractor(Set.of(PiiMention.TYPE_CARD)));
    final String text = "card 4111 1111 1111 1111 for jane@example.com";

    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(2, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_CARD, mentions.get(0).type());
    Assertions.assertEquals(PiiMention.TYPE_EMAIL, mentions.get(1).type());
    Assertions.assertTrue(mentions.get(0).span().getEnd() <= mentions.get(1).span().getStart());
  }

  @Test
  void testEqualsTheSingleExtractorOnTheSameTypes() {
    final String text = "Mail jane@example.com, call (555) 123-4567, "
        + "IBAN DE89 3704 0044 0532 0130 00, card 4111 1111 1111 1111.";
    final List<PiiMention> single = new CursorPiiExtractor().extract(text);

    final List<PiiMention> composed = new CompositePiiExtractor(
        new CursorPiiExtractor(Set.of(PiiMention.TYPE_EMAIL)),
        new CursorPiiExtractor(Set.of(PiiMention.TYPE_IBAN)),
        new CursorPiiExtractor(Set.of(PiiMention.TYPE_CARD)),
        new CursorPiiExtractor(Set.of(PiiMention.TYPE_PHONE))).extract(text);

    Assertions.assertEquals(single, composed);
  }

  /** Checks that an exact duplicate deduplicates to the original candidate object. */
  @Test
  void testDeduplicatesTheSameMentionReportedTwice() {
    final PiiMention first = new PiiMention(new Span(9, 25), PiiMention.TYPE_EMAIL,
        "jane@example.com");
    final PiiMention duplicate = new PiiMention(new Span(9, 25), PiiMention.TYPE_EMAIL,
        "jane@example.com");
    final PiiExtractor extractor = new CompositePiiExtractor(new Fixed(first),
        new Fixed(duplicate));

    final List<PiiMention> mentions = extractor.extract("write to jane@example.com now");

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals("jane@example.com", mentions.get(0).normalized());
    Assertions.assertSame(first, mentions.getFirst());
  }

  /**
   * Checks overlapping candidates in both delegate orders.
   *
   * @param reverse Whether to place the earlier candidate in the earlier delegate.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testCrossingCandidatesAreRetainedInTextOrder(boolean reverse) {
    final PiiMention later = mention(4, 12, PiiMention.TYPE_EMAIL);
    final PiiMention earlier = mention(0, 8, PiiMention.TYPE_CARD);
    final PiiExtractor extractor = reverse
        ? new CompositePiiExtractor(new Fixed(earlier), new Fixed(later))
        : new CompositePiiExtractor(new Fixed(later), new Fixed(earlier));

    final List<PiiMention> mentions = extractor.extract("0123456789abcdef");

    Assertions.assertEquals(List.of(earlier, later), mentions);
  }

  /** Checks descending end-offset ordering among candidates with a shared start. */
  @Test
  void testLongestCandidateSortsFirstAtTheSameStart() {
    final PiiMention shorter = mention(2, 6, PiiMention.TYPE_PHONE);
    final PiiMention longer = mention(2, 10, PiiMention.TYPE_CARD);
    final PiiExtractor extractor = new CompositePiiExtractor(
        new Fixed(shorter), new Fixed(longer));

    final List<PiiMention> mentions = extractor.extract("0123456789abcdef");

    Assertions.assertEquals(List.of(longer, shorter), mentions);
  }

  /**
   * Verifies that the more specific type sorts first for an exact-span tie regardless
   * of delegate order.
   */
  @Test
  void testTypePriorityOrdersAnExactSpanTieForEitherDelegateOrder() {
    final PiiMention phone = mention(2, 10, PiiMention.TYPE_PHONE);
    final PiiMention card = mention(2, 10, PiiMention.TYPE_CARD);

    Assertions.assertEquals(List.of(card, phone),
        new CompositePiiExtractor(new Fixed(phone), new Fixed(card))
            .extract("0123456789abcdef"));
    Assertions.assertEquals(List.of(card, phone),
        new CompositePiiExtractor(new Fixed(card), new Fixed(phone))
            .extract("0123456789abcdef"));
  }

  /**
   * Verifies that delegate order determines the ordering of equal-priority candidates.
   */
  @Test
  void testDelegateOrderOrdersEquallyRankedTypes() {
    final PiiMention first = mention(2, 10, "custom-first");
    final PiiMention second = mention(2, 10, "custom-second");

    Assertions.assertEquals(List.of(first, second),
        new CompositePiiExtractor(new Fixed(first), new Fixed(second))
            .extract("0123456789abcdef"));
    Assertions.assertEquals(List.of(second, first),
        new CompositePiiExtractor(new Fixed(second), new Fixed(first))
            .extract("0123456789abcdef"));
  }

  /** Checks type ordering within one delegate while retaining both mentions. */
  @Test
  void testTypePriorityOrdersAnExactSpanTieWithinOneExtractor() {
    final PiiMention phone = mention(0, 8, PiiMention.TYPE_PHONE);
    final PiiMention iban = mention(0, 8, PiiMention.TYPE_IBAN);
    final PiiExtractor extractor = new CompositePiiExtractor(
        new Fixed(phone, iban));

    final List<PiiMention> mentions = extractor.extract("0123456789abcdef");

    Assertions.assertEquals(List.of(iban, phone), mentions);
  }

  /** Checks that adjacent candidates remain distinct. */
  @Test
  void testAdjacentCandidatesAreRetained() {
    final PiiExtractor extractor = new CompositePiiExtractor(
        new Fixed(mention(0, 4, PiiMention.TYPE_CARD)),
        new Fixed(mention(4, 8, PiiMention.TYPE_PHONE)));

    final List<PiiMention> mentions = extractor.extract("0123456789abcdef");

    Assertions.assertEquals(2, mentions.size());
    Assertions.assertEquals(4, mentions.get(0).span().getEnd());
    Assertions.assertEquals(4, mentions.get(1).span().getStart());
  }

  /** Checks retention across a chain of pairwise overlaps. */
  @Test
  void testTransitiveOverlapChainRetainsEveryMentionInOrder() {
    final PiiMention first = mention(0, 6, PiiMention.TYPE_CARD);
    final PiiMention last = mention(10, 16, PiiMention.TYPE_CARD);
    final PiiMention crossing = mention(4, 12, PiiMention.TYPE_PHONE);
    final PiiMention contained = mention(5, 7, PiiMention.TYPE_IPV4);
    final PiiExtractor extractor = new CompositePiiExtractor(
        new Fixed(first, last), new Fixed(crossing), new Fixed(contained));

    final List<PiiMention> mentions = extractor.extract("0123456789abcdef");

    Assertions.assertEquals(List.of(first, crossing, contained, last), mentions);
  }

  @Test
  void testNestingComposites() {
    final PiiExtractor inner = new CompositePiiExtractor(
        new CursorPiiExtractor(Set.of(PiiMention.TYPE_EMAIL)));
    final PiiExtractor outer = new CompositePiiExtractor(inner,
        new CursorPiiExtractor(Set.of(PiiMention.TYPE_CARD)));

    Assertions.assertEquals(2,
        outer.extract("jane@example.com paid with 4111 1111 1111 1111").size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "nothing to see here", "1 + 1 = 2"})
  void testTextWithoutPiiYieldsNoMention(String text) {
    Assertions.assertTrue(new CompositePiiExtractor(new CursorPiiExtractor()).extract(text)
        .isEmpty(), text);
  }

  @Test
  void testDelegatesAreExposedInOrder() {
    final PiiExtractor first = new CursorPiiExtractor();
    final PiiExtractor second = new CursorPiiExtractor(Set.of(PiiMention.TYPE_CARD));
    final CompositePiiExtractor extractor = new CompositePiiExtractor(first, second);

    Assertions.assertEquals(List.of(first, second), extractor.extractors());
  }

  @Test
  void testDelegateListIsCopiedAndUnmodifiable() {
    final List<PiiExtractor> delegates = new ArrayList<>();
    delegates.add(new CursorPiiExtractor(Set.of(PiiMention.TYPE_EMAIL)));
    final CompositePiiExtractor extractor = new CompositePiiExtractor(delegates);
    delegates.add(new CursorPiiExtractor(Set.of(PiiMention.TYPE_CARD)));

    Assertions.assertEquals(1, extractor.extractors().size());
    Assertions.assertThrows(UnsupportedOperationException.class,
        () -> extractor.extractors().add(new CursorPiiExtractor()));
  }

  @Test
  void testRejectsMissingArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CompositePiiExtractor((PiiExtractor[]) null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CompositePiiExtractor((List<PiiExtractor>) null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CompositePiiExtractor(new PiiExtractor[0]));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CompositePiiExtractor(Collections.emptyList()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CompositePiiExtractor(new CursorPiiExtractor(), null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CompositePiiExtractor(Collections.singletonList(null)));
  }

  @Test
  void testRejectsNullText() {
    final PiiExtractor extractor = new CompositePiiExtractor(new CursorPiiExtractor());
    Assertions.assertThrows(IllegalArgumentException.class, () -> extractor.extract(null));
  }
}
