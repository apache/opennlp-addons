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
package opennlp.geo.dev;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OvertureGeneratorTest {
  @Test void sortsAndDeduplicatesAlternateNamesWithoutLosingUnicode() {
    assertEquals("place-1\tMünchen\tMonaco,Munich\t48.13715\t11.57612\tDE\tlocality\t1500000",
        OvertureGenerator.row("place-1", "München", Arrays.asList("Monaco", " Munich ",
            "München", "Monaco", null, ""), 48.137154, 11.576124, "DE", "locality", 1500000L));
  }

  @ParameterizedTest
  @ValueSource(strings = {"one,two", "one\ttwo", "one\ntwo", "one\rtwo"})
  void rejectsUnrepresentableAlternateNames(String alternate) {
    assertThrows(IllegalArgumentException.class, () -> OvertureGenerator.row("id", "City",
        List.of(alternate), 1.0, 2.0, "US", "locality", 3L));
  }

  @Test void preservesPrimaryNameCommaAndSkipsMissingPoint() {
    assertEquals("id\tCity, district\t\t1.00000\t2.00000\tUS\tlocality\t3",
        OvertureGenerator.row("id", "City, district", List.of(), 1.0, 2.0, "US", "locality", 3L));
    assertNull(OvertureGenerator.row("id", "City", List.of(), null, 2.0, "US", "locality", 3L));
    assertNull(OvertureGenerator.row("id", "", List.of(), 1.0, 2.0, "US", "locality", 3L));
  }

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, -91, 91})
  void rejectsInvalidLatitude(double latitude) {
    assertThrows(IllegalArgumentException.class, () -> OvertureGenerator.row("id", "City",
        List.of(), latitude, 2.0, "US", "locality", 3L));
  }

  @ParameterizedTest
  @ValueSource(strings = {"../escape", "2026-06-18.0'", "2026-02-30.0", "2026-06-18.-1", ""})
  void rejectsInvalidReleaseBeforeConnecting(String release) {
    assertThrows(IllegalArgumentException.class, () -> OvertureGenerator.source(release));
  }

  @Test void acceptsReleaseAndRejectsCommentRecordInjection() {
    assertEquals("s3://overturemaps-us-west-2/release/2026-06-18.0/theme=divisions/type=division/*",
        OvertureGenerator.source("2026-06-18.0"));
    assertThrows(IllegalArgumentException.class, () -> OvertureGenerator.row("#hidden", "City",
        List.of(), 1.0, 2.0, "US", "locality", 3L));
  }
}
