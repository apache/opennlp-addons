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
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaturalEarthGeneratorTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path temporary;

  private static ObjectNode feature() {
    return (ObjectNode) JSON.readTree("""
        {"properties":{"NE_ID":7,"NAME":"Zürich","NAMEASCII":"Zurich",
          "NAMEALT":"Zarich|ZURICH|Zuerich|東京", "ISO_A2":"CH", "ADM1NAME":"Zürich",
          "POP_MAX":-99,"WIKIDATAID":"Q72","GEONAMESID":2657896.0},
         "geometry":{"type":"Point","coordinates":[8.5417,47.3769]}}
        """);
  }

  @Test void derivesNamesCoordinatesAndIdentifiers() {
    var generator = new NaturalEarthGenerator();
    assertEquals("naturalearth;7;Zurich;Zuerich;47.37690;8.54170;CH;Zurich;0;CITY;"
        + "wikidata=Q72|geonames=2657896", generator.row(feature()));
    assertEquals(1, generator.count("alt_dropped_damaged"));
    assertEquals(1, generator.count("alt_dropped_nonascii"));
  }

  @Test void knownRepairsRemainAuditable() {
    var input = feature();
    var properties = (ObjectNode) input.path("properties");
    properties.put("NE_ID", 1159112843L).put("NAME", "Sdid Bouzid")
        .put("NAMEASCII", "Sdid Bouzid").put("NAMEALT", "EdDamer")
        .put("ADM0NAME", "Tunisia").put("ADM1NAME", "MUdenine");
    var generator = new NaturalEarthGenerator();
    assertTrue(generator.row(input).contains(";Sidi Bouzid;Ed Damer;"));
    assertEquals(1, generator.count("name_corrected"));
    assertEquals(1, generator.count("adm1_repaired"));
    assertEquals(1, generator.count("alt_repaired"));
    assertThrows(IllegalArgumentException.class, generator::validateRepairs);
  }

  @Test void fallsBackToAsciiAndOmitsUnverifiedContainment() {
    var input = feature();
    ((ObjectNode) input.path("properties")).put("NAME", "東京").put("NAMEASCII", "Tokyo")
        .put("NAMEALT", "").put("ADM1NAME", "Unknown?").put("ISO_A2", "-99");
    var generator = new NaturalEarthGenerator();
    assertTrue(generator.row(input).contains(";Tokyo;;47.37690;8.54170;;;0;"));
    assertEquals(1, generator.count("name_fallback"));
    assertEquals(1, generator.count("adm1_omitted_damaged"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Bad;Name", "Bad|Name", "Bad\nName", "Bad\rName", "Bad?", "BadName"})
  void rejectsUnreviewedNameDamageOrRecordInjection(String name) {
    var input = feature();
    ((ObjectNode) input.path("properties")).put("NAME", name).put("NAMEASCII", name);
    assertThrows(IllegalArgumentException.class, () -> new NaturalEarthGenerator().row(input));
  }

  @ParameterizedTest
  @ValueSource(strings = {"[181,0]", "[0,91]", "[0]", "[null,0]", "[0,\"bad\"]"})
  void rejectsInvalidCoordinates(String coordinates) {
    var input = feature();
    ((ObjectNode) input.path("geometry")).set("coordinates", JSON.readTree(coordinates));
    assertThrows(IllegalArgumentException.class, () -> new NaturalEarthGenerator().row(input));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Q", "Q1|geonames=3", "Q١"})
  void rejectsInvalidExternalIdentifier(String identifier) {
    var input = feature();
    ((ObjectNode) input.path("properties")).put("WIKIDATAID", identifier);
    assertThrows(IllegalArgumentException.class, () -> new NaturalEarthGenerator().row(input));
  }

  @Test void rejectsFractionalIdsAndDuplicateRows() {
    var input = feature();
    ((ObjectNode) input.path("properties")).put("GEONAMESID", 2.5);
    assertThrows(IllegalArgumentException.class, () -> new NaturalEarthGenerator().row(input));
    var generator = new NaturalEarthGenerator();
    generator.row(feature());
    assertThrows(IllegalArgumentException.class, () -> generator.row(feature()));
  }

  @Test void foldingAndCaseAnomaliesUseCharacters() {
    assertEquals("AEroskobing Thorshofn Lodz ss - '", NaturalEarthGenerator.fold(
        "Ærøskøbing Þórshöfn Łódź ß \u2013 \u2019").text());
    assertEquals("A B C", NaturalEarthGenerator.collapse(" A\u00a0B\u2003 C "));
    assertFalse(NaturalEarthGenerator.corrupted("Fort McPherson"));
    assertFalse(NaturalEarthGenerator.corrupted("KwaZulu-Natal"));
    assertTrue(NaturalEarthGenerator.corrupted("Fort McPHerson"));
  }

  @Test void roundsTheActualCoordinateValueToFivePlaces() {
    var input = feature();
    ((ObjectNode) input.path("geometry")).set("coordinates",
        JSON.readTree("[103.910005,-0.000001]"));
    assertTrue(new NaturalEarthGenerator().row(input).contains(";-0.00000;103.91000;"));
  }

  @Test void outputIsIndependentOfDefaultLocale() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertTrue(new NaturalEarthGenerator().row(feature()).contains("47.37690;8.54170"));
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test void failedRefreshDoesNotOverwriteExistingTable() throws Exception {
    Path input = temporary.resolve("input.json");
    Path output = temporary.resolve("output.txt");
    Files.writeString(input, "{\"features\":[" + feature() + "]}");
    Files.writeString(output, "previous reviewed table");
    assertThrows(IllegalArgumentException.class, () -> NaturalEarthGenerator.generate(input, output));
    assertEquals("previous reviewed table", Files.readString(output));
  }
}
