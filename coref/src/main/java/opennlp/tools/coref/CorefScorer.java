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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import opennlp.tools.coref.CorefScores.Score;

/**
 * Scores coreference partitions of predicted mentions against key partitions with the
 * MUC, B-cubed, and CEAF metrics, following the reference implementation of
 * <a href="https://aclanthology.org/P14-2006/">Pradhan et al. (ACL 2014), "Scoring
 * Coreference Partitions of Predicted Mentions: A Reference Implementation"</a>.
 *
 * <p>A partition is a collection of entities, each a set of mentions; mentions are
 * matched between key and response by {@link Object#equals(Object)}, so any value that
 * identifies a mention, typically its span, can serve. No mention is added to or removed
 * from either side: an unmatched response mention costs precision and an unmatched key
 * mention costs recall, as the original metrics define.
 * Singleton entities are scored when present and should be dropped by the caller when
 * the key convention excludes them, as OntoNotes does.</p>
 *
 * <p>Each document adds its numerators and denominators to running sums, so the scores
 * over a corpus are the reference scorer's corpus-level figures, not an average
 * of per-document scores. The scorer is not thread-safe.</p>
 *
 * @since 3.0.0
 */
public final class CorefScorer {

  private double mucRecallNumerator;
  private double mucRecallDenominator;
  private double mucPrecisionNumerator;
  private double mucPrecisionDenominator;
  private double bCubedRecallNumerator;
  private double bCubedPrecisionNumerator;
  private double ceafMNumerator;
  private double ceafENumerator;
  private long keyEntities;
  private long responseEntities;
  private long keyMentions;
  private long responseMentions;
  private long matchedMentions;

  /** Creates an empty scorer. */
  public CorefScorer() {
  }

  /**
   * Adds one document's partitions.
   *
   * @param key The gold entities. Must not be {@code null}, contain a {@code null} or
   *            empty entity, or place one mention in two entities.
   * @param response The predicted entities under the same constraints.
   * @param <M> The mention identity type.
   * @throws IllegalArgumentException Thrown if a partition is invalid.
   */
  public <M> void add(Collection<? extends Set<M>> key,
      Collection<? extends Set<M>> response) {
    final Map<M, Integer> keyEntityOf = index(key, "key");
    final Map<M, Integer> responseEntityOf = index(response, "response");
    final List<Set<M>> keys = new ArrayList<>(key);
    final List<Set<M>> responses = new ArrayList<>(response);

    mucRecallNumerator += mucNumerator(keys, responseEntityOf);
    mucRecallDenominator += mucDenominator(keys);
    mucPrecisionNumerator += mucNumerator(responses, keyEntityOf);
    mucPrecisionDenominator += mucDenominator(responses);

    final double[][] overlap = overlaps(keys, responses);
    bCubedRecallNumerator += bCubedNumerator(overlap, keys, true);
    bCubedPrecisionNumerator += bCubedNumerator(overlap, responses, false);
    ceafMNumerator += bestAlignment(overlap, keys, responses, false);
    ceafENumerator += bestAlignment(overlap, keys, responses, true);

    keyEntities += keys.size();
    responseEntities += responses.size();
    keyMentions += keyEntityOf.size();
    responseMentions += responseEntityOf.size();
    for (final M mention : responseEntityOf.keySet()) {
      if (keyEntityOf.containsKey(mention)) {
        matchedMentions++;
      }
    }
  }

  /**
   * Computes the scores over every document added so far.
   *
   * @return The scores; all zero before the first document. Never {@code null}.
   */
  public CorefScores scores() {
    final Score muc = Score.of(mucPrecisionNumerator, mucPrecisionDenominator,
        mucRecallNumerator, mucRecallDenominator);
    final Score bCubed = Score.of(bCubedPrecisionNumerator, responseMentions,
        bCubedRecallNumerator, keyMentions);
    final Score ceafM = Score.of(ceafMNumerator, responseMentions,
        ceafMNumerator, keyMentions);
    final Score ceafE = Score.of(ceafENumerator, responseEntities,
        ceafENumerator, keyEntities);
    final Score mentions = Score.of(matchedMentions, responseMentions,
        matchedMentions, keyMentions);
    return new CorefScores(muc, bCubed, ceafM, ceafE, mentions,
        (muc.f1() + bCubed.f1() + ceafE.f1()) / 3.0);
  }

  /**
   * Validates a partition and maps every mention to the index of its entity.
   *
   * @param partition The partition.
   * @param name The partition name for error messages.
   * @param <M> The mention identity type.
   * @return The mention index. Never {@code null}.
   */
  private <M> Map<M, Integer> index(Collection<? extends Set<M>> partition,
      String name) {
    if (partition == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    final Map<M, Integer> entityOf = new HashMap<>();
    int entity = 0;
    for (final Set<M> mentions : partition) {
      if (mentions == null || mentions.isEmpty()) {
        throw new IllegalArgumentException(name + " must not contain an empty entity");
      }
      for (final M mention : mentions) {
        if (mention == null) {
          throw new IllegalArgumentException(name + " must not contain a null mention");
        }
        if (entityOf.put(mention, entity) != null) {
          throw new IllegalArgumentException(
              name + " places mention " + mention + " in two entities");
        }
      }
      entity++;
    }
    return entityOf;
  }

  /**
   * Sums, over the entities of one side, the entity size minus the number of parts the
   * other side cuts it into; a mention the other side lacks forms a part of its own.
   *
   * @param entities The entities of the scored side.
   * @param otherEntityOf The other side's mention index.
   * @param <M> The mention identity type.
   * @return The MUC numerator.
   */
  private <M> double mucNumerator(List<Set<M>> entities,
      Map<M, Integer> otherEntityOf) {
    double numerator = 0.0;
    for (final Set<M> entity : entities) {
      final Set<Integer> parts = new HashSet<>();
      int unmatched = 0;
      for (final M mention : entity) {
        final Integer other = otherEntityOf.get(mention);
        if (other == null) {
          unmatched++;
        } else {
          parts.add(other);
        }
      }
      numerator += entity.size() - parts.size() - unmatched;
    }
    return numerator;
  }

  /**
   * Computes the MUC denominator of a partition.
   *
   * @param entities The partition entities.
   * @param <M> The mention type.
   * @return The sum of each entity size minus one.
   */
  private <M> double mucDenominator(List<Set<M>> entities) {
    double denominator = 0.0;
    for (final Set<M> entity : entities) {
      denominator += entity.size() - 1;
    }
    return denominator;
  }

  /**
   * Counts the mentions shared by each key and response entity pair.
   *
   * @param keys The key entities.
   * @param responses The response entities.
   * @param <M> The mention type.
   * @return The mention overlap of every key and response entity pair.
   */
  private <M> double[][] overlaps(List<Set<M>> keys, List<Set<M>> responses) {
    final double[][] overlap = new double[keys.size()][responses.size()];
    for (int i = 0; i < keys.size(); i++) {
      for (int j = 0; j < responses.size(); j++) {
        int shared = 0;
        for (final M mention : keys.get(i)) {
          if (responses.get(j).contains(mention)) {
            shared++;
          }
        }
        overlap[i][j] = shared;
      }
    }
    return overlap;
  }

  /**
   * Sums the squared overlaps divided by the size of the scored side's entity.
   *
   * @param overlap The overlap matrix, keys by rows.
   * @param entities The entities of the scored side.
   * @param byRow Whether the scored side is the key side.
   * @param <M> The mention identity type.
   * @return The B-cubed numerator.
   */
  private <M> double bCubedNumerator(double[][] overlap, List<Set<M>> entities,
      boolean byRow) {
    double numerator = 0.0;
    for (int i = 0; i < overlap.length; i++) {
      for (int j = 0; j < overlap[i].length; j++) {
        final double shared = overlap[i][j];
        if (shared > 0) {
          numerator += shared * shared / entities.get(byRow ? i : j).size();
        }
      }
    }
    return numerator;
  }

  /**
   * Finds the one-to-one entity alignment with the largest total similarity: the
   * overlap itself for the mention-based CEAF, the Dice similarity of the two entities
   * for the entity-based one.
   *
   * @param overlap The overlap matrix, keys by rows.
   * @param keys The key entities.
   * @param responses The response entities.
   * @param entityBased Whether to score with the entity-based similarity.
   * @param <M> The mention identity type.
   * @return The similarity summed over the best alignment.
   */
  private <M> double bestAlignment(double[][] overlap, List<Set<M>> keys,
      List<Set<M>> responses, boolean entityBased) {
    final int size = Math.max(keys.size(), responses.size());
    if (size == 0) {
      return 0.0;
    }
    final double[][] similarity = new double[size][size];
    for (int i = 0; i < keys.size(); i++) {
      for (int j = 0; j < responses.size(); j++) {
        similarity[i][j] = entityBased
            ? 2.0 * overlap[i][j] / (keys.get(i).size() + responses.get(j).size())
            : overlap[i][j];
      }
    }
    return maximumAssignment(similarity);
  }

  /**
   * Solves the assignment problem for a square similarity matrix with the Hungarian
   * method in its {@code O(n^3)} potential form, minimizing the negated similarities.
   *
   * @param similarity The square similarity matrix.
   * @return The largest total similarity of any perfect matching.
   */
  double maximumAssignment(double[][] similarity) {
    final int n = similarity.length;
    final double[] u = new double[n + 1];
    final double[] v = new double[n + 1];
    final int[] matchedRow = new int[n + 1];
    final int[] way = new int[n + 1];
    for (int i = 1; i <= n; i++) {
      matchedRow[0] = i;
      int column = 0;
      final double[] minimum = new double[n + 1];
      Arrays.fill(minimum, Double.POSITIVE_INFINITY);
      final boolean[] used = new boolean[n + 1];
      do {
        used[column] = true;
        final int row = matchedRow[column];
        double delta = Double.POSITIVE_INFINITY;
        int next = 0;
        for (int j = 1; j <= n; j++) {
          if (used[j]) {
            continue;
          }
          final double cost = -similarity[row - 1][j - 1] - u[row] - v[j];
          if (cost < minimum[j]) {
            minimum[j] = cost;
            way[j] = column;
          }
          if (minimum[j] < delta) {
            delta = minimum[j];
            next = j;
          }
        }
        for (int j = 0; j <= n; j++) {
          if (used[j]) {
            u[matchedRow[j]] += delta;
            v[j] -= delta;
          } else {
            minimum[j] -= delta;
          }
        }
        column = next;
      } while (matchedRow[column] != 0);
      do {
        final int previous = way[column];
        matchedRow[column] = matchedRow[previous];
        column = previous;
      } while (column != 0);
    }
    double total = 0.0;
    for (int j = 1; j <= n; j++) {
      total += similarity[matchedRow[j] - 1][j - 1];
    }
    return total;
  }
}
