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

package opennlp.tools.pii;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline reference-data generation with complete-size synthetic registries. */
class ReferenceDataGeneratorTest {
  private static final String HEADER = "# Version 2026090601, Last Updated Mon Sep  7 07:07:01 2026 UTC";
  private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
  private static final String DATE = "2024-02-29";
  private static final String COUNTRY = "IBAN prefix country code (ISO 3166)";

  static String template(String kind) throws Exception {
    final String name = switch (kind) {
      case "iana" -> "IanaTlds";
      case "iban" -> "IbanLengths";
      default -> "PhoneNumberLengths";
    };
    return Files.readString(Path.of("src/main/java/opennlp/tools/pii/" + name + ".java"));
  }

  static String generate(String kind, String source) throws Exception {
    return ReferenceDataGenerator.generate(kind, source.getBytes(StandardCharsets.UTF_8),
        template(kind), REVISION, DATE);
  }

  static String validInput(String kind) {
    return switch (kind) {
      case "iana" -> iana(HEADER, labels());
      case "iban" -> iban(countries(), lengths());
      default -> phone("100", "10");
    };
  }

  private static List<String> labels() {
    final List<String> labels = new ArrayList<>();
    for (int i = 1000; i < 2000; i++) {
      labels.add("TEST" + i);
    }
    return labels;
  }

  private static String iana(String header, List<String> labels) {
    return header + "\n" + String.join("\n", labels) + "\n";
  }

  private static String quotedTable(String java, String field) {
    final int start = java.indexOf("String " + field + " =");
    final int end = java.indexOf(';', start);
    final StringBuilder result = new StringBuilder();
    boolean quoted = false;
    for (int i = start; i < end; i++) {
      final char c = java.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      } else if (quoted) {
        result.append(c);
      }
    }
    return result.toString();
  }

  @Test
  void ianaRoundTripAndRepeatability() throws Exception {
    final List<String> entries = labels();
    entries.add("W".repeat(63));
    entries.add("XN--P1AI");
    final String input = iana(HEADER, entries);
    final String result = generate("iana", input);
    assertEquals(String.join(",", entries), quotedTable(result, "TLDS"));
    assertTrue(result.contains("Snapshot " + HEADER.substring(2) + "; regenerated"));
    assertEquals(result, ReferenceDataGenerator.generate("iana", input.getBytes(StandardCharsets.UTF_8),
        result, REVISION, DATE));
  }

  static Stream<String> invalidHeaders() {
    return Stream.of("", "Version 2026090601", "# Version", "# Version invalid", "# Version 1 */ invalid /*",
        HEADER + " */", HEADER + "\\u002a\\u002f", HEADER.replace("UTC", "PST"),
        HEADER.replace("07:07:01", "25:07:01"), HEADER.replace("Sep  7", "Feb 30"),
        HEADER.replace("Mon", "Tue"), HEADER.replace("2026090601", "\u0662".repeat(10)));
  }

  @ParameterizedTest
  @MethodSource("invalidHeaders")
  void rejectsInvalidIanaHeaders(String header) {
    assertThrows(IllegalArgumentException.class, () -> generate("iana", iana(header, labels())));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-COM", "COM-", "coM", "A_B", "A.B", "A B", "ÉXAMPLE", "COM*/"})
  void rejectsInvalidIanaLabels(String label) {
    final List<String> entries = labels();
    entries.set(0, label);
    assertThrows(IllegalArgumentException.class, () -> generate("iana", iana(HEADER, entries)));
  }

  @Test
  void rejectsLongDuplicateUnsortedAndTruncatedIanaLists() {
    final List<String> longLabel = labels();
    longLabel.set(0, "A".repeat(64));
    final List<String> duplicate = labels();
    duplicate.set(1, duplicate.getFirst());
    final List<String> unsorted = labels();
    unsorted.set(0, "ZZZ");
    for (List<String> entries : List.of(longLabel, duplicate, unsorted,
        labels().subList(0, 999), List.<String>of())) {
      assertThrows(IllegalArgumentException.class, () -> generate("iana", iana(HEADER, entries)));
    }
  }

  private static List<String> countries() {
    final List<String> result = new ArrayList<>();
    for (int i = 0; i < 80; i++) {
      result.add("" + (char) ('A' + i / 26) + (char) ('A' + i % 26));
    }
    return result;
  }

  private static List<String> lengths() {
    final List<String> result = new ArrayList<>();
    for (int i = 0; i < 80; i++) {
      result.add(Integer.toString(15 + i % 20));
    }
    return result;
  }

  private static String iban(List<String> countries, List<String> lengths) {
    return COUNTRY + "\t" + String.join("\t", countries) + "\r\n"
        + "IBAN length\t" + String.join("\t", lengths)
        + "\r\nDescription\tCafé " + "x".repeat(11000) + "\r\n";
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTF-8", "UTF-8-BOM", "UTF-16", "UTF-16LE-BOM", "windows-1252"})
  void readsIbanEncodingsAndDigest(String encoding) throws Exception {
    final String input = iban(countries(), lengths());
    final byte[] bytes;
    if (encoding.endsWith("-BOM")) {
      final boolean utf8 = encoding.equals("UTF-8-BOM");
      final byte[] bom = utf8 ? new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf}
          : new byte[]{(byte) 0xff, (byte) 0xfe};
      final byte[] content = input.getBytes(utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
      bytes = new byte[bom.length + content.length];
      System.arraycopy(bom, 0, bytes, 0, bom.length);
      System.arraycopy(content, 0, bytes, bom.length, content.length);
    } else {
      bytes = input.getBytes(Charset.forName(encoding));
    }
    final String generated = ReferenceDataGenerator.generate(
        "iban", bytes, template("iban"), REVISION, DATE);
    final StringBuilder expected = new StringBuilder();
    for (int i = 0; i < 80; i++) {
      expected.append(countries().get(i)).append(lengths().get(i));
    }
    assertEquals(expected.toString(), quotedTable(generated, "REGISTRY"));
    assertTrue(generated.contains(HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256")
            .digest(expected.toString().getBytes(StandardCharsets.US_ASCII)))));
    assertEquals(generated, ReferenceDataGenerator.generate("iban", bytes, generated, REVISION, DATE));
  }

  static Stream<Arguments> invalidIbanFields() {
    return Stream.concat(Stream.of("A", "ABC", "aA", "12", "ÉA").map(s -> Arguments.of(true, s)),
        Stream.of("", "1", "014", "14", "35", "2x", "-2", "\u0662\u0662").map(s -> Arguments.of(false, s)));
  }

  @ParameterizedTest
  @MethodSource("invalidIbanFields")
  void rejectsInvalidIbanFields(boolean country, String value) {
    final List<String> countries = countries();
    final List<String> lengths = lengths();
    (country ? countries : lengths).set(0, value);
    assertThrows(IllegalArgumentException.class, () -> generate("iban", iban(countries, lengths)));
  }

  @Test
  void validatesEveryIbanRowAndSourceCompleteness() {
    final String input = iban(countries(), lengths());
    final List<String> duplicate = countries();
    duplicate.set(1, duplicate.getFirst());
    final List<String> unsorted = countries();
    unsorted.set(0, "ZZ");
    for (String malformed : List.of("small", input + "Extra\t\"unterminated", input + COUNTRY + "\tAA\n",
        input + "IBAN\u00a0length\t20\n", iban(countries().subList(0, 79), lengths().subList(0, 79)),
        iban(countries(), lengths().subList(0, 79)), iban(duplicate, lengths()), iban(unsorted, lengths()),
        input.replace(COUNTRY, "Other label"), input + "\u0000")) {
      assertThrows(IllegalArgumentException.class, () -> generate("iban", malformed));
    }
  }

  @Test
  void acceptsQuotedIbanFieldsAndNormalizedRowLabels() throws Exception {
    final String input = iban(countries(), lengths());
    assertEquals(generate("iban", input), generate("iban", input
        .replace(COUNTRY, "\" " + COUNTRY.replace(" ", "\u00a0") + " \"")
        .replace("IBAN length", " IBAN length ")
        .replace("\tAA\t", "\t\u00a0AA\u00a0\t")
        .replace("\t15\t", "\t\u00a015\u00a0\t")));
  }

  private static String phone(String code, String lengths) {
    final StringBuilder xml = new StringBuilder("<phoneNumberMetadata><territories>");
    for (int i = 100; i < 300; i++) {
      xml.append("<territory countryCode=\"").append(i == 100 ? code : Integer.toString(i))
          .append("\"><fixedLine><possibleLengths national=\"").append(i == 100 ? lengths : "10")
          .append("\" localOnly=\"5\"/></fixedLine></territory>");
    }
    return xml + "</territories></phoneNumberMetadata>";
  }

  @Test
  void phoneRoundTripAndSharedCodes() throws Exception {
    final String input = phone("100", "[8-10]").replace("</territories>",
        "<territory countryCode=\"100\"><mobile><possibleLengths national=\"10,12,13\"/>"
            + "</mobile></territory></territories>");
    final String generated = generate("phone", input);
    assertTrue(generated.contains("100, 0x3700,"));
    assertTrue(generated.contains("101, 0x400,"));
    assertTrue(generated.contains("revision {@code " + REVISION.substring(0, 12) + "} of " + DATE));
    assertEquals(generated, ReferenceDataGenerator.generate("phone", input.getBytes(StandardCharsets.UTF_8),
        generated, REVISION, DATE));
  }

  static Stream<Arguments> acceptedLengths() {
    return Stream.of(Arguments.of("1", "0x2"), Arguments.of("31", "0x80000000"),
        Arguments.of("8,10", "0x500"), Arguments.of("[8-10]", "0x700"),
        Arguments.of("[3-5],7,9,[11-14]", "0x7AB8"), Arguments.of("[1-31]", "0xFFFFFFFE"));
  }

  @ParameterizedTest
  @MethodSource("acceptedLengths")
  void acceptsPhoneLengths(String value, String mask) throws Exception {
    assertTrue(generate("phone", phone("100", value)).contains("100, " + mask + ","));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "words", "10garbage12", "x10", "10x", "10;12", "10 12", "10.12",
      "-1", "+10", "1e1", "0xA", "10,", ",10", "10,,12", "10-12", "[10-12", "10-12]", "[10]",
      "[[10-12]]", "[10-12-14]", "[10-10]", "[10-11]", "[12-10]", "10,10", "[8-10],10",
      "[8-10],[10-12]", "[0-2]", "0", "32", "[30-32]", "\u0661\u0660", "10,\u0661\u0662"})
  void rejectsPhoneLengthSyntax(String value) {
    assertThrows(IllegalArgumentException.class, () -> generate("phone", phone("100", value)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "1000", "0100", "001", "-1", "+1", "1x", "\u0661"})
  void rejectsPhoneCodes(String value) {
    assertThrows(IllegalArgumentException.class, () -> generate("phone", phone(value, "10")));
  }

  static Stream<Arguments> invalidPhoneMetadata() {
    return Stream.concat(Stream.of("2024-02-30", "2023-02-29", "2024-13-01",
        "2024-00-01", "0000-01-01",
        "2024-2-01", "20240229", "\u0662\u0660\u0662\u0664-02-29").map(s -> Arguments.of(REVISION, s)),
        Stream.of("abc123", "a".repeat(41), "A".repeat(40), "not-a-revision")
            .map(s -> Arguments.of(s, DATE)));
  }

  @ParameterizedTest
  @MethodSource("invalidPhoneMetadata")
  void rejectsInvalidPhoneMetadata(String revision, String date) {
    assertThrows(IllegalArgumentException.class, () -> ReferenceDataGenerator.generate("phone",
        phone("100", "10").getBytes(StandardCharsets.UTF_8), template("phone"), revision, date));
  }

  @Test
  void acceptsPhoneMetadataInternalTypeDeclarations() throws Exception {
    final String input = phone("100", "10");
    final String declarations = "<!DOCTYPE phoneNumberMetadata ["
        + "<!ELEMENT phoneNumberMetadata ANY><!ELEMENT territories ANY>]>";
    assertEquals(generate("phone", input), generate("phone", declarations + input));
  }

  @Test
  void rejectsIncompletePhoneDataAndUnsafeXml() {
    final String input = phone("100", "10");
    for (String malformed : List.of(input.replace("phoneNumberMetadata", "html"),
        input.replace(" national=\"10\"", ""), input.replace("<possibleLengths", "<other"),
        phone("101", "10"), input.substring(0, input.indexOf("<territory countryCode=\"299\""))
            + "</territories></phoneNumberMetadata>",
        "<!DOCTYPE phoneNumberMetadata SYSTEM 'file:///nonexistent'>" + input,
        "<!DOCTYPE phoneNumberMetadata [<!ENTITY bad 'x'>]>" + input)) {
      assertThrows(IllegalArgumentException.class, () -> generate("phone", malformed));
    }
  }

  @Test
  void rejectsMissingSourceMarkersWithoutReturningPartialOutput() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> ReferenceDataGenerator.generate("iana",
        iana(HEADER, labels()).getBytes(StandardCharsets.UTF_8), template("iana").replace("String TLDS =",
        "String RENAMED ="), REVISION, DATE));
    assertThrows(IllegalArgumentException.class, () -> ReferenceDataGenerator.generate("phone",
        phone("100", "10").getBytes(StandardCharsets.UTF_8), template("phone").replace("CODE_AND_MASK = {",
        "RENAMED = {"), REVISION, DATE));
  }
}
