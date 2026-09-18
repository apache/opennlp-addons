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

package opennlp.tools.geo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link DocumentRegionAnnotator#winner(List, double)}: the top vote wins only
 * when its share leads the runner-up's share by at least the given margin, an empty
 * ballot or an indecisive lead yields no winner, and the arguments are validated.
 */
public class DocumentRegionWinnerTest {

  /**
   * Builds a ballot row without a span, as the region layer carries them.
   *
   * @param countryCode The ISO 3166-1 alpha-2 country code. Must not be {@code null}.
   * @param share The row's share, in {@code (0, 1]}.
   * @return A span-less ballot row. Never {@code null}.
   */
  private static Annotation<RegionVote> row(String countryCode, double share) {
    return Annotation.of(new RegionVote(countryCode, share));
  }

  @Test
  void testEmptyBallotHasNoWinner() {
    assertTrue(DocumentRegionAnnotator.winner(List.of(), 0.0).isEmpty());
  }

  @Test
  void testSingleRowWinsAgainstARunnerUpShareOfZero() {
    final Optional<RegionVote> winner =
        DocumentRegionAnnotator.winner(List.of(row("AU", 1.0)), 0.5);
    assertTrue(winner.isPresent());
    assertEquals("AU", winner.get().countryCode());
  }

  @Test
  void testZeroMarginKeepsTheTopRowEvenOnATie() {
    final Optional<RegionVote> winner = DocumentRegionAnnotator.winner(
        List.of(row("FR", 0.5), row("GB", 0.5)), 0.0);
    assertTrue(winner.isPresent());
    assertEquals("FR", winner.get().countryCode());
  }

  @Test
  void testTieBelowTheMarginHasNoWinner() {
    assertTrue(DocumentRegionAnnotator.winner(
        List.of(row("FR", 0.5), row("GB", 0.5)), 0.1).isEmpty());
  }

  @Test
  void testLeadBelowTheMarginHasNoWinner() {
    assertTrue(DocumentRegionAnnotator.winner(
        List.of(row("MX", 0.55), row("US", 0.45)), 0.2).isEmpty());
  }

  /**
   * Verifies the boundary: a lead of exactly the margin wins. The shares and the
   * margin are binary-exact doubles, so the boundary is hit without rounding noise.
   */
  @Test
  void testLeadExactlyAtTheMarginWins() {
    final Optional<RegionVote> winner = DocumentRegionAnnotator.winner(
        List.of(row("MX", 0.75), row("US", 0.25)), 0.5);
    assertTrue(winner.isPresent());
    assertEquals("MX", winner.get().countryCode());
    assertEquals(0.75, winner.get().share(), 0.0);
  }

  /**
   * Verifies the helper against an annotator-produced ballot: a country name voting at
   * {@code 0.95} leads a geocoded city voting at {@code 0.8} by a share margin of
   * about {@code 0.086}, so a margin of {@code 0.05} elects New Zealand while a margin
   * of {@code 0.1} elects nobody.
   */
  @Test
  void testAnnotatorProducedBallotRespectsTheMargin() {
    final Document document = new DocumentRegionAnnotator(
        GeoTestUtil.tableGeocoder(Map.of("Sydney", "AU"), 0.8))
        .annotate(GeoTestUtil.withLocations(
            "the Sydney office reports to New Zealand headquarters",
            "Sydney", "New Zealand"));
    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);

    final Optional<RegionVote> decisive = DocumentRegionAnnotator.winner(ballot, 0.05);
    assertTrue(decisive.isPresent());
    assertEquals("NZ", decisive.get().countryCode());
    assertTrue(DocumentRegionAnnotator.winner(ballot, 0.1).isEmpty());
  }

  @Test
  void testNullBallotIsRejected() {
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> DocumentRegionAnnotator.winner(null, 0.0));
    assertEquals("ballot must not be null", e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(doubles = {-0.1, 1.1, Double.NaN})
  void testMarginOutsideTheUnitIntervalIsRejected(double minMargin) {
    assertThrows(IllegalArgumentException.class,
        () -> DocumentRegionAnnotator.winner(List.of(row("AU", 1.0)), minMargin));
  }
}
