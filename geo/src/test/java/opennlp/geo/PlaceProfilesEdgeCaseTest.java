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

package opennlp.geo;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static opennlp.geo.PlaceProfilesTestSupport.load;

/** Tests profile scoring, table validation, and ranking. */
public class PlaceProfilesEdgeCaseTest {

  /**
   * Identical nonzero profiles score one; opposite profiles score minus one.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testIdenticalProfilesScoreOne() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
        "id\ta\tb",
        "twin-1\t10\t5",
        "twin-2\t10\t5",
        "other\t2\t9",
        ""));
    Assertions.assertEquals(1.0, profiles.similarity("twin-1", "twin-2"), 1e-15);
    Assertions.assertEquals(profiles.similarity("twin-1", "twin-2"),
        profiles.similarity("twin-1", "twin-1"));
    Assertions.assertEquals(-1.0, profiles.similarity("twin-1", "other"), 1e-15);
  }

  /**
   * Checks perpendicular and opposite profiles in a symmetric table.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testOrthogonalAndOppositeProfilesScoreExactly() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
        "id\tx\ty",
        "north\t1\t0",
        "south\t-1\t0",
        "east\t0\t1",
        "west\t0\t-1",
        ""));
    Assertions.assertEquals(0.0, profiles.similarity("north", "east"));
    Assertions.assertEquals(0.0, profiles.similarity("north", "west"));
    Assertions.assertEquals(-1.0, profiles.similarity("north", "south"));
    Assertions.assertEquals(1.0, profiles.similarity("north", "north"));
    Assertions.assertEquals(3, profiles.mostSimilar("north", 10).size());
  }

  /**
   * Constant columns produce zero similarity, including self-comparisons.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testZeroVarianceProfilesScoreZeroEvenAgainstThemselves() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
        "id\ta\tb",
        "flat-1\t5\t7",
        "flat-2\t5\t7",
        ""));
    Assertions.assertEquals(0.0, profiles.similarity("flat-1", "flat-1"));
    Assertions.assertEquals(0.0, profiles.similarity("flat-1", "flat-2"));
  }

  /** Reports the physical line and field counts for a short data line. */
  @Test
  void testRowMissingAValueIsRejected() {
    final IOException e = Assertions.assertThrows(IOException.class,
        () -> load("id\ta\tb\nlonely\t1\n"));
    Assertions.assertEquals("row 2 has 2 fields, expected 3", e.getMessage());
  }

  /**
   * Rejects NaN and accepts the corresponding finite table.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testNotANumberValueIsRejected() throws IOException {
    final IOException e = Assertions.assertThrows(IOException.class,
        () -> load(String.join("\n",
            "id\tx\ty",
            "north\t1\tNaN",
            "south\t-1\t0",
            "east\t0\t1",
            "west\t0\t-1",
            "")));
    Assertions.assertEquals("non-finite value in row 2: NaN", e.getMessage());
    final PlaceProfiles repaired = load(String.join("\n",
        "id\tx\ty",
        "north\t1\t0",
        "south\t-1\t0",
        "east\t0\t1",
        "west\t0\t-1",
        ""));
    Assertions.assertEquals(-1.0, repaired.similarity("north", "south"));
    Assertions.assertEquals(0.0, repaired.similarity("north", "east"));
    Assertions.assertEquals(3, repaired.mostSimilar("north", 5).size());
  }

  /**
   * Rejects infinity with the value and line number.
   *
   * @param value The infinite cell value.
   */
  @ParameterizedTest
  @ValueSource(strings = {"Infinity", "-Infinity"})
  void testInfiniteValuesAreRejected(String value) {
    final IOException error = Assertions.assertThrows(IOException.class,
        () -> load("id\ta\nparis\t" + value + "\nlyon\t2.0\n"));
    Assertions.assertEquals("non-finite value in row 2: " + value, error.getMessage());
  }

  /**
   * Rejects Java float and double type suffixes in numeric cells.
   *
   * @param value The suffixed cell value.
   */
  @ParameterizedTest
  @ValueSource(strings = {"1.0f", "1.0d", "1.0F", "1.0D"})
  void testJavaLiteralSuffixValuesAreRejected(String value) {
    final IOException error = Assertions.assertThrows(IOException.class,
        () -> load("id\ta\nparis\t" + value + "\nlyon\t2.0\n"));
    Assertions.assertEquals("malformed value in row 2: " + value, error.getMessage());
  }

  /** Reports the value and line number for a nonnumeric cell. */
  @Test
  void testMalformedValueNamesTheOffendingCell() {
    final IOException e = Assertions.assertThrows(IOException.class,
        () -> load("id\ta\nparis\tnot-a-number\nlyon\t2.0\n"));
    Assertions.assertEquals("malformed value in row 2: not-a-number", e.getMessage());
  }

  /** Rejects a header without metric columns. */
  @Test
  void testHeaderWithoutMetricsIsRejected() {
    final IOException e = Assertions.assertThrows(IOException.class,
        () -> load("id\nlonely\n"));
    Assertions.assertEquals("the header must be: id, then at least one metric",
        e.getMessage());
  }

  /**
   * Rejects null arguments at the loader and query entry points.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testNullArgumentsAreRejected() throws IOException {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PlaceProfiles.load((InputStream) null));
    final PlaceProfiles profiles = load("id\ta\np\t1\nq\t2\n");
    Assertions.assertEquals("id must not be null",
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> profiles.similarity(null, "p")).getMessage());
    Assertions.assertEquals("otherId must not be null",
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> profiles.similarity("p", null)).getMessage());
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> profiles.mostSimilar(null, 1));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> profiles.contains(null));
  }

  /**
   * Standardization gives metric columns equal weight despite different units.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testMixedScaleMetricsContributeEqually() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
        "id\tgiga\ttiny",
        "alpha\t9000000000\t0.875",
        "beta\t1000000000\t0.125",
        "gamma\t5000000000\t0.5",
        ""));
    Assertions.assertEquals(-1.0000000000000002,
        profiles.similarity("alpha", "beta"), 1e-15);
    Assertions.assertEquals(0.0, profiles.similarity("alpha", "gamma"));
    Assertions.assertEquals(1.0000000000000002,
        profiles.similarity("alpha", "alpha"), 1e-15);
  }

  /**
   * Checks an exact binary midpoint at a large metric magnitude.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testHugeFiniteMagnitudesStandardizeSafely() throws IOException {
    final double unit = Math.scalb(1.0, 500);
    final PlaceProfiles profiles = load(String.join("\n",
        "id\tv",
        "big\t" + (3 * unit),
        "small\t" + unit,
        "mid\t" + (2 * unit),
        ""));
    Assertions.assertEquals(-1.0, profiles.similarity("big", "small"));
    Assertions.assertEquals(0.0, profiles.similarity("big", "mid"));
    Assertions.assertEquals(1.0, profiles.similarity("big", "big"));
  }

  /**
   * Accepts a column whose unscaled sum exceeds the double range.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testColumnWhosePlainSumWouldOverflowStandardizesCorrectly() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
        "id\tv",
        "x\t1e308",
        "y\t1.5e308",
        ""));
    Assertions.assertEquals(-1.0, profiles.similarity("x", "y"));
    Assertions.assertEquals(1.0, profiles.similarity("x", "x"));
  }

  /**
   * Accepts finite values whose squared deviations exceed the double range.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testMagnitudesBeyondSquaredDoubleRangeStandardizeCorrectly() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
        "id\tv",
        "a\t3e200",
        "b\t1e200",
        ""));
    Assertions.assertEquals(-1.0, profiles.similarity("a", "b"));
  }

  /**
   * Preserves similarity for small nonzero measurements.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testTinyMagnitudesKeepTheirSignal() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
        "id\tv",
        "a\t1e-200",
        "b\t3e-200",
        ""));
    Assertions.assertEquals(-1.0, profiles.similarity("a", "b"));
  }

  /**
   * Preserves opposite standardized signs when the raw deviation is subnormal.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testDeviationBelowSubnormalRange() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
            "id\tv",
            "p1\t0", "p2\t0", "p3\t0", "p4\t0",
            "p5\t0", "p6\t0", "p7\t0",
            "p8\t4.9e-324",
            ""));
    Assertions.assertEquals(-1.0, profiles.similarity("p1", "p8"));
    Assertions.assertEquals(1.0, profiles.similarity("p1", "p2"));
  }

  /**
   * Standardizes finite observations with differences exceeding the double range.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testSpreadBeyondDoubleRange() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
            "id\tsmall\thuge",
            "x\t1\t1.7976931348623157e308",
            "y\t2\t-1.7976931348623157e308",
            "z\t3\t-1.7976931348623157e308",
            ""));
    Assertions.assertTrue(Double.isFinite(profiles.similarity("x", "y")));
    Assertions.assertEquals(0.5, profiles.similarity("y", "z"), 1e-12);
  }

  /**
   * Changing a metric's units preserves the pairwise scores within rounding tolerance.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testStandardizationIsScaleInvariantAcrossTheDoubleRange() throws IOException {
    final PlaceProfiles huge = load(String.join("\n",
        "id\tsmall\thuge",
        "x\t1\t1e308",
        "y\t2\t1.5e308",
        "z\t3\t1e308",
        ""));
    final PlaceProfiles rescaled = load(String.join("\n",
        "id\tsmall\thuge",
        "x\t1\t1.0",
        "y\t2\t1.5",
        "z\t3\t1.0",
        ""));
    Assertions.assertEquals(rescaled.similarity("x", "z"), huge.similarity("x", "z"), 1e-12);
    Assertions.assertEquals(rescaled.similarity("x", "y"), huge.similarity("x", "y"), 1e-12);
    Assertions.assertEquals(-0.4999999999999996, huge.similarity("x", "z"), 1e-12);
    Assertions.assertEquals(-0.5000000000000002, huge.similarity("x", "y"), 1e-12);
  }

  /**
   * A constant large-valued column has no effect on similarity.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testConstantColumnOfHugeValuesContributesNothing() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
        "id\tv\tconstant",
        "a\t1\t1e308",
        "b\t3\t1e308",
        "c\t1e308\t1e308",
        ""));
    Assertions.assertEquals(List.of("v", "constant"), profiles.metrics());
    Assertions.assertEquals(-1.0, Math.signum(profiles.similarity("a", "c")));
  }

  /**
   * Equal scores are ordered by ascending place identifier.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testTiedScoresRankByAscendingIdentifier() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
        "id\tm1\tm2",
        "query\t4\t1",
        "twin-b\t1\t3",
        "twin-a\t1\t3",
        "far\t9\t9",
        ""));
    final List<PlaceProfiles.Neighbor> neighbors = profiles.mostSimilar("query", 3);
    Assertions.assertEquals(3, neighbors.size());
    Assertions.assertEquals("twin-a", neighbors.get(0).id());
    Assertions.assertEquals("twin-b", neighbors.get(1).id());
    Assertions.assertEquals(neighbors.get(0).similarity(), neighbors.get(1).similarity());
    Assertions.assertEquals(0.29643507578021855, neighbors.get(0).similarity(), 1e-12);
    Assertions.assertEquals("far", neighbors.get(2).id());
    Assertions.assertEquals(-0.6651076860027197, neighbors.get(2).similarity(), 1e-12);
  }

  /**
   * The result limit selects the lowest identifiers when scores are equal.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testTiedTopCountCutKeepsTheLowestIdentifiers() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
        "id\tm1\tm2",
        "query\t4\t1",
        "tie-c\t1\t3",
        "tie-a\t1\t3",
        "tie-b\t1\t3",
        ""));
    final List<PlaceProfiles.Neighbor> neighbors = profiles.mostSimilar("query", 2);
    Assertions.assertEquals(List.of("tie-a", "tie-b"),
        List.of(neighbors.get(0).id(), neighbors.get(1).id()));
    Assertions.assertEquals(neighbors.get(0).similarity(), neighbors.get(1).similarity());
  }

  /**
   * Accepts comments and blank lines before the header.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testCommentsAndBlankLinesBeforeTheHeaderAreIgnored() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
        "# compass fixture, derived from a project-authored source",
        "",
        "id\tx\ty",
        "north\t1\t0",
        "south\t-1\t0",
        ""));
    Assertions.assertEquals(List.of("x", "y"), profiles.metrics());
    Assertions.assertTrue(profiles.contains("north"));
    Assertions.assertEquals(-1.0, profiles.similarity("north", "south"));
  }

  /**
   * Skips indented comments between data lines.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testIndentedCommentBetweenDataRowsIsSkipped() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
        "id\tx\ty",
        "north\t1\t0",
        "   # a note between rows",
        "south\t-1\t0",
        ""));
    Assertions.assertTrue(profiles.contains("north"));
    Assertions.assertTrue(profiles.contains("south"));
    Assertions.assertEquals(-1.0, profiles.similarity("north", "south"));
  }

  /**
   * Removes surrounding whitespace from metric names.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testHeaderMetricNamesAreStripped() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
        "id\t population \tarea ",
        "a\t1\t2",
        "b\t2\t1",
        ""));
    Assertions.assertEquals(List.of("population", "area"), profiles.metrics());
  }

  /** Rejects an empty metric name and reports the header column. */
  @Test
  void testEmptyMetricNameInHeaderIsRejected() {
    final IOException e = Assertions.assertThrows(IOException.class,
        () -> load(String.join("\n",
            "id\tpopulation\t\tarea",
            "a\t1\t2\t3",
            "")));
    Assertions.assertEquals("empty metric name in header column 3", e.getMessage());
  }

  /** Reports an invalid header before parsing data lines. */
  @Test
  void testHeaderIsValidatedBeforeRows() {
    final IOException e = Assertions.assertThrows(IOException.class,
        () -> load(String.join("\n",
            "id\tpopulation\t\tarea",
            "a\t1\tnot-a-number\t3",
            "")));
    Assertions.assertEquals("empty metric name in header column 3", e.getMessage());
  }

  /** Rejects a whitespace-only identifier with the physical line number. */
  @Test
  void testWhitespaceOnlyIdIsRejected() {
    final IOException e = Assertions.assertThrows(IOException.class,
        () -> load(String.join("\n",
            "id\tv",
            " \t1",
            "b\t2",
            "")));
    Assertions.assertEquals("empty id in row 2", e.getMessage());
  }

  /** Rejects hexadecimal floating-point notation in a metric cell. */
  @Test
  void testHexadecimalFloatLiteralIsRejected() {
    final IOException e = Assertions.assertThrows(IOException.class,
        () -> load(String.join("\n",
            "id\tv",
            "a\t0x1.8p1",
            "b\t2",
            "")));
    Assertions.assertEquals("malformed value in row 2: 0x1.8p1", e.getMessage());
  }

  /** Rejects a table containing only comments and blank lines. */
  @Test
  void testTableOfOnlyCommentsHasNoHeader() {
    final IOException e = Assertions.assertThrows(IOException.class,
        () -> load("# just a note\n\n# and another\n"));
    Assertions.assertEquals("the profile table has no header", e.getMessage());
  }

  /**
   * Removes no-break spaces from identifiers and numeric cells.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testNoBreakSpaceAroundCellsIsStripped() throws IOException {
    final PlaceProfiles profiles = load(String.join("\n",
        "id\tx\ty",
        "\u00A0north\u00A0\t\u00A01\t0",
        "south\t-1\t0",
        ""));
    Assertions.assertTrue(profiles.contains("north"));
    Assertions.assertEquals(-1.0, profiles.similarity("north", "south"));
  }

  /** Rejects repeated identifiers before standardizing the table. */
  @Test
  void testDuplicateIdentifierIsRejected() {
    final IOException error = Assertions.assertThrows(IOException.class,
        () -> load(String.join("\n",
        "id\ta\tb",
        "dup\t100\t100",
        "dup\t1\t2",
        "twin\t1\t2",
        "far\t9\t5",
        "")));
    Assertions.assertEquals("duplicate id in row 3: dup", error.getMessage());
  }

  /**
   * Accepts CRLF line endings with comments and blank lines.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testCarriageReturnsCommentsAndBlankLinesAreIgnored() throws IOException {
    final String table = "id\tx\ty\r\n"
        + "# compass fixture with carriage returns and a comment\r\n"
        + "north\t1\t0\r\n"
        + "\r\n"
        + "south\t-1\t0\r\n"
        + "east\t0\t1\r\n"
        + "west\t0\t-1\r\n";
    final PlaceProfiles profiles = load(table);
    Assertions.assertEquals(List.of("x", "y"), profiles.metrics());
    Assertions.assertFalse(
        profiles.contains("# compass fixture with carriage returns and a comment"));
    Assertions.assertTrue(profiles.contains("north"));
    Assertions.assertEquals(3, profiles.mostSimilar("north", 10).size());
    Assertions.assertEquals(-1.0, profiles.similarity("north", "south"));
  }
}
