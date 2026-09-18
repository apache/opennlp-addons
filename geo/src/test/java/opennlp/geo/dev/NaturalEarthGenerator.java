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
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import opennlp.tools.util.StringUtil;

/** Derives the bundled ASCII table from the pinned Natural Earth GeoJSON. Developer tooling only. */
public final class NaturalEarthGenerator {
  private static final String MIRROR_COMMIT = "789c9904087846cc3361302857aa2e76b0ae71ff";
  private static final String EXTRACTION_DATE = "2026-07-06";
  private static final Map<Long, String> NAME_CORRECTIONS = Map.of(1159112843L, "Sidi Bouzid");
  private static final Map<String, String> ALT_REPAIRS = Map.of(
      "EdDamer", "Ed Damer", "Hai PhNng", "Hai Phong", "Hai Phnng", "Hai Phong",
      "MMabatho (Mafikeng)", "Mmabatho (Mafikeng)");
  // Verified against accent-bearing sibling records or documented region names in the source.
  private static final Map<String, String> ADM1_REPAIRS = Map.ofEntries(
      Map.entry("Algeria\tBZchar", "Bechar"),
      Map.entry("Algeria\tTZbessa", "Tebessa"),
      Map.entry("Algeria\tSZtif", "Setif"),
      Map.entry("Angola\tBiO", "Bie"),
      Map.entry("Angola\tHuOla", "Huila"),
      Map.entry("Angola\tUGge", "Uige"),
      Map.entry("Argentina\tCRrdoba", "Cordoba"),
      Map.entry("Argentina\tNeuqutn", "Neuquen"),
      Map.entry("Argentina\tRRo Negro", "Rio Negro"),
      Map.entry("Argentina\tTucumtn", "Tucuman"),
      Map.entry("Brazil\tMaranh", "Maranhao"),
      Map.entry("Brazil\tPar", "Para"),
      Map.entry("Brazil\tRondinia", "Rondonia"),
      Map.entry("Cambodia\tStMng Tr", "Stoeng Treng"),
      Map.entry("Chile\tBHo-B", "Bio-Bio"),
      Map.entry("Chile\tLa Araucanpa", "La Araucania"),
      Map.entry("Chile\tTarapac", "Tarapaca"),
      Map.entry("Chile\tTarapace", "Tarapaca"),
      Map.entry("Chile\tTarapacm", "Tarapaca"),
      Map.entry("Colombia\tCaqueti", "Caqueta"),
      Map.entry("Congo (Kinshasa)\tCquateur", "Equateur"),
      Map.entry("Congo (Kinshasa)\tKasao-Occidental", "Kasai-Occidental"),
      Map.entry("Gabon\tMoyen-Ogooul", "Moyen-Ogooue"),
      Map.entry("Liberia\tGrandGedeh", "Grand Gedeh"),
      Map.entry("Liberia\tGrandKru", "Grand Kru"),
      Map.entry("Morocco\tLaRyoune - Boujdour - Sakia El Hamra", "Laayoune - Boujdour - Sakia El Hamra"),
      Map.entry("Morocco\tMekncs - Tafilalet", "Meknes - Tafilalet"),
      Map.entry("Norway\tMOre og Romsdal", "More og Romsdal"),
      Map.entry("Paraguay\tAlto Paran", "Alto Parana"),
      Map.entry("Paraguay\tAlto Paranp", "Alto Parana"),
      Map.entry("Paraguay\tBoqueran", "Boqueron"),
      Map.entry("Paraguay\tCanindey", "Canindeyu"),
      Map.entry("Paraguay\tItapga", "Itapua"),
      Map.entry("Peru\tHuRnuco", "Huanuco"),
      Map.entry("Peru\tSan Mart", "San Martin"),
      Map.entry("Tunisia\tKasssrine", "Kasserine"),
      Map.entry("Tunisia\tMUdenine", "Medenine"),
      Map.entry("Venezuela\tAnzoztegui", "Anzoategui"),
      Map.entry("Vietnam\tNinh Bmnh", "Ninh Binh"),
      Map.entry("Vietnam\tThMi Bmnh", "Thai Binh"),
      Map.entry("Vietnam\tTrM Vinh", "Tra Vinh"));
  private static final Set<String> LEGIT_CASE_ANOMALIES =
      Set.of("KwaZulu-Natal", "HaMerkaz", "HaDarom", "HaZafon");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final Set<Long> seen = new HashSet<>();
  private final Set<String> usedRepairs = new HashSet<>();
  private final Map<String, Integer> counts = new HashMap<>();

  NaturalEarthGenerator() {
    for (String key : List.of("name_corrected", "name_fallback", "alt_repaired",
        "alt_dropped_nonascii", "alt_dropped_damaged", "adm1_repaired",
        "adm1_omitted_damaged", "adm1_omitted_nonascii")) {
      counts.put(key, 0);
    }
  }

  /** Run with an input GeoJSON path and an optional output table path. */
  public static void main(String[] args) throws IOException {
    if (args.length < 1 || args.length > 2) {
      throw new IllegalArgumentException("Usage: NaturalEarthGenerator input.geojson [output.txt]");
    }
    generate(Path.of(args[0]), Path.of(args.length == 2 ? args[1] : "naturalearth-populated-places.txt"));
  }

  /** Validate all rows and audited repairs before replacing the output. */
  static void generate(Path input, Path output) throws IOException {
    JsonNode document;
    try (InputStream stream = Files.newInputStream(input)) {
      document = JSON.readTree(stream);
    }
    JsonNode features = document.path("features");
    require(features.isArray() && !features.isEmpty(), "Expected a nonempty features array");
    var generator = new NaturalEarthGenerator();
    Map<Long, String> rows = new TreeMap<>();
    for (JsonNode feature : features) {
      String row = generator.row(feature);
      rows.put(integer(feature.path("properties").path("NE_ID"), "NE_ID"), row);
    }
    generator.validateRepairs();
    String header;
    try (InputStream stream = NaturalEarthGenerator.class.getResourceAsStream("naturalearth-header.txt")) {
      if (stream == null) {
        throw new IOException("Missing Natural Earth header template");
      }
      header = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    // Strip the source template's license block; the generated data carries its derivation record.
    header = header.substring(header.indexOf("# Bundled gazetteer"));
    header = header.replace("{count}", Integer.toString(rows.size()))
        .replace("{date}", EXTRACTION_DATE).replace("{commit}", MIRROR_COMMIT);
    for (var entry : generator.counts.entrySet()) {
      header = header.replace("{" + entry.getKey() + "}", entry.getValue().toString());
    }
    require(ascii(header), "Non-ASCII header");
    Files.writeString(output, header + String.join("\n", rows.values()) + "\n", StandardCharsets.US_ASCII);
    System.out.println("rows: " + rows.size() + ", " + new TreeMap<>(generator.counts));
  }

  /** Convert one feature without silently carrying known encoding damage. */
  String row(JsonNode feature) {
    JsonNode properties = feature.path("properties");
    long id = integer(properties.path("NE_ID"), "NE_ID");
    require(id > 0 && seen.add(id), "Invalid or duplicate NE_ID: " + id);
    String rawName = collapse(text(properties, "NAME"));
    String rawAscii = collapse(text(properties, "NAMEASCII"));
    String name = NAME_CORRECTIONS.get(id);
    if (name != null) {
      increment("name_corrected");
      usedRepairs.add("NAME:" + id);
    } else {
      name = fold(rawName).text();
      if (name.isEmpty() || !ascii(name) || corrupted(name)) {
        name = fold(rawAscii).text();
        require(!name.isEmpty() && ascii(name) && !corrupted(name), "No clean name for " + id);
        increment("name_fallback");
      }
    }
    clean(name);
    Fold display = fold(rawName);
    var alternates = new ArrayList<String>();
    var alternateKeys = new HashSet<String>();
    alternateKeys.add(StringUtil.toLowerCase(name));
    var rawAlternates = new ArrayList<String>();
    if (NAME_CORRECTIONS.containsKey(id)) {
      increment("alt_dropped_damaged");
    } else {
      rawAlternates.add(rawAscii);
    }
    for (String field : List.of("NAMEALT", "NAMEPAR", "MEGANAME")) {
      String value = text(properties, field);
      int start = 0;
      for (int end = 0; end <= value.length(); end++) {
        if (end == value.length() || value.charAt(end) == '|') {
          rawAlternates.add(value.substring(start, end).strip());
          start = end + 1;
        }
      }
    }
    for (String raw : rawAlternates) {
      if (raw.isEmpty()) {
        continue;
      }
      String alternate = ALT_REPAIRS.get(raw);
      if (alternate == null) {
        alternate = collapse(fold(raw).text());
      } else {
        increment("alt_repaired");
        usedRepairs.add("ALT:" + raw);
      }
      if (!ascii(alternate)) {
        increment("alt_dropped_nonascii");
        continue;
      }
      if (ascii(display.text()) && damagedVariant(alternate, display)) {
        increment("alt_dropped_damaged");
        continue;
      }
      require(!corrupted(alternate), "Unrecognized damaged alternate for " + id + ": " + raw);
      clean(alternate);
      if (!alternate.isEmpty() && alternateKeys.add(StringUtil.toLowerCase(alternate))) {
        alternates.add(alternate);
      }
    }
    JsonNode geometry = feature.path("geometry");
    require("Point".equals(geometry.path("type").asString()), "Expected Point geometry for " + id);
    JsonNode coordinates = geometry.path("coordinates");
    require(coordinates.isArray() && coordinates.size() >= 2, "Missing coordinates for " + id);
    double longitude = coordinate(coordinates.get(0), 180);
    double latitude = coordinate(coordinates.get(1), 90);
    String iso = text(properties, "ISO_A2").strip();
    if (iso.length() != 2 || !upper(iso.charAt(0)) || !upper(iso.charAt(1))) {
      iso = "";
    }
    String admin = collapse(fold(text(properties, "ADM1NAME").strip()).text());
    String adminKey = text(properties, "ADM0NAME").strip() + "\t" + admin;
    if (ADM1_REPAIRS.containsKey(adminKey)) {
      admin = ADM1_REPAIRS.get(adminKey);
      usedRepairs.add("ADM1:" + adminKey);
      increment("adm1_repaired");
    } else if (admin.indexOf('?') >= 0 || admin.indexOf('\ufffd') >= 0) {
      admin = "";
      increment("adm1_omitted_damaged");
    } else if (!ascii(admin)) {
      admin = "";
      increment("adm1_omitted_nonascii");
    } else {
      require(!corrupted(admin), "Unrecognized damaged admin region for " + id);
    }
    clean(admin);
    JsonNode populationNode = properties.path("POP_MAX");
    long population = populationNode.isMissingNode() || populationNode.isNull() ? 0
        : Math.max(0, integer(populationNode, "POP_MAX"));
    var attributes = new ArrayList<String>();
    String wikidata = text(properties, "WIKIDATAID").strip();
    if (!wikidata.isEmpty()) {
      require(wikidata.charAt(0) == 'Q' && digits(wikidata, 1), "Invalid WIKIDATAID for " + id);
      attributes.add("wikidata=" + wikidata);
    }
    identifier(properties, "GEONAMESID", "geonames", attributes);
    identifier(properties, "WOF_ID", "whosonfirst", attributes);
    String row = String.join(";", "naturalearth", Long.toString(id), name,
        String.join("|", alternates), decimal(latitude), decimal(longitude), iso, admin,
        Long.toString(population), "CITY", String.join("|", attributes));
    require(ascii(row), "Non-ASCII row for " + id);
    return row;
  }

  /** Each audited repair must still correspond to an upstream record. */
  void validateRepairs() {
    var expected = new HashSet<String>();
    NAME_CORRECTIONS.keySet().forEach(key -> expected.add("NAME:" + key));
    ALT_REPAIRS.keySet().forEach(key -> expected.add("ALT:" + key));
    ADM1_REPAIRS.keySet().forEach(key -> expected.add("ADM1:" + key));
    expected.removeAll(usedRepairs);
    require(expected.isEmpty(), "Stale repair table entries: " + expected);
  }

  int count(String key) {
    return counts.get(key);
  }

  private void increment(String key) {
    counts.merge(key, 1, Integer::sum);
  }

  /** A folded string with the positions derived from non-ASCII input marked. */
  record Fold(String text, List<Boolean> accented) { }

  static Fold fold(String value) {
    var output = new StringBuilder();
    var accented = new ArrayList<Boolean>();
    String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
    for (int cursor = 0; cursor < decomposed.length();) {
      int cp = decomposed.codePointAt(cursor);
      cursor += Character.charCount(cp);
      int type = Character.getType(cp);
      if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
          || type == Character.ENCLOSING_MARK) {
        if (!accented.isEmpty()) {
          accented.set(accented.size() - 1, true);
        }
        continue;
      }
      String replacement = switch (cp) {
        case 0x00d0, 0x0110 -> "D";
        case 0x00f0, 0x0111 -> "d";
        case 0x00de -> "Th";
        case 0x00fe -> "th";
        case 0x00c6 -> "AE";
        case 0x00e6 -> "ae";
        case 0x0152 -> "OE";
        case 0x0153 -> "oe";
        case 0x00d8 -> "O";
        case 0x00f8 -> "o";
        case 0x00df -> "ss";
        case 0x0141 -> "L";
        case 0x0142 -> "l";
        case 0x0131 -> "i";
        case 0x2013, 0x2014 -> "-";
        case 0x2019 -> "'";
        default -> new String(Character.toChars(cp));
      };
      output.append(replacement);
      for (int index = 0; index < replacement.length(); index++) {
        accented.add(cp >= 128);
      }
    }
    return new Fold(output.toString(), accented);
  }

  private static boolean damagedVariant(String alternate, Fold display) {
    if (alternate.length() != display.text().length()) {
      return false;
    }
    int differences = 0;
    for (int index = 0; index < alternate.length(); index++) {
      if (alternate.charAt(index) != display.text().charAt(index)) {
        if (!display.accented().get(index) || ++differences > 1) {
          return false;
        }
      }
    }
    return differences == 1;
  }

  static String collapse(String value) {
    var output = new StringBuilder();
    boolean space = false;
    for (int cursor = 0; cursor < value.length();) {
      int cp = value.codePointAt(cursor);
      cursor += Character.charCount(cp);
      if (StringUtil.isWhitespace(cp)) {
        space = !output.isEmpty();
      } else {
        if (space) {
          output.append(' ');
        }
        output.appendCodePoint(cp);
        space = false;
      }
    }
    return output.toString();
  }

  /** Detect the upstream ASCII casing artifacts while allowing verified names and Mc prefixes. */
  static boolean corrupted(String value) {
    if (value.indexOf('?') >= 0 || value.indexOf('\ufffd') >= 0) {
      return true;
    }
    if (LEGIT_CASE_ANOMALIES.contains(value)) {
      return false;
    }
    var stripped = new StringBuilder();
    for (int cursor = 0; cursor < value.length(); cursor++) {
      boolean start = cursor == 0 || !word(value.charAt(cursor - 1));
      if (start && value.startsWith("Mc", cursor) && cursor + 2 < value.length()
          && upper(value.charAt(cursor + 2))) {
        cursor++;
      } else {
        stripped.append(value.charAt(cursor));
      }
    }
    for (int cursor = 0; cursor < stripped.length(); cursor++) {
      if (cursor + 1 < stripped.length() && lower(stripped.charAt(cursor))
          && upper(stripped.charAt(cursor + 1))) {
        return true;
      }
      boolean start = cursor == 0 || !word(stripped.charAt(cursor - 1));
      if (start && cursor + 2 < stripped.length() && upper(stripped.charAt(cursor))
          && upper(stripped.charAt(cursor + 1)) && lower(stripped.charAt(cursor + 2))) {
        return true;
      }
    }
    return false;
  }

  private static boolean upper(char value) {
    return value >= 'A' && value <= 'Z';
  }

  private static boolean lower(char value) {
    return value >= 'a' && value <= 'z';
  }

  private static boolean word(char value) {
    return Character.isLetterOrDigit(value) || value == '_';
  }

  private static boolean ascii(String value) {
    return value.codePoints().allMatch(cp -> cp < 128);
  }

  private static boolean digits(String value, int start) {
    if (value.length() <= start) {
      return false;
    }
    for (int index = start; index < value.length(); index++) {
      if (value.charAt(index) < '0' || value.charAt(index) > '9') {
        return false;
      }
    }
    return true;
  }

  private static void clean(String value) {
    require(value.indexOf(';') < 0 && value.indexOf('|') < 0 && value.indexOf('\n') < 0
        && value.indexOf('\r') < 0, "Separator in table field: " + value);
  }

  private static String text(JsonNode properties, String field) {
    JsonNode node = properties.path(field);
    if (node.isNull() || node.isMissingNode()) {
      return "";
    }
    require(node.isString(), "Expected text in " + field);
    String value = node.asString();
    require(value.indexOf('\r') < 0 && value.indexOf('\n') < 0, "Line break in " + field);
    return value;
  }

  private static long integer(JsonNode value, String field) {
    require(value.isNumber(), "Expected numeric " + field);
    try {
      return new BigDecimal(value.asString()).longValueExact();
    } catch (NumberFormatException | ArithmeticException exception) {
      throw new IllegalArgumentException("Expected integral " + field + ": " + value, exception);
    }
  }

  private static void identifier(JsonNode properties, String field, String key, List<String> output) {
    JsonNode value = properties.path(field);
    if (!value.isNull() && !value.isMissingNode()) {
      long id = integer(value, field);
      if (id > 0) {
        output.add(key + "=" + id);
      }
    }
  }

  private static double coordinate(JsonNode value, int limit) {
    require(value.isNumber(), "Expected numeric coordinate");
    double number = value.asDouble();
    require(Double.isFinite(number) && number >= -limit && number <= limit, "Invalid coordinate");
    return number;
  }

  private static String decimal(double number) {
    BigDecimal rounded = new BigDecimal(number).setScale(5, RoundingMode.HALF_EVEN);
    return (Math.copySign(1, number) < 0 && rounded.signum() == 0 ? "-" : "")
        + rounded.toPlainString();
  }

  private static void require(boolean valid, String message) {
    if (!valid) {
      throw new IllegalArgumentException(message);
    }
  }
}
