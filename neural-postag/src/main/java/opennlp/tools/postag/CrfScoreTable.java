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

import java.math.BigDecimal;
import java.util.Arrays;

/** Exact score sums stored as scaled 128-bit integers when the range permits. */
final class CrfScoreTable {

  private static final int FRACTION_BITS = 52;
  private static final int EXPONENT_BIAS = 1023;
  private static final int SUBNORMAL_SCALE = -1074;
  private static final long FRACTION_MASK = (1L << FRACTION_BITS) - 1;
  private static final int EXPONENT_MASK = 0x7ff;

  private final int scale;
  private long[] high;
  private long[] low;
  private BigDecimal[] large;
  private int size;

  /**
   * Selects a common binary scale with enough range for path-score differences.
   *
   * @param steps The number of positions.
   * @param parameters The finite model parameters.
   * @throws IllegalStateException Thrown if a score is not finite.
   */
  CrfScoreTable(int steps, double[][][] parameters) {
    int smallest = Integer.MAX_VALUE;
    int largest = Integer.MIN_VALUE;
    int count = 0;
    for (double[][] matrix : parameters) {
      for (double[] values : matrix) {
        count = Math.addExact(count, values.length);
        for (double value : values) {
          if (!Double.isFinite(value)) {
            throw new IllegalStateException("CRF scores must be finite");
          }
          if (value != 0) {
            final long bits = Double.doubleToRawLongBits(value);
            final int exponent = (int) ((bits >>> FRACTION_BITS) & EXPONENT_MASK);
            final long fraction = bits & FRACTION_MASK;
            final long significand = exponent == 0 ? fraction : fraction | (1L << FRACTION_BITS);
            smallest = Math.min(smallest, (exponent == 0 ? SUBNORMAL_SCALE
                : exponent - EXPONENT_BIAS - FRACTION_BITS)
                + Long.numberOfTrailingZeros(significand));
            largest = Math.max(largest, Math.getExponent(value));
          }
        }
      }
    }
    scale = smallest == Integer.MAX_VALUE ? 0 : smallest;
    // A difference of complete paths contains at most 4 * steps + 2 parameters.
    final int sumBits = Long.SIZE - Long.numberOfLeadingZeros(4L * steps + 1);
    final int capacity = Math.max(16, Math.multiplyExact(count, 2));
    if (largest == Integer.MIN_VALUE || largest - scale + 1 + sumBits < 2 * Long.SIZE) {
      high = new long[capacity];
      low = new long[capacity];
    } else {
      large = new BigDecimal[capacity];
    }
  }

  /**
   * Reserves score locations.
   *
   * @param count The number of locations.
   * @return Their indexes.
   */
  int[] allocate(int count) {
    final int required = Math.addExact(size, count);
    final int capacity = large == null ? high.length : large.length;
    if (required > capacity) {
      final int grown = Math.max(required, Math.multiplyExact(capacity, 2));
      if (large == null) {
        high = Arrays.copyOf(high, grown);
        low = Arrays.copyOf(low, grown);
      } else {
        large = Arrays.copyOf(large, grown);
      }
    }
    final int[] indexes = new int[count];
    for (int i = 0; i < count; i++) {
      indexes[i] = size++;
    }
    return indexes;
  }

  /**
   * Stores finite parameters without rounding.
   *
   * @param values The parameters.
   * @return The score indexes.
   */
  int[] put(double[] values) {
    final int[] indexes = allocate(values.length);
    for (int i = 0; i < values.length; i++) {
      final int index = indexes[i];
      final double value = values[i];
      if (large != null) {
        large[index] = new BigDecimal(value);
      } else if (value != 0) {
        final long bits = Double.doubleToRawLongBits(value);
        final int exponent = (int) ((bits >>> FRACTION_BITS) & EXPONENT_MASK);
        final long fraction = bits & FRACTION_MASK;
        long significand = exponent == 0 ? fraction : fraction | (1L << FRACTION_BITS);
        final int trailing = Long.numberOfTrailingZeros(significand);
        significand >>>= trailing;
        final int shift = (exponent == 0 ? SUBNORMAL_SCALE
            : exponent - EXPONENT_BIAS - FRACTION_BITS) + trailing - scale;
        if (bits < 0) {
          significand = -significand;
        }
        if (shift < Long.SIZE) {
          low[index] = significand << shift;
          high[index] = shift == 0 ? (significand < 0 ? -1 : 0)
              : significand >> (Long.SIZE - shift);
        } else {
          high[index] = significand << (shift - Long.SIZE);
        }
      }
    }
    return indexes;
  }

  /**
   * Stores a matrix of finite parameters.
   *
   * @param values The parameters.
   * @return The score indexes.
   */
  int[][] put(double[][] values) {
    final int[][] indexes = new int[values.length][];
    for (int i = 0; i < values.length; i++) {
      indexes[i] = put(values[i]);
    }
    return indexes;
  }

  /**
   * Adds scores, allowing the output location to equal either input location.
   *
   * @param output The result index.
   * @param a The first input index.
   * @param b The other input index.
   */
  void add(int output, int a, int b) {
    if (large == null) {
      final long sum = low[a] + low[b];
      high[output] = high[a] + high[b] + (Long.compareUnsigned(sum, low[a]) < 0 ? 1 : 0);
      low[output] = sum;
    } else {
      large[output] = large[a].add(large[b]);
    }
  }

  /**
   * Copies a score.
   *
   * @param output The result index.
   * @param input The source index.
   */
  void copy(int output, int input) {
    if (large == null) {
      high[output] = high[input];
      low[output] = low[input];
    } else {
      large[output] = large[input];
    }
  }

  /**
   * Compares exact scores.
   *
   * @param a The first index.
   * @param b The other index.
   * @return A negative, zero or positive comparison result.
   */
  int compare(int a, int b) {
    if (large != null) {
      return large[a].compareTo(large[b]);
    }
    final int comparison = Long.compare(high[a], high[b]);
    return comparison == 0 ? Long.compareUnsigned(low[a], low[b]) : comparison;
  }

  /**
   * Subtracts exact scores before converting the result to double.
   *
   * @param a The first index.
   * @param b The subtracted index.
   * @return The difference, or infinity if it exceeds the double range.
   */
  double difference(int a, int b) {
    if (large != null) {
      return large[a].subtract(large[b]).doubleValue();
    }
    long lo = low[a] - low[b];
    long hi = high[a] - high[b] - (Long.compareUnsigned(low[a], low[b]) < 0 ? 1 : 0);
    final boolean negative = hi < 0;
    if (negative) {
      hi = -hi - (lo == 0 ? 0 : 1);
      lo = -lo;
    }
    final int bits = hi == 0 ? Long.SIZE - Long.numberOfLeadingZeros(lo)
        : 2 * Long.SIZE - Long.numberOfLeadingZeros(hi);
    final int shift = Math.max(0, bits - FRACTION_BITS - 1);
    final long significand = shift == 0 ? lo : roundedShift(hi, lo, shift);
    final double result = Math.scalb((double) significand, scale + shift);
    return negative ? -result : result;
  }

  /**
   * Rounds a positive integer after a binary right shift, with even results at halfway points.
   *
   * @param hi The high bits.
   * @param lo The low bits.
   * @param shift The positive shift distance.
   * @return The rounded significand.
   */
  private long roundedShift(long hi, long lo, int shift) {
    final long result;
    final int comparison;
    if (shift < Long.SIZE) {
      result = (lo >>> shift) | (hi << (Long.SIZE - shift));
      comparison = Long.compareUnsigned(lo & ((1L << shift) - 1), 1L << (shift - 1));
    } else if (shift == Long.SIZE) {
      result = hi;
      comparison = Long.compareUnsigned(lo, Long.MIN_VALUE);
    } else {
      final int upperShift = shift - Long.SIZE;
      result = hi >>> upperShift;
      final int upperComparison = Long.compare(hi & ((1L << upperShift) - 1),
          1L << (upperShift - 1));
      comparison = upperComparison == 0 && lo != 0 ? 1 : upperComparison;
    }
    return comparison > 0 || (comparison == 0 && (result & 1) != 0) ? result + 1 : result;
  }
}
