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
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

public class PiiTypePriorityTest {

  private static final List<String> ALL_TYPES = List.of(
      PiiMention.TYPE_JWT,
      PiiMention.TYPE_AWS_ACCESS_KEY,
      PiiMention.TYPE_GITHUB_TOKEN,
      PiiMention.TYPE_URL_CREDENTIAL,
      PiiMention.TYPE_EMAIL,
      PiiMention.TYPE_IBAN,
      PiiMention.TYPE_IMEI,
      PiiMention.TYPE_CARD,
      PiiMention.TYPE_BTC_ADDRESS,
      PiiMention.TYPE_ETH_ADDRESS,
      PiiMention.TYPE_MAC,
      PiiMention.TYPE_IPV6,
      PiiMention.TYPE_IPV4,
      PiiMention.TYPE_US_SSN,
      PiiMention.TYPE_US_ITIN,
      PiiMention.TYPE_UK_NHS,
      PiiMention.TYPE_DE_STEUER_ID,
      PiiMention.TYPE_CA_SIN,
      PiiMention.TYPE_ABA_ROUTING,
      PiiMention.TYPE_PHONE);

  /**
   * Provides the expected type ranks.
   *
   * @return The type and rank.
   */
  private static Stream<Arguments> rankedTypes() {
    return IntStream.range(0, ALL_TYPES.size())
        .mapToObj(index -> Arguments.of(ALL_TYPES.get(index), index));
  }

  /**
   * Checks the numeric rank of a built-in type.
   *
   * @param type The mention type.
   * @param rank The expected rank.
   */
  @ParameterizedTest
  @MethodSource("rankedTypes")
  void testEveryNamedTypeHasItsOwnRank(String type, int rank) {
    Assertions.assertEquals(rank, PiiTypePriority.rank(type));
  }

  /**
   * Checks that all public type constants have an expected rank.
   *
   * @throws IllegalAccessException If a public constant cannot be read.
   */
  @Test
  void testCoversPublicTypeConstants() throws IllegalAccessException {
    Assertions.assertEquals(PiiTestSupport.declaredTypes(), Set.copyOf(ALL_TYPES));
    Assertions.assertEquals(ALL_TYPES.size(), Set.copyOf(ALL_TYPES).size());
    Assertions.assertEquals(ALL_TYPES.size(), PiiTypePriority.UNRANKED);
  }

  /**
   * Provides the ordered combinations of distinct built-in types.
   *
   * @return The preferred type and the alternative.
   */
  private static Stream<Arguments> typeChoices() {
    return IntStream.range(0, ALL_TYPES.size()).boxed().flatMap(preferred ->
        IntStream.range(preferred + 1, ALL_TYPES.size())
            .mapToObj(alternative -> Arguments.of(ALL_TYPES.get(preferred), ALL_TYPES.get(alternative))));
  }

  /**
   * Checks retained mention ordering in internal scans and composites.
   *
   * @param preferred The type with the lower rank.
   * @param alternative The alternative type.
   */
  @ParameterizedTest
  @MethodSource("typeChoices")
  void testRetainedMentionsUseTheCompletePriorityOrder(String preferred, String alternative) {
    final Span span = new Span(0, 5);
    final PiiMention higherPriority = new PiiMention(span, preferred, "higher");
    final PiiMention lowerPriority = new PiiMention(span, alternative, "lower");
    for (final List<PiiMention> candidates : List.of(List.of(higherPriority, lowerPriority),
        List.of(lowerPriority, higherPriority))) {
      final List<Hits.Hit> hits = new ArrayList<>();
      for (final PiiMention candidate : candidates) {
        Hits.add(hits, span.getStart(), span.getEnd(), candidate.type(), candidate.normalized());
      }
      Assertions.assertEquals(List.of(higherPriority, lowerPriority), Hits.resolve(hits));
      final PiiExtractor a = text -> List.of(candidates.get(0));
      final PiiExtractor b = text -> List.of(candidates.get(1));
      final CompositePiiExtractor composite = new CompositePiiExtractor(a, b);
      final List<PiiMention> expected = List.of(higherPriority, lowerPriority);
      Assertions.assertEquals(expected, composite.extract("value"));
      Assertions.assertSame(higherPriority, composite.extract("value").get(0));
      Assertions.assertSame(lowerPriority, composite.extract("value").get(1));
      Assertions.assertEquals(expected,
          new CompositePiiExtractor(new CompositePiiExtractor(a), b).extract("value"));
    }
  }

  /**
   * Checks candidate and delegate order for equal spans and equal ranks.
   *
   * @param leadingType The initial candidate type.
   * @param followingType The following candidate type.
   */
  @ParameterizedTest
  @CsvSource({"email,email", "custom-a,custom-b", "custom,custom"})
  void testEqualRanksPreserveInsertionOrder(String leadingType, String followingType) {
    final Span span = new Span(0, 5);
    final PiiMention leading = new PiiMention(span, leadingType, "leading");
    final PiiMention following = new PiiMention(span, followingType, "following");
    for (final List<PiiMention> candidates : List.of(List.of(leading, following),
        List.of(following, leading))) {
      final List<Hits.Hit> hits = new ArrayList<>();
      for (final PiiMention candidate : candidates) {
        Hits.add(hits, 0, 5, candidate.type(), candidate.normalized());
      }
      Assertions.assertEquals(candidates, Hits.resolve(hits));
      final List<PiiMention> withinDelegate =
          new CompositePiiExtractor(text -> candidates).extract("value");
      Assertions.assertEquals(candidates, withinDelegate);
      Assertions.assertSame(candidates.get(0), withinDelegate.get(0));
      Assertions.assertSame(candidates.get(1), withinDelegate.get(1));
      final List<PiiMention> acrossDelegates = new CompositePiiExtractor(
          text -> List.of(candidates.get(0)), text -> List.of(candidates.get(1)))
          .extract("value");
      Assertions.assertEquals(candidates, acrossDelegates);
      Assertions.assertSame(candidates.get(0), acrossDelegates.get(0));
      Assertions.assertSame(candidates.get(1), acrossDelegates.get(1));
    }
  }

  /** Checks that equal mentions collapse to the first original object. */
  @Test
  void testExactlyEqualMentionsCollapseToTheFirstCandidate() {
    final PiiMention first = new PiiMention(new Span(0, 5), PiiMention.TYPE_EMAIL,
        "value");
    final PiiMention duplicate = new PiiMention(new Span(0, 5), PiiMention.TYPE_EMAIL,
        "value");
    final List<Hits.Hit> hits = new ArrayList<>();
    Hits.add(hits, first);
    Hits.add(hits, duplicate);

    final List<PiiMention> resolved = Hits.resolve(hits);

    Assertions.assertEquals(List.of(first), resolved);
    Assertions.assertSame(first, resolved.getFirst());
  }

  /** Checks that equal mentions retain the first span probability. */
  @Test
  void testEqualMentionsWithDifferentProbabilitiesRetainTheFirstObject() {
    final PiiMention first = new PiiMention(new Span(0, 5, "source", 0.1),
        PiiMention.TYPE_EMAIL, "value");
    final PiiMention duplicate = new PiiMention(new Span(0, 5, "source", 0.9),
        PiiMention.TYPE_EMAIL, "value");
    final List<Hits.Hit> hits = new ArrayList<>();
    Hits.add(hits, first);
    Hits.add(hits, duplicate);

    final List<PiiMention> resolved = Hits.resolve(hits);

    Assertions.assertEquals(List.of(first), resolved);
    Assertions.assertSame(first, resolved.getFirst());
    Assertions.assertEquals(0.1, resolved.getFirst().span().getProb());
  }

  /** Checks exact-duplicate handling for an allowed empty span. */
  @Test
  void testEqualEmptyMentionsCollapseToTheFirstObject() {
    final PiiMention first = new PiiMention(new Span(0, 0), "marker", "value");
    final PiiMention duplicate = new PiiMention(new Span(0, 0), "marker", "value");
    final List<Hits.Hit> hits = new ArrayList<>();
    Hits.add(hits, first);
    Hits.add(hits, duplicate);

    final List<PiiMention> resolved = Hits.resolve(hits);

    Assertions.assertEquals(List.of(first), resolved);
    Assertions.assertSame(first, resolved.getFirst());
  }

  /** Checks that span type metadata remains part of mention identity. */
  @Test
  void testEqualOffsetsWithDifferentSpanTypesRemainDistinct() {
    final PiiMention first = new PiiMention(new Span(0, 5, "source-a"),
        PiiMention.TYPE_EMAIL, "value");
    final PiiMention second = new PiiMention(new Span(0, 5, "source-b"),
        PiiMention.TYPE_EMAIL, "value");
    final List<Hits.Hit> hits = new ArrayList<>();
    Hits.add(hits, first);
    Hits.add(hits, second);

    final List<PiiMention> resolved = Hits.resolve(hits);

    Assertions.assertEquals(List.of(first, second), resolved);
    Assertions.assertSame(first, resolved.get(0));
    Assertions.assertSame(second, resolved.get(1));
  }

  @ParameterizedTest
  @CsvSource({
      "email, phone",
      "iban, card",
      "card, aba-routing",
      "jwt, github-token",
      "aws-access-key, email",
      "ipv6, ipv4",
      "mac, ipv6",
      "btc-address, us-ssn",
      "us-ssn, phone",
      "de-steuer-id, phone"
  })
  void testMoreSpecificTypeRanksFirst(String stronger, String weaker) {
    Assertions.assertTrue(PiiTypePriority.rank(stronger) < PiiTypePriority.rank(weaker),
        stronger + " should outrank " + weaker);
  }

  @ParameterizedTest
  @ValueSource(strings = {"custom", "person", "email ", "EMAIL", "", "  "})
  void testUnknownTypeRanksAfterEveryNamedType(String type) {
    Assertions.assertEquals(PiiTypePriority.UNRANKED, PiiTypePriority.rank(type), type);
    for (final String named : ALL_TYPES) {
      Assertions.assertTrue(PiiTypePriority.rank(named) < PiiTypePriority.rank(type));
    }
  }

  @Test
  void testRejectsNullType() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> PiiTypePriority.rank(null));
  }
}
