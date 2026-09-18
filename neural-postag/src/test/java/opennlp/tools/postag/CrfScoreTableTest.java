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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks exact score storage and rounded differences across binary exponents. */
class CrfScoreTableTest {

  /** {@return exponent ranges and reproducible random seeds} */
  private static Stream<Arguments> ranges() {
    final List<Arguments> cases = new ArrayList<>();
    for (int base : new int[] {-1074, -1022, -500, -100, 0, 100, 500, 900, 970}) {
      for (int width : new int[] {0, 1, 52, 63, 64, 65, 70, 100, 120, 128, 300, 1000, 2000}) {
        if (base + width <= 1023) {
          for (int seed = 0; seed < 8; seed++) {
            cases.add(Arguments.of(base, width, seed));
          }
        }
      }
    }
    return cases.stream();
  }

  /**
   * Checks parameter differences, accumulation, comparisons, copying and buffer growth.
   *
   * @param base The lowest exponent.
   * @param width The exponent range.
   * @param seed The random seed.
   */
  @ParameterizedTest
  @MethodSource("ranges")
  void testExactScores(int base, int width, int seed) {
    final Random random = new Random(seed);
    final double[] values = new double[17];
    for (int i = 0; i < values.length - 1; i++) {
      final int exponent = base + (i == 0 ? 0 : i == 1 ? width : random.nextInt(width + 1));
      values[i] = Math.scalb((random.nextBoolean() ? 1 : -1) * (1 + random.nextDouble()),
          exponent);
    }
    final CrfScoreTable table = new CrfScoreTable(16, new double[][][] {{values}});
    final int[] indexes = table.put(values);
    for (int a = 0; a < values.length; a++) {
      for (int b = 0; b < values.length; b++) {
        final BigDecimal expected = new BigDecimal(values[a]).subtract(new BigDecimal(values[b]));
        assertEquals(expected.doubleValue(), table.difference(indexes[a], indexes[b]),
            "difference " + a + ", " + b);
        assertEquals(expected.signum(), Integer.signum(table.compare(indexes[a], indexes[b])));
      }
    }
    final int[] extra = table.allocate(2000);
    final int sum = extra[0];
    table.copy(sum, indexes[values.length - 1]);
    BigDecimal expected = BigDecimal.ZERO;
    for (int i = 0; i < values.length; i++) {
      if (i % 2 == 0) {
        table.add(sum, sum, indexes[i]);
      } else {
        table.add(sum, indexes[i], sum);
      }
      expected = expected.add(new BigDecimal(values[i]));
      assertEquals(expected.doubleValue(), table.difference(sum, indexes[values.length - 1]));
      table.copy(extra[1], sum);
      assertEquals(0, table.compare(extra[1], sum));
    }
  }

  /** {@return values at and around halfway rounding points across storage boundaries} */
  private static Stream<Arguments> halfwayValues() {
    final List<Arguments> cases = new ArrayList<>();
    for (int exponent : new int[] {-900, 0, 900}) {
      for (int shift : new int[] {1, 63, 64, 65, 70}) {
        for (boolean odd : new boolean[] {false, true}) {
          final double power = Math.scalb(1.0, exponent);
          final double first = odd ? Math.nextUp(power) : power;
          final double halfUlp = Math.scalb(1.0, exponent - 53);
          for (double adjustment : new double[] {Math.nextDown(halfUlp), halfUlp,
              Math.nextUp(halfUlp)}) {
            for (int sign : new int[] {-1, 1}) {
              cases.add(Arguments.of(sign * first, sign * adjustment,
                  Math.scalb(1.0, exponent - 52 - shift)));
            }
          }
        }
      }
    }
    cases.add(Arguments.of(Double.MAX_VALUE, Double.MAX_VALUE, Math.ulp(Double.MAX_VALUE)));
    return cases.stream();
  }

  /**
   * Rounds once after exact addition, including when a sum exceeds the double range.
   *
   * @param first The initial score.
   * @param adjustment The score added to it.
   * @param small The parameter selecting the binary unit.
   */
  @ParameterizedTest
  @MethodSource("halfwayValues")
  void testRounding(double first, double adjustment, double small) {
    final double[] values = {first, adjustment, small, -adjustment, 0};
    final CrfScoreTable table = new CrfScoreTable(2, new double[][][] {{values}});
    final int[] indexes = table.put(values);
    final int sum = table.allocate(1)[0];
    table.add(sum, indexes[0], indexes[1]);
    assertEquals(new BigDecimal(first).add(new BigDecimal(adjustment)).doubleValue(),
        table.difference(sum, indexes[4]));
    table.add(sum, sum, indexes[3]);
    assertEquals(first, table.difference(sum, indexes[4]));
  }
}
