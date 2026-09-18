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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * The Adam optimizer over a registered set of weight arrays, with global-norm
 * gradient clipping. Weight arrays are shared with the caller, gradient mirrors are
 * owned here, and {@link #step} updates every registered array in place. Vectors
 * register as one-row matrices so there is a single update path.
 *
 * <p>An instance is not thread-safe. Parallel training keeps it on the coordinating
 * thread: workers accumulate into their own {@link #newGradientBuffers() buffers} and
 * the coordinator folds those in with {@link #absorb} before stepping.</p>
 */
final class AdamOptimizer {

  private static final double BETA1 = 0.9d;
  private static final double BETA2 = 0.999d;
  private static final double EPSILON = 1e-8d;

  private final List<double[][]> weights = new ArrayList<>();
  private final List<double[][]> gradients = new ArrayList<>();
  private final List<double[][]> firstMoments = new ArrayList<>();
  private final List<double[][]> secondMoments = new ArrayList<>();
  private final List<Double> lrMultipliers = new ArrayList<>();

  /**
   * Registers a weight matrix, allocating zeroed gradient and moment mirrors.
   *
   * @param weight The weight array, shared and updated in place. Must not be
   *               {@code null}.
   * @return The index used to reach the gradient mirror.
   * @throws IllegalArgumentException Thrown if {@code weight} is {@code null}.
   */
  int register(double[][] weight) {
    return register(weight, 1.0d);
  }

  /**
   * Registers a weight matrix with a learning-rate multiplier, for parameter groups
   * trained slower than the rest, allocating zeroed gradient and moment mirrors.
   *
   * @param weight The weight array, shared and updated in place. Must not be
   *               {@code null}.
   * @param lrMultiplier Multiplier applied to the learning rate for this group; must
   *                     be finite and positive.
   * @return The index used to reach the gradient mirror.
   * @throws IllegalArgumentException Thrown if {@code weight} is {@code null} or
   *         {@code lrMultiplier} is not finite and positive.
   */
  int register(double[][] weight, double lrMultiplier) {
    if (weight == null) {
      throw new IllegalArgumentException("weight must not be null");
    }
    if (!Double.isFinite(lrMultiplier) || lrMultiplier <= 0.0d) {
      throw new IllegalArgumentException("lrMultiplier must be finite and positive");
    }
    weights.add(weight);
    final double[][] gradient = new double[weight.length][];
    final double[][] first = new double[weight.length][];
    final double[][] second = new double[weight.length][];
    for (int r = 0; r < weight.length; r++) {
      gradient[r] = new double[weight[r].length];
      first[r] = new double[weight[r].length];
      second[r] = new double[weight[r].length];
    }
    gradients.add(gradient);
    firstMoments.add(first);
    secondMoments.add(second);
    lrMultipliers.add(lrMultiplier);
    return weights.size() - 1;
  }

  /**
   * Registers a weight vector as a one-row matrix, so the update path stays single.
   *
   * @param weight The weight array, shared and updated in place. Must not be
   *               {@code null}.
   * @return The index used to reach the gradient mirror.
   * @throws IllegalArgumentException Thrown if {@code weight} is {@code null}.
   */
  int register(double[] weight) {
    if (weight == null) {
      throw new IllegalArgumentException("weight must not be null");
    }
    return register(new double[][] {weight});
  }

  /**
   * @param index The registration index.
   * @return The gradient mirror to accumulate into, in place. Never {@code null}.
   */
  double[][] gradient(int index) {
    return gradients.get(index);
  }

  /**
   * @return The global gradient norm over every registered array.
   * @throws IllegalStateException Thrown if a gradient is not finite.
   */
  double globalNorm() {
    double sumSquares = 0.0d;
    double scale = 0.0d;
    for (final double[][] gradient : gradients) {
      for (final double[] row : gradient) {
        for (final double value : row) {
          if (!Double.isFinite(value)) {
            throw new IllegalStateException("gradient must be finite");
          }
          sumSquares += value * value;
          scale = Math.max(scale, Math.abs(value));
        }
      }
    }
    if (scale > 0.0d && (!Double.isFinite(sumSquares) || sumSquares < Double.MIN_NORMAL)) {
      return scale * normalizedNorm(scale);
    }
    return Math.sqrt(sumSquares);
  }

  /**
   * Clips the accumulated gradients to a maximum Euclidean norm.
   *
   * @param limit The positive, finite norm bound validated by the training settings.
   * @throws IllegalStateException Thrown if a gradient is not finite.
   */
  void clipGradients(double limit) {
    final double norm = globalNorm();
    if (norm > limit) {
      final double factor = limit / norm;
      if (factor >= Double.MIN_NORMAL) {
        scaleGradients(factor);
        return;
      }
      double scale = 0.0d;
      for (final double[][] gradient : gradients) {
        for (final double[] row : gradient) {
          for (final double value : row) {
            scale = Math.max(scale, Math.abs(value));
          }
        }
      }
      final double normalizedNorm = normalizedNorm(scale);
      final double scaledFactor = (limit / scale) / normalizedNorm;
      if (scaledFactor >= Double.MIN_NORMAL) {
        scaleGradients(scaledFactor);
      } else {
        // A subnormal factor can lose precision before it reaches the gradient.
        final double magnitude = limit / normalizedNorm;
        for (final double[][] gradient : gradients) {
          for (final double[] row : gradient) {
            for (int i = 0; i < row.length; i++) {
              row[i] = (row[i] / scale) * magnitude;
            }
          }
        }
      }
    }
  }

  /**
   * Calculates the norm after division by the largest gradient magnitude.
   *
   * @param scale The positive, finite largest magnitude.
   * @return The norm of the scaled gradients.
   */
  private double normalizedNorm(double scale) {
    double sumSquares = 0.0d;
    for (final double[][] gradient : gradients) {
      for (final double[] row : gradient) {
        for (final double value : row) {
          final double normalized = value / scale;
          sumSquares += normalized * normalized;
        }
      }
    }
    return Math.sqrt(sumSquares);
  }

  /**
   * Scales every accumulated gradient, used to clip the global norm.
   *
   * @param factor The scale factor.
   */
  void scaleGradients(double factor) {
    for (final double[][] gradient : gradients) {
      for (final double[] row : gradient) {
        for (int i = 0; i < row.length; i++) {
          row[i] *= factor;
        }
      }
    }
  }

  /**
   * Applies one Adam step with bias correction to every registered weight array.
   *
   * @param learningRate The finite, nonnegative step size.
   * @param timestep The one-based number of this update, driving bias correction.
   *        Must be positive.
   * @throws IllegalArgumentException Thrown if {@code timestep} is not positive or
   *         {@code learningRate} is negative or not finite.
   * @throws IllegalStateException Thrown if a gradient or computed update is not finite.
   */
  void step(double learningRate, int timestep) {
    if (timestep <= 0) {
      throw new IllegalArgumentException("timestep must be positive");
    }
    if (!Double.isFinite(learningRate) || learningRate < 0.0d) {
      throw new IllegalArgumentException("learningRate must be finite and nonnegative");
    }
    final double biasCorrection =
        Math.sqrt(1.0d - Math.pow(BETA2, timestep)) / (1.0d - Math.pow(BETA1, timestep));
    for (int p = 0; p < weights.size(); p++) {
      final double[][] weight = weights.get(p);
      final double[][] gradient = gradients.get(p);
      final double[][] first = firstMoments.get(p);
      final double[][] second = secondMoments.get(p);
      final double scaledRate = learningRate * lrMultipliers.get(p);
      if (!Double.isFinite(scaledRate)) {
        throw new IllegalStateException("scaled learning rate must be finite");
      }
      for (int r = 0; r < weight.length; r++) {
        for (int i = 0; i < weight[r].length; i++) {
          final double g = gradient[r][i];
          final double firstMoment = BETA1 * first[r][i] + (1.0d - BETA1) * g;
          final double secondMoment = BETA2 * second[r][i] + (1.0d - BETA2) * g * g;
          final double updated = weight[r][i] - scaledRate * biasCorrection * firstMoment
              / (Math.sqrt(secondMoment) + EPSILON);
          if (!Double.isFinite(g) || !Double.isFinite(firstMoment)
              || !Double.isFinite(secondMoment) || !Double.isFinite(updated)) {
            throw new IllegalStateException("Adam update must be finite at parameter " + p
                + ", row " + r + ", component " + i);
          }
          first[r][i] = firstMoment;
          second[r][i] = secondMoment;
          weight[r][i] = updated;
        }
      }
    }
  }

  /**
   * Allocates an independent, zeroed set of gradient buffers shaped like the
   * registered gradients, for one parallel worker to accumulate into.
   *
   * @return The buffers, in registration order. Never {@code null}.
   */
  List<double[][]> newGradientBuffers() {
    return newGradientBuffers(-1);
  }

  /**
   * Allocates an independent, zeroed set of gradient buffers shaped like the
   * registered gradients, leaving a {@code null} hole at {@code skipIndex}, for one
   * parallel worker to accumulate into. The skipped registration is expected to be
   * fed sparsely through {@link #absorbSparse}.
   *
   * @param skipIndex The registration to skip, or a negative value for none.
   * @return The buffers, in registration order; may contain one {@code null} hole.
   */
  List<double[][]> newGradientBuffers(int skipIndex) {
    final List<double[][]> buffers = new ArrayList<>(gradients.size());
    for (int p = 0; p < gradients.size(); p++) {
      if (p == skipIndex) {
        buffers.add(null);
        continue;
      }
      final double[][] gradient = gradients.get(p);
      final double[][] copy = new double[gradient.length][];
      for (int r = 0; r < gradient.length; r++) {
        copy[r] = new double[gradient[r].length];
      }
      buffers.add(copy);
    }
    return buffers;
  }

  /**
   * Adds worker buffers into the registered gradient mirrors, in place. Called in a
   * fixed worker order so parallel training stays deterministic.
   *
   * @param buffers The worker buffers, as allocated by {@link #newGradientBuffers()}.
   *        Must not be {@code null}.
   */
  void absorb(List<double[][]> buffers) {
    for (int p = 0; p < gradients.size(); p++) {
      final double[][] gradient = gradients.get(p);
      final double[][] buffer = buffers.get(p);
      if (buffer == null) {
        continue;
      }
      for (int r = 0; r < gradient.length; r++) {
        for (int i = 0; i < gradient[r].length; i++) {
          gradient[r][i] += buffer[r][i];
        }
      }
    }
  }

  /**
   * Adds sparse row gradients into the registered gradient mirror, in row order.
   * Used for large, sparsely-touched tables so parallel workers need no dense
   * per-worker copy; called in a fixed worker order so parallel training stays
   * deterministic.
   *
   * @param registration The registration index whose mirror receives the rows.
   * @param rowGradients The per-row gradients, keyed by row index. Must not be
   *        {@code null}.
   */
  void absorbSparse(int registration, SortedMap<Integer, double[]> rowGradients) {
    final double[][] gradient = gradients.get(registration);
    for (final Map.Entry<Integer, double[]> entry : rowGradients.entrySet()) {
      final double[] row = gradient[entry.getKey()];
      final double[] add = entry.getValue();
      for (int i = 0; i < row.length; i++) {
        row[i] += add[i];
      }
    }
  }

  /**
   * Resets worker buffers to zero.
   *
   * @param buffers The worker buffers. Must not be {@code null}.
   */
  static void zero(List<double[][]> buffers) {
    for (final double[][] buffer : buffers) {
      if (buffer == null) {
        continue;
      }
      for (final double[] row : buffer) {
        Arrays.fill(row, 0.0d);
      }
    }
  }

  /**
   * Resets every accumulated gradient to zero; moments are kept.
   */
  void zero() {
    for (final double[][] gradient : gradients) {
      for (final double[] row : gradient) {
        Arrays.fill(row, 0.0d);
      }
    }
  }
}
