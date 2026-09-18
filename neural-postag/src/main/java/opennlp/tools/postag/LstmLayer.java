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

import java.util.Arrays;
import java.util.Random;

/**
 * A single-direction LSTM recurrent layer with hand-rolled backpropagation through
 * time. Gate order inside the weight matrices is input, forget, cell candidate,
 * output. The forget-gate bias initializes to one, the common trick that keeps early
 * training from forgetting everything.
 *
 * <p>All arithmetic runs in {@code double}: training needs the precision for stable
 * gradients and for finite-difference gradient checks, and inference speed is a
 * separate concern handled by the caller's batching. Weights are effectively immutable
 * for readers; a concurrent forward pass only needs its own {@link ForwardCache}, so a
 * trained layer is thread-safe at inference. Training state lives in the separate
 * {@link Gradients} holder, never in the layer.</p>
 */
final class LstmLayer {

  /** Largest hidden width that permits four gate blocks in an integer array length. */
  static final int MAX_HIDDEN_SIZE = Integer.MAX_VALUE / 4;

  /** Gate-row block size for the cache-tiled gradient accumulation in backward. */
  private static final int ACCUMULATE_BLOCK = 32;

  private final int inputSize;
  private final int hiddenSize;
  private final double[][] w;
  private final double[][] u;
  private final double[] b;

  // Transposed views of w and u, read by backpropagation. Built eagerly so concurrent
  // workers never race to create them, and only valid until training mutates the
  // weights: every mutation must be followed by refreshTransposed().
  private double[][] wT;
  private double[][] uT;

  /**
   * Initializes a layer with Xavier-uniform input and recurrence weights, zero biases,
   * and a forget-gate bias of one.
   *
   * @param inputSize The number of input features per timestep. Must be positive.
   * @param hiddenSize The number of hidden units. Must be positive.
   * @param random The seeded source of init randomness. Must not be {@code null}.
   * @throws IllegalArgumentException If a size is not positive, {@code hiddenSize}
   *         exceeds {@link #MAX_HIDDEN_SIZE}, or {@code random} is {@code null}.
   */
  LstmLayer(int inputSize, int hiddenSize, Random random) {
    validateDimensions(inputSize, hiddenSize);
    if (random == null) {
      throw new IllegalArgumentException("random must not be null");
    }
    this.inputSize = inputSize;
    this.hiddenSize = hiddenSize;
    w = new double[4 * hiddenSize][inputSize];
    u = new double[4 * hiddenSize][hiddenSize];
    b = new double[4 * hiddenSize];
    final double wLimit = Math.sqrt(6.0d / (inputSize + 4.0d * hiddenSize));
    final double uLimit = Math.sqrt(6.0d / (hiddenSize + 4.0d * hiddenSize));
    for (int r = 0; r < w.length; r++) {
      for (int c = 0; c < inputSize; c++) {
        w[r][c] = (random.nextDouble() * 2.0d - 1.0d) * wLimit;
      }
      for (int c = 0; c < hiddenSize; c++) {
        u[r][c] = (random.nextDouble() * 2.0d - 1.0d) * uLimit;
      }
    }
    for (int j = 0; j < hiddenSize; j++) {
      b[hiddenSize + j] = 1.0d;
    }
    refreshTransposed();
  }

  /**
   * Takes over already initialized weight arrays; the validating entry point is
   * {@link #ofWeights(int, int, double[][], double[][], double[])}.
   *
   * @param inputSize The number of input features per timestep.
   * @param hiddenSize The number of hidden units.
   * @param w The input weights, shape {@code [4 * hiddenSize][inputSize]}.
   * @param u The recurrence weights, shape {@code [4 * hiddenSize][hiddenSize]}.
   * @param b The biases, length {@code 4 * hiddenSize}.
   */
  private LstmLayer(int inputSize, int hiddenSize, double[][] w, double[][] u,
      double[] b) {
    this.inputSize = inputSize;
    this.hiddenSize = hiddenSize;
    this.w = w;
    this.u = u;
    this.b = b;
    refreshTransposed();
  }

  /**
   * Wraps existing weight arrays without copying; used by model loading code in this
   * package.
   *
   * @param inputSize The number of input features per timestep, matching the column
   *                  count of {@code w}.
   * @param hiddenSize The number of hidden units, matching the column count of
   *                   {@code u}.
   * @param w The input weights, shape {@code [4 * hiddenSize][inputSize]}. Must not be
   *          {@code null}.
   * @param u The recurrence weights, shape {@code [4 * hiddenSize][hiddenSize]}. Must
   *          not be {@code null}.
   * @param b The biases, length {@code 4 * hiddenSize}. Must not be {@code null}.
   * @return A layer over the given arrays. Never {@code null}.
   * @throws IllegalArgumentException If a size is not positive, {@code hiddenSize}
   *         exceeds {@link #MAX_HIDDEN_SIZE}, or an array is {@code null} or its outer
   *         length is not {@code 4 * hiddenSize}.
   */
  static LstmLayer ofWeights(int inputSize, int hiddenSize, double[][] w, double[][] u,
      double[] b) {
    validateDimensions(inputSize, hiddenSize);
    if (w == null || u == null || b == null) {
      throw new IllegalArgumentException("weights must not be null");
    }
    if (w.length != 4 * hiddenSize || u.length != 4 * hiddenSize
        || b.length != 4 * hiddenSize) {
      throw new IllegalArgumentException("weight shapes do not match 4 * hiddenSize");
    }
    return new LstmLayer(inputSize, hiddenSize, w, u, b);
  }

  /**
   * Checks input and gate widths before calculating array dimensions.
   *
   * @param inputSize The input width.
   * @param hiddenSize The recurrent width.
   * @throws IllegalArgumentException If a size is not positive or the gate width overflows.
   */
  private static void validateDimensions(int inputSize, int hiddenSize) {
    if (inputSize <= 0 || hiddenSize <= 0) {
      throw new IllegalArgumentException("inputSize and hiddenSize must be positive");
    }
    if (hiddenSize > MAX_HIDDEN_SIZE) {
      throw new IllegalArgumentException("hiddenSize must not exceed " + MAX_HIDDEN_SIZE);
    }
  }

  /**
   * @return The number of input features per timestep.
   */
  int inputSize() {
    return inputSize;
  }

  /**
   * @return The number of hidden units.
   */
  int hiddenSize() {
    return hiddenSize;
  }

  /**
   * @return The input weights, shape {@code [4 * hiddenSize][inputSize]}. Package
   *         visible for training and serialization; treat as read-only.
   */
  double[][] w() {
    return w;
  }

  /**
   * @return The recurrence weights, shape {@code [4 * hiddenSize][hiddenSize]}.
   *         Package visible for training and serialization; treat as read-only.
   */
  double[][] u() {
    return u;
  }

  /**
   * @return The biases, length {@code 4 * hiddenSize}. Package visible for training
   *         and serialization; treat as read-only.
   */
  double[] b() {
    return b;
  }

  /**
   * Allocates a fresh gradient holder matching this layer's shapes, zero filled.
   *
   * @return A zeroed {@link Gradients}. Never {@code null}.
   */
  Gradients newGradients() {
    return new Gradients(4 * hiddenSize, inputSize, hiddenSize);
  }

  /**
   * Runs the layer over one sequence from the zero state.
   *
   * @param xs The input sequence, {@code [T][inputSize]}. Must not be {@code null} or
   *           empty, and every row must match the input size.
   * @param cache Receives the per-timestep gate activations for backpropagation.
   *              Must not be {@code null}; match the sequence length.
   * @return The hidden states, {@code [T][hiddenSize]}. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code xs} is {@code null} or empty, or
   *         a row does not have {@link #inputSize()} entries.
   */
  double[][] run(double[][] xs, ForwardCache cache) {
    if (xs == null || xs.length == 0) {
      throw new IllegalArgumentException("xs must not be null or empty");
    }
    final int steps = xs.length;
    final double[][] h = new double[steps][hiddenSize];
    final double[] cPrev = new double[hiddenSize];
    final double[] hPrev = new double[hiddenSize];
    for (int t = 0; t < steps; t++) {
      final double[] x = xs[t];
      if (x.length != inputSize) {
        throw new IllegalArgumentException("input row " + t + " has wrong length");
      }
      final double[] iGate = cache.i[t];
      final double[] fGate = cache.f[t];
      final double[] gGate = cache.g[t];
      final double[] oGate = cache.o[t];
      final double[] cCell = cache.c[t];
      for (int j = 0; j < hiddenSize; j++) {
        double ai = b[j];
        double af = b[hiddenSize + j];
        double ag = b[2 * hiddenSize + j];
        double ao = b[3 * hiddenSize + j];
        for (int k = 0; k < inputSize; k++) {
          final double xv = x[k];
          ai += w[j][k] * xv;
          af += w[hiddenSize + j][k] * xv;
          ag += w[2 * hiddenSize + j][k] * xv;
          ao += w[3 * hiddenSize + j][k] * xv;
        }
        for (int k = 0; k < hiddenSize; k++) {
          final double hv = hPrev[k];
          ai += u[j][k] * hv;
          af += u[hiddenSize + j][k] * hv;
          ag += u[2 * hiddenSize + j][k] * hv;
          ao += u[3 * hiddenSize + j][k] * hv;
        }
        final double iv = sigmoid(ai);
        final double fv = sigmoid(af);
        final double gv = Math.tanh(ag);
        final double ov = sigmoid(ao);
        final double cv = fv * cPrev[j] + iv * gv;
        iGate[j] = iv;
        fGate[j] = fv;
        gGate[j] = gv;
        oGate[j] = ov;
        cCell[j] = cv;
        h[t][j] = ov * Math.tanh(cv);
      }
      System.arraycopy(h[t], 0, hPrev, 0, hiddenSize);
      System.arraycopy(cCell, 0, cPrev, 0, hiddenSize);
    }
    cache.storeH(h);
    return h;
  }

  /**
   * Rebuilds the transposed weight views used by backpropagation, allocating them
   * on first call. Training must call this after every optimizer step that mutates
   * the weights; only ever invoked single-threaded (construction, optimizer-step
   * wiring), never concurrently with a backward pass.
   */
  void refreshTransposed() {
    if (wT == null) {
      wT = new double[inputSize][4 * hiddenSize];
      uT = new double[hiddenSize][4 * hiddenSize];
    }
    for (int r = 0; r < 4 * hiddenSize; r++) {
      final double[] wRow = w[r];
      final double[] uRow = u[r];
      for (int k = 0; k < inputSize; k++) {
        wT[k][r] = wRow[k];
      }
      for (int k = 0; k < hiddenSize; k++) {
        uT[k][r] = uRow[k];
      }
    }
  }

  /**
   * Backpropagates hidden-state gradients through the sequence, accumulating parameter
   * gradients into {@code gradients} and input gradients into {@code dXs}. The caller
   * owns zeroing or reuse of both outputs.
   *
   * @param xs The input sequence the forward pass ran on. Must not be {@code null}.
   * @param cache The gate activations from that forward pass. Must not be {@code null}.
   * @param dH The gradient of the loss with respect to each hidden state,
   *           {@code [T][hiddenSize]}. Must not be {@code null}.
   * @param dXs Receives the gradient with respect to each input row,
   *            {@code [T][inputSize]}; added in place. Must not be {@code null}.
   * @param gradients Receives the accumulated parameter gradients. Must not be
   *                  {@code null}.
   */
  void backward(double[][] xs, ForwardCache cache, double[][] dH, double[][] dXs,
      Gradients gradients) {
    final int steps = xs.length;
    final double[] dCell = new double[hiddenSize];
    final double[] dHNext = new double[hiddenSize];
    final double[][] daAll = new double[steps][4 * hiddenSize];
    // Phase 1: the t-sequential recurrence. Pointwise gate gradients are kept per
    // timestep because phase 2 walks them in a different order.
    for (int t = steps - 1; t >= 0; t--) {
      final double[] cPrev = t > 0 ? cache.c[t - 1] : null;
      final double[] da = daAll[t];
      for (int j = 0; j < hiddenSize; j++) {
        final double dh = dH[t][j] + dHNext[j];
        final double tanhC = Math.tanh(cache.c[t][j]);
        final double ov = cache.o[t][j];
        final double iv = cache.i[t][j];
        final double fv = cache.f[t][j];
        final double gv = cache.g[t][j];
        final double dC = dCell[j] + dh * ov * (1.0d - tanhC * tanhC);
        final double dI = dC * gv;
        final double dF = dC * (cPrev != null ? cPrev[j] : 0.0d);
        final double dG = dC * iv;
        final double dO = dh * tanhC;
        dCell[j] = dC * fv;
        da[j] = dI * iv * (1.0d - iv);
        da[hiddenSize + j] = dF * fv * (1.0d - fv);
        da[2 * hiddenSize + j] = dG * (1.0d - gv * gv);
        da[3 * hiddenSize + j] = dO * ov * (1.0d - ov);
      }
      for (int k = 0; k < hiddenSize; k++) {
        final double[] uTk = uT[k];
        double sum = 0.0d;
        for (int r = 0; r < 4 * hiddenSize; r++) {
          sum += uTk[r] * da[r];
        }
        dHNext[k] = sum;
      }
      for (int k = 0; k < inputSize; k++) {
        final double[] wTk = wT[k];
        double sum = 0.0d;
        for (int r = 0; r < 4 * hiddenSize; r++) {
          sum += wTk[r] * da[r];
        }
        dXs[t][k] += sum;
      }
    }
    // Phase 2: parameter gradients, blocked over gate rows so each dw/du tile stays
    // cache resident across all timesteps. Every element accumulates its per-timestep
    // contributions time-descending, which is what keeps parallel training bit-exact.
    final double[][] dw = gradients.dw;
    final double[][] du = gradients.du;
    final double[] db = gradients.db;
    for (int r0 = 0; r0 < 4 * hiddenSize; r0 += ACCUMULATE_BLOCK) {
      final int rEnd = Math.min(r0 + ACCUMULATE_BLOCK, 4 * hiddenSize);
      for (int t = steps - 1; t >= 0; t--) {
        final double[] da = daAll[t];
        final double[] x = xs[t];
        final double[] hPrev = t > 0 ? cache.h(t - 1) : null;
        for (int r = r0; r < rEnd; r++) {
          final double daR = da[r];
          db[r] += daR;
          final double[] dwRow = dw[r];
          for (int k = 0; k < inputSize; k++) {
            dwRow[k] += daR * x[k];
          }
          if (hPrev != null) {
            final double[] duRow = du[r];
            for (int k = 0; k < hiddenSize; k++) {
              duRow[k] += daR * hPrev[k];
            }
          }
        }
      }
    }
  }

  /**
   * The logistic sigmoid, evaluated on the side of zero that keeps
   * {@link Math#exp(double)} from overflowing.
   *
   * @param x The gate pre-activation.
   * @return The sigmoid of {@code x}, in the range {@code (0, 1)}.
   */
  private static double sigmoid(double x) {
    if (x >= 0.0d) {
      return 1.0d / (1.0d + Math.exp(-x));
    }
    final double e = Math.exp(x);
    return e / (1.0d + e);
  }

  /**
   * Per-timestep gate activations of one forward pass, the cache backpropagation
   * reads. Rows are indexed {@code [timestep][hidden unit]}.
   */
  static final class ForwardCache {

    final double[][] i;
    final double[][] f;
    final double[][] g;
    final double[][] o;
    final double[][] c;

    private double[][] h;

    private ForwardCache(int steps, int hiddenSize) {
      i = new double[steps][hiddenSize];
      f = new double[steps][hiddenSize];
      g = new double[steps][hiddenSize];
      o = new double[steps][hiddenSize];
      c = new double[steps][hiddenSize];
      h = new double[steps][hiddenSize];
    }

    /**
     * Allocates a cache for a sequence of {@code steps} timesteps.
     *
     * @param steps The sequence length. Must be positive.
     * @param hiddenSize The layer's hidden size. Must be positive.
     * @return A zeroed cache. Never {@code null}.
     * @throws IllegalArgumentException Thrown if a size is not positive.
     */
    static ForwardCache of(int steps, int hiddenSize) {
      if (steps <= 0 || hiddenSize <= 0) {
        throw new IllegalArgumentException("steps and hiddenSize must be positive");
      }
      return new ForwardCache(steps, hiddenSize);
    }

    /**
     * Stores the hidden states of the forward pass; required for recurrence-weight
     * gradients.
     *
     * @param hidden The hidden states returned by the forward pass. Must not be
     *               {@code null}.
     */
    void storeH(double[][] hidden) {
      h = hidden;
    }

    /**
     * @param t The timestep.
     * @return The hidden state at timestep {@code t}.
     */
    double[] h(int t) {
      return h[t];
    }
  }

  /**
   * Mutable parameter-gradient accumulator for one layer, matching the layer's weight
   * shapes. Training zeroes it per batch with {@link #zero()}.
   */
  static final class Gradients {

    private final double[][] dw;
    private final double[][] du;
    private final double[] db;

    private Gradients(double[][] dw, double[][] du, double[] db) {
      this.dw = dw;
      this.du = du;
      this.db = db;
    }

    private Gradients(int gateSize, int inputSize, int hiddenSize) {
      this(new double[gateSize][inputSize], new double[gateSize][hiddenSize],
          new double[gateSize]);
    }

    /**
     * Wraps existing arrays as a gradient holder, so training can accumulate straight
     * into optimizer-owned storage without per-sentence copies.
     *
     * @param dw The input-weight gradient target. Must not be {@code null}.
     * @param du The recurrence-weight gradient target. Must not be {@code null}.
     * @param db The bias gradient target. Must not be {@code null}.
     * @return A holder over the given arrays. Never {@code null}.
     * @throws IllegalArgumentException Thrown if an array is {@code null}.
     */
    static Gradients wrap(double[][] dw, double[][] du, double[] db) {
      if (dw == null || du == null || db == null) {
        throw new IllegalArgumentException("gradient arrays must not be null");
      }
      return new Gradients(dw, du, db);
    }

    /**
     * @return The input-weight gradients. Never {@code null}.
     */
    double[][] dw() {
      return dw;
    }

    /**
     * @return The recurrence-weight gradients. Never {@code null}.
     */
    double[][] du() {
      return du;
    }

    /**
     * @return The bias gradients. Never {@code null}.
     */
    double[] db() {
      return db;
    }

    /**
     * Resets every accumulated gradient to zero.
     */
    void zero() {
      for (final double[] row : dw) {
        Arrays.fill(row, 0.0d);
      }
      for (final double[] row : du) {
        Arrays.fill(row, 0.0d);
      }
      Arrays.fill(db, 0.0d);
    }
  }
}
