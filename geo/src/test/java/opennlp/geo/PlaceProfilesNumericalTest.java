/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
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
package opennlp.geo;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static opennlp.geo.PlaceProfilesTestSupport.load;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks analytically known scores at large and small metric magnitudes. */
class PlaceProfilesNumericalTest {

  /** Precision used only by the independent decimal arithmetic reference. */
  private static final MathContext REFERENCE_PRECISION = new MathContext(100);

  /**
   * Rounding does not produce a score outside the cosine range.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testCosineRange() throws IOException {
    final PlaceProfiles profiles = load("id\ta\tb\ntwin-1\t10\t5\ntwin-2\t10\t5\nother\t2\t9\n");
    assertTrue(profiles.similarity("twin-1", "twin-2") <= 1.0);
    assertTrue(profiles.similarity("twin-1", "other") >= -1.0);
  }

  /**
   * A single positive observation has the opposite standardized sign from the zeros.
   *
   * @param value The nonzero observation.
   * @throws IOException Thrown if the finite table cannot be loaded.
   */
  @ParameterizedTest
  @ValueSource(strings = {"1", "1e-200", "1e-323", "4.9e-324", "1.7976931348623157e308"})
  void testOneNonzeroObservation(String value) throws IOException {
    final PlaceProfiles profiles = load("id\tv\na\t" + value + "\nb\t0\nc\t0\nd\t0\n");
    assertEquals(-1.0, profiles.similarity("a", "b"), 1e-15);
    assertEquals(1.0, profiles.similarity("b", "c"), 1e-15);
  }

  /**
   * Distinct one-hot profiles have cosine -1/(n-1) after column standardization.
   *
   * @param count The number of profiles and columns.
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @ParameterizedTest
  @ValueSource(ints = {2, 3, 4, 8, 32})
  void testOneHotProfiles(int count) throws IOException {
    final StringBuilder table = new StringBuilder("id");
    for (int m = 0; m < count; m++) {
      table.append('\t').append('m').append(m);
    }
    for (int row = 0; row < count; row++) {
      table.append('\n').append('p').append(row);
      for (int m = 0; m < count; m++) {
        table.append('\t').append(row == m ? 1 : 0);
      }
    }
    final PlaceProfiles profiles = load(table.toString());
    assertEquals(-1.0 / (count - 1), profiles.similarity("p0", "p1"), 1e-14);
    assertEquals(1.0, profiles.similarity("p0", "p0"), 1e-14);
  }

  /**
   * Centering retains differences smaller than one unit of the rounded raw mean.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testLargeCommonOffset() throws IOException {
    final PlaceProfiles profiles = load("id\tv\na\t10000000000000000\n"
        + "b\t10000000000000002\nc\t10000000000000004\nd\t10000000000000006\n");
    assertEquals(-1.0, profiles.similarity("b", "c"), 1e-15);
    assertEquals(1.0, profiles.similarity("c", "d"), 1e-15);
  }

  /**
   * Small nonzero profiles retain their direction even if their squared norm underflows.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testTinyNonzeroProfile() throws IOException {
    final PlaceProfiles profiles = load("id\tv\na\t-1\nb\t1\nc\t1e-200\nd\t-1e-200\n");
    assertEquals(1.0, profiles.similarity("c", "b"), 1e-15);
    assertEquals(-1.0, profiles.similarity("c", "d"), 1e-15);
  }

  /**
   * Scaling a finite column avoids overflow when centering opposite-sign extremes.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testOppositeDoubleExtremes() throws IOException {
    final String max = Double.toString(Double.MAX_VALUE);
    final PlaceProfiles profiles = load("id\tv\na\t" + max + "\nb\t-" + max + "\nc\t-" + max);
    assertEquals(-1.0, profiles.similarity("a", "b"), 1e-15);
    assertEquals(1.0, profiles.similarity("b", "c"), 1e-15);
  }

  /**
   * Compares scores with high-precision arithmetic over the exact input double values.
   *
   * @param seed The deterministic table seed.
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @ParameterizedTest
  @ValueSource(longs = {19, 47, 83, 127, 271})
  void testAgainstHighPrecisionReference(long seed) throws IOException {
    final Random random = new Random(seed);
    final double[][] values = new double[12][5];
    for (int row = 0; row < values.length; row++) {
      for (int m = 0; m < values[row].length; m++) {
        values[row][m] = Math.scalb(random.nextInt(21) - 10.0, (m - 2) * 500);
      }
    }
    compareWithReference(values);
  }

  /**
   * Distinguishes a rounded decimal fixture from a profile at the exact column mean.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testRoundedDecimalMagnitudesAgainstReference() throws IOException {
    compareWithReference(new double[][] {{1e150}, {2e150}, {3e150}});
    compareWithReference(new double[][] {{9e9, 0.9}, {1e9, 0.1}, {5e9, 0.5}});
  }

  /**
   * Nonzero profiles remain comparable across the full range of finite doubles.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testFullDoubleRangeAgainstReference() throws IOException {
    compareWithReference(new double[][] {{-Double.MAX_VALUE}, {Double.MAX_VALUE},
        {Double.MIN_VALUE}, {-Double.MIN_VALUE}});
    compareWithReference(new double[][] {{-Double.MAX_VALUE}, {Double.MAX_VALUE},
        {Double.MIN_VALUE}, {0.0}});
  }

  /**
   * Loads a numeric matrix and compares all pairwise scores with a reference calculation.
   *
   * @param values The input double values by place and metric.
   * @throws IOException Thrown if the table cannot be loaded.
   */
  private void compareWithReference(double[][] values) throws IOException {
    final StringBuilder table = new StringBuilder("id");
    final BigDecimal[][] standardized = new BigDecimal[values.length][values[0].length];
    final BigDecimal count = BigDecimal.valueOf(values.length);
    for (int m = 0; m < values[0].length; m++) {
      table.append('\t').append('m').append(m);
      BigDecimal sum = BigDecimal.ZERO;
      for (int row = 0; row < values.length; row++) {
        standardized[row][m] = new BigDecimal(values[row][m]);
        sum = sum.add(standardized[row][m]);
      }
      final BigDecimal mean = sum.divide(count, REFERENCE_PRECISION);
      BigDecimal squares = BigDecimal.ZERO;
      for (final BigDecimal[] profile : standardized) {
        profile[m] = profile[m].subtract(mean);
        squares = squares.add(profile[m].multiply(profile[m]));
      }
      final BigDecimal deviation = squares.divide(count, REFERENCE_PRECISION)
          .sqrt(REFERENCE_PRECISION);
      for (final BigDecimal[] profile : standardized) {
        profile[m] = deviation.signum() == 0 ? BigDecimal.ZERO
            : profile[m].divide(deviation, REFERENCE_PRECISION);
      }
    }
    for (int row = 0; row < values.length; row++) {
      table.append('\n').append('p').append(row);
      for (final double value : values[row]) {
        table.append('\t').append(value);
      }
    }
    final PlaceProfiles profiles = load(table.toString());
    for (int a = 0; a < values.length; a++) {
      for (int b = 0; b < values.length; b++) {
        assertEquals(referenceCosine(standardized[a], standardized[b]),
            profiles.similarity("p" + a, "p" + b), 1e-12, "places " + a + ", " + b);
      }
    }
  }

  /**
   * Computes cosine with high-precision products and sums.
   *
   * @param a The first standardized profile.
   * @param b The second standardized profile.
   * @return The cosine, or zero if a profile is zero.
   */
  private double referenceCosine(BigDecimal[] a, BigDecimal[] b) {
    BigDecimal dot = BigDecimal.ZERO;
    BigDecimal normA = BigDecimal.ZERO;
    BigDecimal normB = BigDecimal.ZERO;
    for (int m = 0; m < a.length; m++) {
      dot = dot.add(a[m].multiply(b[m]));
      normA = normA.add(a[m].multiply(a[m]));
      normB = normB.add(b[m].multiply(b[m]));
    }
    if (normA.signum() == 0 || normB.signum() == 0) {
      return 0.0;
    }
    return dot.divide(normA.multiply(normB).sqrt(REFERENCE_PRECISION), REFERENCE_PRECISION)
        .doubleValue();
  }
}
