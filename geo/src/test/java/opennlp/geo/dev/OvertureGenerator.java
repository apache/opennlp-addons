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

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Developer tool for deriving an attributed division table from Overture Parquet. */
public final class OvertureGenerator {
  private static final String SUBTYPES = "country,dependency,region,county,localadmin,locality";
  private static final String QUERY = """
      SELECT id, names.primary AS name,
             COALESCE(map_values(names.common), []) AS alternates,
             ST_Y(ST_GeomFromWKB(geometry)) AS latitude,
             ST_X(ST_GeomFromWKB(geometry)) AS longitude,
             COALESCE(country, '') AS country, subtype, COALESCE(population, 0) AS population
      FROM read_parquet(?, filename=true, hive_partitioning=1)
      WHERE subtype IN ('country','dependency','region','county','localadmin','locality')
        AND (subtype != 'locality' OR COALESCE(population, 0) >= ?)
      ORDER BY id
      """;

  private OvertureGenerator() {
  }

  /** Run with a release, optional output path, and optional locality population floor. */
  public static void main(String[] args) throws SQLException, IOException {
    if (args.length < 1 || args.length > 3) {
      throw new IllegalArgumentException("Usage: OvertureGenerator release [output.txt] [min_population]");
    }
    String input = source(args[0]);
    Path output = Path.of(args.length >= 2 ? args[1] : "overture-divisions.txt");
    long minimum = args.length == 3 ? Long.parseLong(args[2]) : 10000;
    require(minimum >= 0, "Population floor must not be negative");
    try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
         var statement = connection.createStatement()) {
      statement.execute("INSTALL httpfs");
      statement.execute("LOAD httpfs");
      statement.execute("INSTALL spatial");
      statement.execute("LOAD spatial");
      statement.execute("SET s3_region='us-west-2'");
      long count = derive(connection, input, args[0], minimum, LocalDate.now(), output);
      System.out.println("wrote " + count + " divisions to " + output);
    }
  }

  /** Validate the release before constructing a path or opening a connection. */
  static String source(String release) {
    require(release != null && release.length() >= 12 && release.charAt(10) == '.', "Invalid release");
    try {
      LocalDate.parse(release.substring(0, 10));
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException("Invalid release date", exception);
    }
    for (int index = 11; index < release.length(); index++) {
      char cp = release.charAt(index);
      require(cp >= '0' && cp <= '9', "Invalid release revision");
    }
    return "s3://overturemaps-us-west-2/release/" + release + "/theme=divisions/type=division/*";
  }

  /** Stream rows to a temporary file so an invalid refresh leaves the reviewed table intact. */
  static long derive(Connection connection, String input, String release, long minimum,
                     LocalDate date, Path output) throws SQLException, IOException {
    source(release);
    require(minimum >= 0, "Population floor must not be negative");
    Path target = output.toAbsolutePath();
    Path temporary = Files.createTempFile(target.getParent(), "overture-", ".tmp");
    long count = 0;
    try {
      try (var query = connection.prepareStatement(QUERY);
           var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
        query.setString(1, input);
        query.setLong(2, minimum);
        writer.write("# Division table derived from Overture Maps divisions by Apache OpenNLP.\n"
            + "# Release " + release + ", subtypes " + SUBTYPES + ".\n"
            + "# Locality population floor " + minimum + ", derived " + date + ".\n"
            + "# Generated by OvertureGenerator.java. Upstream license: ODbL; attribution and\n"
            + "# database share-alike terms apply. Verify the named release's license records.\n");
        try (ResultSet rows = query.executeQuery()) {
          while (rows.next()) {
            var alternates = new ArrayList<String>();
            var array = rows.getArray("alternates");
            if (array != null) {
              try {
                for (Object value : (Object[]) array.getArray()) {
                  if (value != null) {
                    alternates.add((String) value);
                  }
                }
              } finally {
                array.free();
              }
            }
            String line = row(rows.getString("id"), rows.getString("name"), alternates,
                coordinate(rows, "latitude"), coordinate(rows, "longitude"),
                rows.getString("country"), rows.getString("subtype"),
                rows.getBigDecimal("population").longValueExact());
            if (line != null) {
              writer.write(line);
              writer.write('\n');
              count++;
            }
          }
        }
      }
      require(count > 0, "No usable divisions read from " + input + "; check release and schema");
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      return count;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /** Format the eight fields expected by OvertureGazetteer without ambiguous list separators. */
  static String row(String id, String name, List<String> alternates, Double latitude,
                    Double longitude, String country, String subtype, long population) {
    if (name == null || name.isBlank() || latitude == null || longitude == null) {
      return null;
    }
    require(id != null && !id.isBlank() && !id.startsWith("#"), "Invalid division id");
    require(Double.isFinite(latitude) && latitude >= -90 && latitude <= 90
        && Double.isFinite(longitude) && longitude >= -180 && longitude <= 180, "Invalid coordinates");
    require(population >= 0, "Invalid population");
    var cleanAlternates = new TreeSet<String>();
    for (String alternate : alternates) {
      if (alternate != null) {
        clean(alternate);
        alternate = alternate.strip();
        if (!alternate.isEmpty() && !alternate.equals(name)) {
          require(alternate.indexOf(',') < 0, "Comma in alternate name cannot be represented: " + alternate);
          cleanAlternates.add(alternate);
        }
      }
    }
    List<String> fields = List.of(id, name, String.join(",", cleanAlternates),
        decimal(latitude), decimal(longitude), country, subtype, Long.toString(population));
    fields.forEach(OvertureGenerator::clean);
    return String.join("\t", fields);
  }

  private static Double coordinate(ResultSet rows, String column) throws SQLException {
    double value = rows.getDouble(column);
    return rows.wasNull() ? null : value;
  }

  private static String decimal(double number) {
    BigDecimal rounded = new BigDecimal(number).setScale(5, RoundingMode.HALF_EVEN);
    return (Math.copySign(1, number) < 0 && rounded.signum() == 0 ? "-" : "")
        + rounded.toPlainString();
  }

  private static void clean(String value) {
    require(value.indexOf('\t') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0,
        "Separator in table field: " + value);
  }

  private static void require(boolean valid, String message) {
    if (!valid) {
      throw new IllegalArgumentException(message);
    }
  }
}
