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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import opennlp.geo.OvertureGazetteer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicitly enabled because installing DuckDB spatial requires network access. */
@EnabledIfSystemProperty(named = "opennlp.geo.spatialTest", matches = "true")
class OvertureGeneratorParquetTest {
  @TempDir Path temporary;

  @Test void derivesLocalParquetAndLoadsTheActualGazetteer() throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:duckdb:");
         var statement = connection.createStatement()) {
      statement.execute("INSTALL spatial");
      statement.execute("LOAD spatial");
      Path parquet = temporary.resolve("divisions.parquet");
      Path output = temporary.resolve("table.txt");
      // Authored records exercise the same nested names, WKB point and nullable columns as the query.
      statement.execute("""
          CREATE TABLE divisions AS
          SELECT id, {'primary': name, 'common': map(['de'], ['München'])} AS names,
                 ST_AsWKB(ST_Point(11.576124,48.137154)) AS geometry,
                 'DE' AS country, subtype, population
          FROM (VALUES ('z', 'Munich', 'locality', 1500000),
                       ('a', 'Bavaria', 'region', NULL),
                       ('tiny', 'Tiny', 'locality', 2),
                       ('other', 'Other', 'water', 90000)) t(id,name,subtype,population)
          """);
      try (var copy = connection.prepareStatement("COPY divisions TO ? (FORMAT PARQUET)")) {
        copy.setString(1, parquet.toString());
        copy.execute();
      }
      assertEquals(2, OvertureGenerator.derive(connection, parquet.toString(), "2026-06-18.0",
          10000, LocalDate.of(2026, 9, 18), output));
      String table = Files.readString(output);
      assertTrue(table.contains("ODbL"));
      assertTrue(table.indexOf("a\tBavaria") < table.indexOf("z\tMunich"));
      var gazetteer = OvertureGazetteer.load(output);
      assertEquals(1, gazetteer.lookup("Munich").size());
      assertEquals(1, gazetteer.lookup("Bavaria").size());
      assertTrue(gazetteer.lookup("Tiny").isEmpty());
      statement.execute("DELETE FROM divisions");
      try (var copy = connection.prepareStatement("COPY divisions TO ? (FORMAT PARQUET)")) {
        copy.setString(1, parquet.toString());
        copy.execute();
      }
      assertThrows(IllegalArgumentException.class, () -> OvertureGenerator.derive(connection,
          parquet.toString(), "2026-06-18.0", 10000, LocalDate.of(2026, 9, 18), output));
      assertEquals(table, Files.readString(output));
    }
  }
}
