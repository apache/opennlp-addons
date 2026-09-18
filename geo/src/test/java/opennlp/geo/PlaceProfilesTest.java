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
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static opennlp.geo.PlaceProfilesTestSupport.load;

/**
 * Tests place-profile similarity against a project-authored miniature table; no
 * external metadata is involved.
 */
public class PlaceProfilesTest {

  private static final String TABLE = String.join("\n",
      "id\tdensity\tincome\ttransit",
      "park-slope\t38000\t95000\t9",
      "brooklyn-heights\t36000\t110000\t9",
      "suburbia\t2000\t85000\t2",
      "rural-town\t150\t45000\t1",
      "");

  private static PlaceProfiles profiles;

  /**
   * Loads the shared table fixture.
   *
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @BeforeAll
  static void loadProfiles() throws IOException {
    profiles = load(TABLE);
  }

  /** Similar urban profiles score higher than profiles with different measurements. */
  @Test
  void testSimilarProfilesScoreHigherThanDissimilarOnes() {
    final double urbanPair = profiles.similarity("park-slope", "brooklyn-heights");
    final double urbanRural = profiles.similarity("park-slope", "rural-town");
    Assertions.assertTrue(urbanPair > 0.9);
    Assertions.assertTrue(urbanRural < 0.0);
    Assertions.assertEquals(1.0, profiles.similarity("suburbia", "suburbia"), 1e-9);
  }

  /** Orders neighbors by decreasing similarity. */
  @Test
  void testMostSimilarRanksByProfile() {
    final List<PlaceProfiles.Neighbor> neighbors =
        profiles.mostSimilar("park-slope", 2);
    Assertions.assertEquals(2, neighbors.size());
    Assertions.assertEquals("brooklyn-heights", neighbors.get(0).id());
    Assertions.assertTrue(
        neighbors.get(0).similarity() > neighbors.get(1).similarity());
  }

  /** Lists metric names in column order and checks place membership. */
  @Test
  void testMetricsAndMembership() {
    Assertions.assertEquals(List.of("density", "income", "transit"),
        profiles.metrics());
    Assertions.assertTrue(profiles.contains("suburbia"));
    Assertions.assertFalse(profiles.contains("atlantis"));
  }

  /**
   * Rejects invalid headers, numeric cells, field counts, and empty data sections.
   *
   * @param table The malformed table.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "density\tincome\n",
      "id\tdensity\na\tnot-a-number\n",
      "id\tdensity\na\t1\t2\n",
      "id\tdensity\n"})
  void testMalformedTablesAreRejected(String table) {
    Assertions.assertThrows(IOException.class, () -> load(table));
  }

  /** Rejects null sources, unknown places, and invalid result counts. */
  @Test
  void testInvalidArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> PlaceProfiles.load((Path) null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> profiles.similarity("park-slope", "atlantis"));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> profiles.mostSimilar("park-slope", 0));
  }
}
