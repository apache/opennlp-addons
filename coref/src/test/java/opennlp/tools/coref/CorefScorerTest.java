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

package opennlp.tools.coref;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.coref.CorefScores.Score;

/**
 * Pins the scorer to the worked example of Pradhan et al. (ACL 2014), section 4: the
 * key holds {a, b, c} and {d, e, f, g}, the response holds {a, b}, {c, d}, and
 * {f, g, h, i}, so e is missing and h and i are spurious.
 */
public class CorefScorerTest {

  private static final double TOLERANCE = 1e-9;

  private static final List<Set<String>> KEY =
      List.of(Set.of("a", "b", "c"), Set.of("d", "e", "f", "g"));

  private static final List<Set<String>> RESPONSE =
      List.of(Set.of("a", "b"), Set.of("c", "d"), Set.of("f", "g", "h", "i"));

  private CorefScores scoreExample() {
    final CorefScorer scorer = new CorefScorer();
    scorer.add(KEY, RESPONSE);
    return scorer.scores();
  }

  @Test
  void testMucMatchesReferenceExample() {
    final Score muc = scoreExample().muc();
    Assertions.assertEquals(0.40, muc.recall(), TOLERANCE);
    Assertions.assertEquals(0.40, muc.precision(), TOLERANCE);
    Assertions.assertEquals(0.40, muc.f1(), TOLERANCE);
  }

  @Test
  void testBCubedMatchesReferenceExample() {
    final Score bCubed = scoreExample().bCubed();
    Assertions.assertEquals(35.0 / 84.0, bCubed.recall(), TOLERANCE);
    Assertions.assertEquals(0.50, bCubed.precision(), TOLERANCE);
    Assertions.assertEquals(2 * (35.0 / 84.0) * 0.5 / (35.0 / 84.0 + 0.5),
        bCubed.f1(), TOLERANCE);
  }

  @Test
  void testCeafMMatchesReferenceExample() {
    final Score ceafM = scoreExample().ceafM();
    Assertions.assertEquals(4.0 / 7.0, ceafM.recall(), TOLERANCE);
    Assertions.assertEquals(0.50, ceafM.precision(), TOLERANCE);
  }

  @Test
  void testCeafEMatchesReferenceExample() {
    final Score ceafE = scoreExample().ceafE();
    Assertions.assertEquals(0.65, ceafE.recall(), TOLERANCE);
    Assertions.assertEquals(1.3 / 3.0, ceafE.precision(), TOLERANCE);
    Assertions.assertEquals(0.52, ceafE.f1(), 0.005);
  }

  @Test
  void testMentionDetectionAndConllAverage() {
    final CorefScores scores = scoreExample();
    Assertions.assertEquals(6.0 / 7.0, scores.mentions().recall(), TOLERANCE);
    Assertions.assertEquals(6.0 / 8.0, scores.mentions().precision(), TOLERANCE);
    Assertions.assertEquals(
        (scores.muc().f1() + scores.bCubed().f1() + scores.ceafE().f1()) / 3.0,
        scores.conll(), TOLERANCE);
  }

  @Test
  void testIdenticalPartitionsScorePerfectly() {
    final CorefScorer scorer = new CorefScorer();
    scorer.add(KEY, KEY);
    final CorefScores scores = scorer.scores();
    Assertions.assertEquals(1.0, scores.muc().f1(), TOLERANCE);
    Assertions.assertEquals(1.0, scores.bCubed().f1(), TOLERANCE);
    Assertions.assertEquals(1.0, scores.ceafM().f1(), TOLERANCE);
    Assertions.assertEquals(1.0, scores.ceafE().f1(), TOLERANCE);
    Assertions.assertEquals(1.0, scores.mentions().f1(), TOLERANCE);
    Assertions.assertEquals(1.0, scores.conll(), TOLERANCE);
  }

  @Test
  void testSingletonsCarryNoMucLinksButCountElsewhere() {
    final CorefScorer scorer = new CorefScorer();
    scorer.add(List.of(Set.of("a")), List.of(Set.of("a")));
    final CorefScores scores = scorer.scores();
    Assertions.assertEquals(0.0, scores.muc().f1(), TOLERANCE);
    Assertions.assertEquals(1.0, scores.bCubed().f1(), TOLERANCE);
    Assertions.assertEquals(1.0, scores.ceafE().f1(), TOLERANCE);
  }

  @Test
  void testEmptyResponseScoresZeroNotNaN() {
    final CorefScorer scorer = new CorefScorer();
    scorer.add(KEY, List.of());
    final CorefScores scores = scorer.scores();
    Assertions.assertEquals(0.0, scores.muc().precision(), TOLERANCE);
    Assertions.assertEquals(0.0, scores.bCubed().precision(), TOLERANCE);
    Assertions.assertEquals(0.0, scores.ceafE().precision(), TOLERANCE);
    Assertions.assertEquals(0.0, scores.conll(), TOLERANCE);
  }

  @Test
  void testNothingAddedScoresZero() {
    Assertions.assertEquals(0.0, new CorefScorer().scores().conll(), TOLERANCE);
  }

  @Test
  void testCorpusScoresSumNumeratorsAcrossDocuments() {
    final CorefScorer scorer = new CorefScorer();
    scorer.add(KEY, RESPONSE);
    scorer.add(List.of(Set.of("x", "y")), List.of(Set.of("x", "y")));
    // MUC precision: (2 + 1) correct links of (5 + 1) response links is 0.50; the mean
    // of the per-document precisions 0.40 and 1.00 would be 0.70.
    Assertions.assertEquals(3.0 / 6.0, scorer.scores().muc().precision(), TOLERANCE);
    Assertions.assertEquals(3.0 / 6.0, scorer.scores().muc().recall(), TOLERANCE);
  }

  @Test
  void testRejectsInvalidPartitions() {
    final CorefScorer scorer = new CorefScorer();
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> scorer.add(null, RESPONSE));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> scorer.add(KEY, null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> scorer.add(List.of(Set.of()), RESPONSE));
    final List<Set<String>> overlapping = List.of(Set.of("a", "b"), Set.of("b", "c"));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> scorer.add(overlapping, RESPONSE));
    final List<Set<String>> withNull = new ArrayList<>();
    withNull.add(null);
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> scorer.add(KEY, withNull));
  }

  @Test
  void testAssignmentMatchesExhaustiveSearch() {
    final Random random = new Random(17);
    for (int trial = 0; trial < 200; trial++) {
      final int n = 1 + random.nextInt(6);
      final double[][] similarity = new double[n][n];
      for (int i = 0; i < n; i++) {
        for (int j = 0; j < n; j++) {
          similarity[i][j] = random.nextInt(5) / 4.0;
        }
      }
      Assertions.assertEquals(bestByPermutation(similarity, new boolean[n], 0),
          new CorefScorer().maximumAssignment(similarity), TOLERANCE);
    }
  }

  private double bestByPermutation(double[][] similarity, boolean[] used,
      int row) {
    if (row == similarity.length) {
      return 0.0;
    }
    double best = Double.NEGATIVE_INFINITY;
    for (int j = 0; j < similarity.length; j++) {
      if (!used[j]) {
        used[j] = true;
        best = Math.max(best,
            similarity[row][j] + bestByPermutation(similarity, used, row + 1));
        used[j] = false;
      }
    }
    return best;
  }

  @Test
  void testScoreRejectsValuesOutsideUnitInterval() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new Score(1.5, 0.0, 0.0));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new Score(0.0, Double.NaN, 0.0));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefScores(null, null, null, null, null, 0.0));
  }

  @Test
  void testScoreRecordsRejectInconsistentDerivedValues() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new Score(1.0, 1.0, 0.0));

    final Score perfect = new Score(1.0, 1.0, 1.0);
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefScores(perfect, perfect, perfect, perfect, perfect, 0.0));
  }
}
