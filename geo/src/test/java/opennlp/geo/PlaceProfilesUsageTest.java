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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Demonstrates loading and querying a project-authored place-profile table. */
public class PlaceProfilesUsageTest {

  /** Density, income, and transit measurements for five fictional places. */
  private static final String TABLE = String.join("\n",
      "id\tdensity\tincome\ttransit",
      "metroville\t12000\t72000\t8",
      "harborview\t11000\t68000\t9",
      "suburb-glen\t1800\t85000\t3",
      "farmdale\t120\t46000\t1",
      "mill-city\t9500\t52000\t7",
      "");

  /** Profiles loaded from the test file. */
  private static PlaceProfiles profiles;

  /**
   * Writes and loads the table through the Path entry point.
   *
   * @param tempDir The test directory.
   * @throws IOException Thrown if the file cannot be written or loaded.
   */
  @BeforeAll
  static void loadProfilesFromFile(@TempDir Path tempDir) throws IOException {
    final Path table = tempDir.resolve("profiles.tsv");
    Files.writeString(table, TABLE, StandardCharsets.UTF_8);
    profiles = PlaceProfiles.load(table);
  }

  /**
   * Checks the metric names and place membership.
   */
  @Test
  void testLoadedTableExposesMetricsAndMembership() {
    Assertions.assertEquals(List.of("density", "income", "transit"), profiles.metrics());
    Assertions.assertTrue(profiles.contains("metroville"));
    Assertions.assertTrue(profiles.contains("mill-city"));
    Assertions.assertFalse(profiles.contains("shangri-la"));
  }

  /**
   * Checks pairwise scores with floating-point tolerance.
   */
  @Test
  void testPairwiseSimilaritiesMatchComputedScores() {
    Assertions.assertEquals(0.94217361531876,
        profiles.similarity("metroville", "harborview"), 1e-12);
    Assertions.assertEquals(0.27243341210254074,
        profiles.similarity("metroville", "mill-city"), 1e-12);
    Assertions.assertEquals(-0.34971429214039584,
        profiles.similarity("metroville", "suburb-glen"), 1e-12);
    Assertions.assertEquals(-0.9684681668897906,
        profiles.similarity("metroville", "farmdale"), 1e-12);
    Assertions.assertEquals(0.9999999999999999,
        profiles.similarity("metroville", "metroville"), 1e-15);
  }

  /**
   * Checks the complete neighbor ranking and excludes the query place.
   */
  @Test
  void testMostSimilarReturnsFullDescendingRanking() {
    final List<PlaceProfiles.Neighbor> neighbors = profiles.mostSimilar("metroville", 4);
    Assertions.assertEquals(4, neighbors.size());
    Assertions.assertEquals("harborview", neighbors.get(0).id());
    Assertions.assertEquals("mill-city", neighbors.get(1).id());
    Assertions.assertEquals("suburb-glen", neighbors.get(2).id());
    Assertions.assertEquals("farmdale", neighbors.get(3).id());
    Assertions.assertEquals(0.94217361531876, neighbors.get(0).similarity(), 1e-12);
    Assertions.assertEquals(0.27243341210254074, neighbors.get(1).similarity(), 1e-12);
    Assertions.assertEquals(-0.34971429214039584, neighbors.get(2).similarity(), 1e-12);
    Assertions.assertEquals(-0.9684681668897906, neighbors.get(3).similarity(), 1e-12);
    for (final PlaceProfiles.Neighbor neighbor : neighbors) {
      Assertions.assertNotEquals("metroville", neighbor.id());
    }
  }

  /**
   * Limits the ranking to the requested number of results.
   */
  @Test
  void testTruncatedRankingFromAnotherQueryPlace() {
    final List<PlaceProfiles.Neighbor> neighbors = profiles.mostSimilar("farmdale", 2);
    Assertions.assertEquals(2, neighbors.size());
    Assertions.assertEquals("suburb-glen", neighbors.get(0).id());
    Assertions.assertEquals("mill-city", neighbors.get(1).id());
    Assertions.assertEquals(0.15895265022433747, neighbors.get(0).similarity(), 1e-12);
    Assertions.assertEquals(-0.08091385909242901, neighbors.get(1).similarity(), 1e-12);
  }

  /** Returned metric names and rankings cannot modify the loaded profiles. */
  @Test
  void testImmutableResults() {
    Assertions.assertThrows(UnsupportedOperationException.class,
        () -> profiles.metrics().add("extra"));
    Assertions.assertThrows(UnsupportedOperationException.class,
        () -> profiles.mostSimilar("metroville", 2).clear());
    Assertions.assertEquals(3, profiles.metrics().size());
    Assertions.assertEquals(4, profiles.mostSimilar("metroville", Integer.MAX_VALUE).size());
  }

  /**
   * A single-place table has no comparative variation or neighbors.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @Test
  void testSinglePlace() throws IOException {
    final PlaceProfiles single = PlaceProfilesTestSupport.load("id\tv\na\t42\n");
    Assertions.assertEquals(0.0, single.similarity("a", "a"));
    Assertions.assertTrue(single.mostSimilar("a", 1).isEmpty());
  }

  /**
   * Shared profiles return consistent rankings during concurrent queries.
   *
   * @throws InterruptedException Thrown if the test is interrupted.
   * @throws ExecutionException Thrown if a query task fails.
   */
  @Test
  void testConcurrentQueries() throws InterruptedException, ExecutionException {
    final List<PlaceProfiles.Neighbor> urban = profiles.mostSimilar("metroville", 4);
    final List<PlaceProfiles.Neighbor> rural = profiles.mostSimilar("farmdale", 4);
    final List<Callable<List<PlaceProfiles.Neighbor>>> tasks = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      final String id = i % 2 == 0 ? "metroville" : "farmdale";
      tasks.add(() -> profiles.mostSimilar(id, 4));
    }
    try (var executor = Executors.newFixedThreadPool(4)) {
      final var results = executor.invokeAll(tasks);
      for (int i = 0; i < results.size(); i++) {
        Assertions.assertEquals(i % 2 == 0 ? urban : rural, results.get(i).get());
      }
    }
  }

  /**
   * Table line order does not change scores or rankings.
   *
   * @param seed The shuffle seed.
   * @throws IOException Thrown if the reordered table cannot be loaded.
   */
  @ParameterizedTest
  @ValueSource(longs = {1, 7, 43, 91})
  void testRowOrder(long seed) throws IOException {
    final List<String> lines = TABLE.lines().toList();
    final List<String> data = new ArrayList<>(lines.subList(1, lines.size()));
    Collections.shuffle(data, new Random(seed));
    final PlaceProfiles reordered = PlaceProfilesTestSupport.load(
        lines.getFirst() + "\n" + String.join("\n", data));
    Assertions.assertEquals(profiles.mostSimilar("metroville", 4),
        reordered.mostSimilar("metroville", 4));
    Assertions.assertEquals(profiles.mostSimilar("farmdale", 4),
        reordered.mostSimilar("farmdale", 4));
  }
}
