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

package opennlp.tools.postag;

/**
 * Computes linear-chain CRF loss, gradients, posterior probabilities and Viterbi
 * decoding. Exact path-score sums are separated from floating-point probability
 * calculations. An instance contains only the tag count and can be shared between
 * threads.
 */
final class CrfScorer {

  private final int tagCount;

  /**
   * Initializes a scorer over a fixed tag inventory.
   *
   * @param tagCount The number of tags. Must be positive.
   * @throws IllegalArgumentException Thrown if {@code tagCount} is not positive.
   */
  CrfScorer(int tagCount) {
    if (tagCount <= 0) {
      throw new IllegalArgumentException("tagCount must be positive");
    }
    this.tagCount = tagCount;
  }

  /** {@return the number of tags} */
  int tagCount() {
    return tagCount;
  }

  /**
   * Computes negative log-likelihood and adds parameter gradients to the buffers.
   *
   * @param emissions The finite emission scores, {@code [T][tagCount]}, with T positive.
   * @param gold The gold tag indexes, one per position.
   * @param transitions The finite transition scores, {@code [tagCount][tagCount]}.
   * @param start The finite start scores.
   * @param end The finite end scores.
   * @param emissionGrads The emission gradient buffer.
   * @param transitionGrads The transition gradient buffer.
   * @param startGrads The start gradient buffer.
   * @param endGrads The end gradient buffer.
   * @return The negative log-likelihood, or positive infinity if it exceeds the double range.
   * @throws IllegalStateException Thrown if a score is not finite.
   */
  double lossAndGradients(double[][] emissions, int[] gold, double[][] transitions,
      double[] start, double[] end, double[][] emissionGrads,
      double[][] transitionGrads, double[] startGrads, double[] endGrads) {
    final Parameters parameters = parameters(emissions, transitions, start, end);
    final Inference result = infer(parameters, gold, transitionGrads);
    final int steps = emissions.length;
    for (int t = 0; t < steps; t++) {
      for (int k = 0; k < tagCount; k++) {
        emissionGrads[t][k] += result.marginals()[t][k];
      }
      emissionGrads[t][gold[t]] -= 1;
      if (t > 0) {
        transitionGrads[gold[t - 1]][gold[t]] -= 1;
      }
    }
    for (int k = 0; k < tagCount; k++) {
      startGrads[k] += result.marginals()[0][k];
      endGrads[k] += result.marginals()[steps - 1][k];
    }
    startGrads[gold[0]] -= 1;
    endGrads[gold[steps - 1]] -= 1;
    return result.loss();
  }

  /**
   * Computes posterior tag probabilities.
   *
   * @param emissions The finite emission scores, with at least one position.
   * @param transitions The finite transition scores.
   * @param start The finite start scores.
   * @param end The finite end scores.
   * @return The probabilities, {@code [T][tagCount]}.
   * @throws IllegalStateException Thrown if a score is not finite.
   */
  double[][] marginals(double[][] emissions, double[][] transitions, double[] start,
      double[] end) {
    return infer(parameters(emissions, transitions, start, end), null, null).marginals();
  }

  /**
   * Computes the highest-scoring tag sequence.
   *
   * @param emissions The finite emission scores, with at least one position.
   * @param transitions The finite transition scores.
   * @param start The finite start scores.
   * @param end The finite end scores.
   * @return The indexes of the best tags.
   * @throws IllegalStateException Thrown if a score is not finite.
   */
  int[] viterbi(double[][] emissions, double[][] transitions, double[] start,
      double[] end) {
    final Parameters parameters = parameters(emissions, transitions, start, end);
    final CrfScoreTable scores = parameters.scores();
    final int steps = emissions.length;
    final int[][] backpointers = new int[steps][tagCount];
    int[] previous = scores.allocate(tagCount);
    int[] current = scores.allocate(tagCount);
    final int candidate = scores.allocate(1)[0];
    for (int k = 0; k < tagCount; k++) {
      scores.add(previous[k], parameters.start()[k], parameters.emissions()[0][k]);
    }
    for (int t = 1; t < steps; t++) {
      for (int k = 0; k < tagCount; k++) {
        scores.add(current[k], previous[0], parameters.transitions()[0][k]);
        for (int j = 1; j < tagCount; j++) {
          scores.add(candidate, previous[j], parameters.transitions()[j][k]);
          if (scores.compare(candidate, current[k]) > 0) {
            scores.copy(current[k], candidate);
            backpointers[t][k] = j;
          }
        }
        scores.add(current[k], current[k], parameters.emissions()[t][k]);
      }
      final int[] swap = previous;
      previous = current;
      current = swap;
    }
    int bestLast = 0;
    scores.add(current[0], previous[0], parameters.end()[0]);
    for (int k = 1; k < tagCount; k++) {
      scores.add(candidate, previous[k], parameters.end()[k]);
      if (scores.compare(candidate, current[0]) > 0) {
        scores.copy(current[0], candidate);
        bestLast = k;
      }
    }
    final int[] path = new int[steps];
    path[steps - 1] = bestLast;
    for (int t = steps - 1; t > 0; t--) {
      path[t - 1] = backpointers[t][path[t]];
    }
    return path;
  }

  /**
   * Calculates suffix weights and propagates the conditional tag probabilities.
   *
   * @param parameters The parameters.
   * @param gold The reference tags, or null when only probabilities are needed.
   * @param transitionGrads The optional transition gradient buffer.
   * @return The loss and posterior probabilities.
   */
  private Inference infer(Parameters parameters, int[] gold, double[][] transitionGrads) {
    final CrfScoreTable scores = parameters.scores();
    final Backward suffixes = backward(parameters);
    final int steps = parameters.emissions().length;
    final double[][] probabilities = new double[steps][tagCount];
    final int[] candidates = scores.allocate(tagCount);
    final int candidate = scores.allocate(1)[0];
    for (int k = 0; k < tagCount; k++) {
      scores.add(candidates[k], parameters.start()[k], parameters.emissions()[0][k]);
      scores.add(candidates[k], candidates[k], suffixes.best()[0][k]);
    }
    final int best = max(candidates, scores);
    for (int k = 0; k < tagCount; k++) {
      probabilities[0][k] = scores.difference(candidates[k], best) + suffixes.logMass()[0][k];
    }
    double loss = normalize(probabilities[0], gold == null ? -1 : gold[0]);
    final double[] conditional = new double[tagCount];
    for (int t = 0; t < steps - 1; t++) {
      for (int k = 0; k < tagCount; k++) {
        scores.add(candidates[k], parameters.emissions()[t + 1][k], suffixes.best()[t + 1][k]);
      }
      for (int j = 0; j < tagCount; j++) {
        for (int k = 0; k < tagCount; k++) {
          scores.add(candidate, candidates[k], parameters.transitions()[j][k]);
          conditional[k] = scores.difference(candidate, suffixes.best()[t][j]) + suffixes.logMass()[t + 1][k];
        }
        loss += normalize(conditional, gold != null && gold[t] == j ? gold[t + 1] : -1);
        for (int k = 0; k < tagCount; k++) {
          final double probability = probabilities[t][j] * conditional[k];
          probabilities[t + 1][k] += probability;
          if (transitionGrads != null) {
            transitionGrads[j][k] += probability;
          }
        }
      }
      double total = 0;
      for (double value : probabilities[t + 1]) {
        total += value;
      }
      for (int k = 0; k < tagCount; k++) {
        probabilities[t + 1][k] /= total;
      }
    }
    return new Inference(loss, probabilities);
  }

  /**
   * Separates each maximum suffix score from the log weight of its alternatives.
   *
   * @param parameters The parameters.
   * @return The exact maximum scores and shifted log weights.
   */
  private Backward backward(Parameters parameters) {
    final CrfScoreTable scores = parameters.scores();
    final int steps = parameters.emissions().length;
    final int[][] best = new int[steps][];
    for (int t = 0; t < steps; t++) {
      best[t] = scores.allocate(tagCount);
    }
    final double[][] logMass = new double[steps][tagCount];
    for (int k = 0; k < tagCount; k++) {
      scores.copy(best[steps - 1][k], parameters.end()[k]);
    }
    final int[] next = scores.allocate(tagCount);
    final int[] candidates = scores.allocate(tagCount);
    final double[] weights = new double[tagCount];
    for (int t = steps - 2; t >= 0; t--) {
      for (int k = 0; k < tagCount; k++) {
        scores.add(next[k], parameters.emissions()[t + 1][k], best[t + 1][k]);
      }
      double largestMass = Double.NEGATIVE_INFINITY;
      for (int j = 0; j < tagCount; j++) {
        for (int k = 0; k < tagCount; k++) {
          scores.add(candidates[k], next[k], parameters.transitions()[j][k]);
        }
        scores.copy(best[t][j], max(candidates, scores));
        for (int k = 0; k < tagCount; k++) {
          weights[k] = scores.difference(candidates[k], best[t][j]) + logMass[t + 1][k];
        }
        logMass[t][j] = logSumExp(weights);
        largestMass = Math.max(largestMass, logMass[t][j]);
      }
      for (int j = 0; j < tagCount; j++) {
        logMass[t][j] -= largestMass;
      }
    }
    return new Backward(best, logMass);
  }

  /**
   * Converts log weights to probabilities and computes a selected label's loss.
   *
   * @param values The log weights, replaced with probabilities.
   * @param gold The selected label, or a negative index to omit loss.
   * @return The selected loss, or zero.
   */
  private double normalize(double[] values, int gold) {
    double max = Double.NEGATIVE_INFINITY;
    for (double value : values) {
      max = Math.max(max, value);
    }
    final double selected = gold < 0 ? max : values[gold];
    double total = 0;
    for (int k = 0; k < tagCount; k++) {
      values[k] = Math.exp(values[k] - max);
      total += values[k];
    }
    for (int k = 0; k < tagCount; k++) {
      values[k] /= total;
    }
    return gold < 0 ? 0 : (max - selected) + Math.log(total);
  }

  /**
   * Calculates a shifted log-sum-exp.
   *
   * @param values The log weights, with at least one finite value.
   * @return The log of their exponential sum.
   */
  private double logSumExp(double[] values) {
    double max = Double.NEGATIVE_INFINITY;
    for (double value : values) {
      max = Math.max(max, value);
    }
    double total = 0;
    for (double value : values) {
      total += Math.exp(value - max);
    }
    return max + Math.log(total);
  }

  /**
   * Selects the maximum exact score.
   *
   * @param values The nonempty score array.
   * @param scores The score storage.
   * @return The index of the maximum.
   */
  private int max(int[] values, CrfScoreTable scores) {
    int max = values[0];
    for (int k = 1; k < values.length; k++) {
      if (scores.compare(values[k], max) > 0) {
        max = values[k];
      }
    }
    return max;
  }

  /**
   * Creates exact representations of the binary floating-point parameters.
   *
   * @param emissions The emission scores.
   * @param transitions The transition scores.
   * @param start The start scores.
   * @param end The end scores.
   * @return The exact parameters.
   * @throws IllegalStateException Thrown if a score is not finite.
   */
  private Parameters parameters(double[][] emissions, double[][] transitions,
      double[] start, double[] end) {
    final CrfScoreTable scores = new CrfScoreTable(emissions.length,
        new double[][][] {emissions, transitions, {start, end}});
    return new Parameters(scores.put(emissions), scores.put(transitions),
        scores.put(start), scores.put(end), scores);
  }

  /** Indexes of exact parameters and their storage. */
  private record Parameters(int[][] emissions, int[][] transitions,
      int[] start, int[] end, CrfScoreTable scores) {
  }

  /** Maximum suffix scores and log weights relative to those scores. */
  private record Backward(int[][] best, double[][] logMass) {
  }

  /** Loss and posterior tag probabilities. */
  private record Inference(double loss, double[][] marginals) {
  }
}
