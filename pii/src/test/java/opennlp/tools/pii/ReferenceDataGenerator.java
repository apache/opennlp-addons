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

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** JDK-only source launcher for validating and regenerating PII reference tables. */
final class ReferenceDataGenerator {
  private ReferenceDataGenerator() {
  }

  /**
   * Generates a table into a temporary destination prepared by the shell wrapper.
   *
   * @param args Registry kind, input, output and optional phone revision and date.
   * @throws Exception If input validation, generation or file access fails.
   */
  public static void main(String[] args) throws Exception {
    if (args.length == 2 && args[0].equals("commit-date")) {
      System.out.println(commitDate(Files.readString(Path.of(args[1]))));
      return;
    }
    if (args.length != 3 && args.length != 5) {
      throw new IllegalArgumentException("Expected kind input output [revision date]");
    }
    final Path output = Path.of(args[2]);
    final String generated = generate(args[0], Files.readAllBytes(Path.of(args[1])),
        Files.readString(output), args.length == 5 ? args[3] : "", args.length == 5 ? args[4] : "");
    Files.writeString(output, generated);
  }

  /** Validates a registry fully before returning the replacement Java source. */
  static String generate(String kind, byte[] input, String template, String revision, String date) {
    try {
      return switch (kind) {
        case "iana" -> iana(input, template);
        case "iban" -> iban(input, template);
        case "phone" -> phone(input, template, revision, date);
        default -> throw new IllegalArgumentException("Unknown registry: " + kind);
      };
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid " + kind + " registry", e);
    }
  }

  /** Regenerates TLDs after validating the source header, every label and ordering. */
  private static String iana(byte[] input, String template) throws Exception {
    final List<String> lines = decode(input, StandardCharsets.UTF_8).lines().toList();
    require(!lines.isEmpty(), "Missing IANA header");
    final String header = lines.getFirst();
    final String prefix = "# Version ";
    final String middle = ", Last Updated ";
    require(header.startsWith(prefix) && header.length() > 20, "Invalid IANA header");
    require(asciiDigits(header.substring(10, 20)) && header.startsWith(middle, 20), "Invalid IANA header");
    final List<String> fields = tokens(header.substring(20 + middle.length()), ' ').stream()
        .filter(s -> !s.isEmpty()).toList();
    require(fields.size() == 6 && fields.get(5).equals("UTC"), "Invalid IANA header");
    final List<String> months = List.of("Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec");
    final List<String> weekdays = List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");
    final List<String> time = tokens(fields.get(3), ':');
    require(time.size() == 3 && time.stream().allMatch(s -> s.length() == 2 && asciiDigits(s)),
        "Invalid IANA header time");
    require(fields.get(2).length() <= 2 && fields.get(4).length() == 4, "Invalid IANA header date");
    final int year = decimal(fields.get(4));
    require(year > 0, "Invalid IANA header year");
    final LocalDateTime date = LocalDateTime.of(year, months.indexOf(fields.get(1)) + 1,
        decimal(fields.get(2)), decimal(time.get(0)), decimal(time.get(1)), decimal(time.get(2)));
    require(weekdays.indexOf(fields.get(0)) + 1 == date.getDayOfWeek().getValue(), "Invalid IANA weekday");
    final List<String> labels = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      if (strip(line).isEmpty() || line.startsWith("#")) {
        continue;
      }
      final String label = strip(line);
      require(!label.isEmpty() && label.length() <= 63 && label.charAt(0) != '-'
          && label.charAt(label.length() - 1) != '-', "Invalid IANA TLD length or edge hyphen");
      for (int i = 0; i < label.length(); i++) {
        final char c = label.charAt(i);
        require(c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-', "Invalid IANA TLD character");
      }
      require(labels.isEmpty() || labels.getLast().compareTo(label) < 0, "Unsorted or duplicate IANA TLDs");
      labels.add(label);
    }
    require(labels.size() >= 1000, "Too few IANA entries");
    final String packed = String.join(",", labels);
    final List<String> chunks = new ArrayList<>();
    for (int start = 0; start < packed.length();) {
      int end = Math.min(start + 90, packed.length());
      if (end < packed.length()) {
        end = packed.lastIndexOf(',', end) + 1;
        require(end > start, "Invalid IANA chunk");
      }
      chunks.add(packed.substring(start, end));
      start = end;
    }
    final String updated = replace(template, "Snapshot ", "; regenerated", header.substring(2));
    return replace(updated, "private static final String TLDS =\n", ";", quoted(chunks));
  }

  /** Reads the committer date from GitHub's commit object, ignoring unrelated nested keys. */
  private static String commitDate(String json) {
    final String value = jsonField(jsonField(jsonField(json, "commit"), "committer"), "date");
    require(value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"',
        "Invalid commit date");
    return java.time.OffsetDateTime.parse(value.substring(1, value.length() - 1)).toLocalDate().toString();
  }

  /** Locates a direct JSON object field without matching keys inside strings or nested values. */
  private static String jsonField(String source, String name) {
    final String json = source.strip();
    require(json.startsWith("{") && json.endsWith("}"), "Expected commit metadata object");
    int cursor = 1;
    while (cursor < json.length() - 1) {
      while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
        cursor++;
      }
      require(cursor < json.length() && json.charAt(cursor) == '"', "Expected commit metadata key");
      final int keyEnd = quoteEnd(json, cursor);
      final String key = json.substring(cursor + 1, keyEnd - 1);
      cursor = keyEnd;
      while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
        cursor++;
      }
      require(cursor < json.length() && json.charAt(cursor++) == ':', "Expected metadata colon");
      final int start = cursor;
      int depth = 0;
      while (cursor < json.length()) {
        final char c = json.charAt(cursor);
        if (c == '"') {
          cursor = quoteEnd(json, cursor);
          continue;
        }
        if (depth == 0 && (c == ',' || c == '}')) {
          break;
        }
        if (c == '{' || c == '[') {
          depth++;
        } else if (c == '}' || c == ']') {
          depth--;
        }
        cursor++;
      }
      require(depth == 0 && cursor < json.length(), "Invalid commit metadata value");
      if (key.equals(name)) {
        return json.substring(start, cursor).strip();
      }
      if (json.charAt(cursor++) != ',') {
        break;
      }
    }
    throw new IllegalArgumentException("Missing commit metadata field: " + name);
  }

  /** Skips a JSON string while retaining escaped delimiters as string content. */
  private static int quoteEnd(String text, int start) {
    for (int i = start + 1; i < text.length(); i++) {
      if (text.charAt(i) == '\\') {
        i++;
      } else if (text.charAt(i) == '"') {
        return i + 1;
      }
    }
    throw new IllegalArgumentException("Unclosed commit metadata string");
  }

  /** Reads SWIFT TSV registries with their supported BOM and legacy encodings. */
  private static String iban(byte[] input, String template) throws Exception {
    require(input.length >= 10000, "SWIFT registry is unexpectedly small");
    String decoded;
    if ((input[0] == (byte) 0xfe && input[1] == (byte) 0xff)
        || (input[0] == (byte) 0xff && input[1] == (byte) 0xfe)) {
      decoded = decode(input, StandardCharsets.UTF_16);
    } else {
      try {
        decoded = decode(input, StandardCharsets.UTF_8);
      } catch (CharacterCodingException e) {
        decoded = decode(input, Charset.forName("windows-1252"));
      }
      if (decoded.startsWith("\ufeff")) {
        decoded = decoded.substring(1);
      }
    }
    require(decoded.indexOf('\0') < 0, "Unsupported SWIFT registry encoding");
    final String countryLabel = "IBAN prefix country code (ISO 3166)";
    final Map<String, List<String>> rows = new TreeMap<>();
    for (List<String> row : tsv(decoded)) {
      final String label = row.getFirst().replace('\u00a0', ' ').strip();
      if (label.equals(countryLabel) || label.equals("IBAN length")) {
        require(!rows.containsKey(label), "Duplicate SWIFT row");
        rows.put(label, row.subList(1, row.size()).stream().map(ReferenceDataGenerator::strip).toList());
      }
    }
    final List<String> countries = rows.get(countryLabel);
    final List<String> lengths = rows.get("IBAN length");
    require(countries != null && lengths != null, "SWIFT registry lacks required rows");
    require(countries.size() >= 80 && countries.size() == lengths.size(),
        "Mismatched or too few SWIFT entries");
    final StringBuilder registry = new StringBuilder();
    for (int i = 0; i < countries.size(); i++) {
      final String country = countries.get(i);
      final String length = lengths.get(i);
      require(country.length() == 2 && country.chars().allMatch(c -> c >= 'A' && c <= 'Z'),
          "Invalid IBAN country");
      require(length.length() == 2 && decimal(length) >= 15 && decimal(length) <= 34, "Invalid IBAN length");
      require(i == 0 || countries.get(i - 1).compareTo(country) < 0, "Unsorted or duplicate IBAN countries");
      registry.append(country).append(length);
    }
    final String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(registry.toString().getBytes(StandardCharsets.US_ASCII)));
    final List<String> chunks = new ArrayList<>();
    for (int i = 0; i < registry.length(); i += 76) {
      chunks.add(registry.substring(i, Math.min(i + 76, registry.length())));
    }
    final String updated = replace(template, "Country-length projection SHA-256:\n * {@code ", "}.", digest);
    return replace(updated, "private static final String REGISTRY =\n", ";", quoted(chunks));
  }

  /** Parses tab-separated rows including escaped quotes and quoted multiline fields. */
  private static List<List<String>> tsv(String input) {
    final List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    final StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    boolean closed = false;
    for (int i = 0; i < input.length(); i++) {
      final char c = input.charAt(i);
      if (quoted) {
        if (c == '"' && i + 1 < input.length() && input.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else if (c == '"') {
          quoted = false;
          closed = true;
        } else {
          cell.append(c);
        }
      } else if (c == '\t' || c == '\r' || c == '\n') {
        row.add(cell.toString());
        cell.setLength(0);
        closed = false;
        if (c != '\t') {
          rows.add(row);
          row = new ArrayList<>();
          if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') {
            i++;
          }
        }
      } else {
        require(!closed, "Invalid text following a quoted SWIFT field");
        if (c == '"' && cell.isEmpty()) {
          quoted = true;
        } else {
          cell.append(c);
        }
      }
    }
    require(!quoted, "Unclosed SWIFT registry quote");
    if (!row.isEmpty() || !cell.isEmpty() || closed) {
      row.add(cell.toString());
      rows.add(row);
    }
    return rows;
  }

  /** Collects the union of national lengths for every calling code. */
  private static String phone(byte[] input, String template, String revision, String date) throws Exception {
    require(revision.length() >= 7 && revision.length() <= 40 && revision.chars()
        .allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f'), "Invalid phone metadata revision");
    require(date.length() == 10 && date.charAt(4) == '-' && date.charAt(7) == '-'
        && asciiDigits(date.substring(0, 4) + date.substring(5, 7) + date.substring(8)),
        "Invalid snapshot date");
    final LocalDate parsedDate = LocalDate.parse(date);
    require(parsedDate.getYear() > 0 && parsedDate.toString().equals(date), "Invalid snapshot date");
    final DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setExpandEntityReferences(false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    final Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(input));
    final DocumentType doctype = document.getDoctype();
    require(doctype == null || doctype.getSystemId() == null && doctype.getPublicId() == null
        && doctype.getEntities().getLength() == 0,
        "External DTDs and entities are not metadata declarations");
    final Element root = document.getDocumentElement();
    require(root.getTagName().equals("phoneNumberMetadata"), "Unexpected phone metadata root");
    final List<Element> territories = new ArrayList<>();
    for (int i = 0; i < root.getChildNodes().getLength(); i++) {
      if (root.getChildNodes().item(i) instanceof Element container
          && container.getTagName().equals("territories")) {
        for (int j = 0; j < container.getChildNodes().getLength(); j++) {
          if (container.getChildNodes().item(j) instanceof Element territory
              && territory.getTagName().equals("territory")) {
            territories.add(territory);
          }
        }
      }
    }
    require(territories.size() >= 200, "Too few phone territories");
    final Map<Integer, Integer> masks = new TreeMap<>();
    for (Element territory : territories) {
      final String code = territory.getAttribute("countryCode");
      require(!code.isEmpty() && code.length() <= 3 && code.charAt(0) != '0' && asciiDigits(code),
          "Invalid calling code");
      final NodeList possible = territory.getElementsByTagName("possibleLengths");
      require(possible.getLength() > 0, "Calling code has no national lengths");
      int mask = 0;
      for (int j = 0; j < possible.getLength(); j++) {
        mask |= lengthMask(((Element) possible.item(j)).getAttribute("national"));
      }
      masks.merge(decimal(code), mask, (left, right) -> left | right);
    }
    require(masks.size() >= 200, "Too few distinct calling codes");
    final StringBuilder table = new StringBuilder();
    StringBuilder line = new StringBuilder("      ");
    for (Map.Entry<Integer, Integer> entry : masks.entrySet()) {
      final String hex = HexFormat.of().withUpperCase().toHexDigits(entry.getValue());
      int start = 0;
      while (start < hex.length() - 1 && hex.charAt(start) == '0') {
        start++;
      }
      for (String token : List.of(entry.getKey().toString(), "0x" + hex.substring(start))) {
        if (line.length() + token.length() + 2 > 98) {
          table.append(line.toString().stripTrailing()).append('\n');
          line = new StringBuilder("      ");
        }
        line.append(token).append(", ");
      }
    }
    table.append(line.toString().stripTrailing()).append('\n');
    final String prefix = "revision {@code ";
    final int commentStart = unique(template, prefix) + prefix.length();
    final int revisionEnd = template.indexOf("} of ", commentStart);
    require(revisionEnd >= commentStart && revisionEnd + 15 <= template.length(),
        "Missing phone snapshot date");
    final String updated = template.substring(0, commentStart)
        + revision.substring(0, Math.min(12, revision.length()))
        + "} of " + date + template.substring(revisionEnd + 15);
    return replace(updated, "private static final int[] CODE_AND_MASK = {\n", "  };", table.toString());
  }

  /** Expands the documented comma and inclusive-range syntax without duplicate lengths. */
  private static int lengthMask(String input) {
    int mask = 0;
    for (String token : tokens(input, ',')) {
      final boolean range = token.startsWith("[") && token.endsWith("]");
      final List<String> endpoints = range
          ? tokens(token.substring(1, token.length() - 1), '-') : List.of(token);
      require(endpoints.size() == (range ? 2 : 1), "Invalid national length syntax");
      final int low = decimal(endpoints.getFirst());
      final int high = decimal(endpoints.getLast());
      require(low >= 1 && high <= 31 && (range ? high - low >= 2 : low == high),
          "Invalid national length range");
      for (int i = low; i <= high; i++) {
        final int bit = 1 << i;
        require((mask & bit) == 0, "Duplicate national length");
        mask |= bit;
      }
    }
    return mask;
  }

  /** Decodes strictly, so malformed byte sequences cannot silently alter generated data. */
  private static String decode(byte[] input, Charset charset) throws CharacterCodingException {
    return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString();
  }

  /** Splits on one literal delimiter, retaining empty fields for validation. */
  private static List<String> tokens(String input, char delimiter) {
    final List<String> result = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < input.length(); i++) {
      if (input.charAt(i) == delimiter) {
        result.add(input.substring(start, i));
        start = i + 1;
      }
    }
    result.add(input.substring(start));
    return result;
  }

  /** Trims Unicode spacing, including nonbreaking spaces in registry cells. */
  private static String strip(String text) {
    int start = 0;
    int end = text.length();
    while (start < end) {
      final int cp = text.codePointAt(start);
      if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
        break;
      }
      start += Character.charCount(cp);
    }
    while (end > start) {
      final int cp = text.codePointBefore(end);
      if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
        break;
      }
      end -= Character.charCount(cp);
    }
    return text.substring(start, end);
  }

  /** Checks the ASCII decimal alphabet before parsing. */
  private static boolean asciiDigits(String text) {
    return !text.isEmpty() && text.chars().allMatch(c -> c >= '0' && c <= '9');
  }

  /** Parses validated ASCII decimal fields. */
  private static int decimal(String text) {
    require(asciiDigits(text), "Invalid ASCII decimal field");
    return Integer.parseInt(text);
  }

  /** Emits Java string literal chunks whose inputs already passed alphabet validation. */
  private static String quoted(List<String> chunks) {
    return "      \"" + String.join("\"\n          + \"", chunks) + "\"";
  }

  /** Replaces one required delimited source section without changing its delimiters. */
  private static String replace(String template, String start, String end, String replacement) {
    final int first = unique(template, start) + start.length();
    final int last = template.indexOf(end, first);
    require(last >= first, "Missing source end marker: " + end);
    return template.substring(0, first) + replacement + template.substring(last);
  }

  /** Locates a unique source marker, rejecting ambiguous or outdated templates. */
  private static int unique(String template, String marker) {
    final int first = template.indexOf(marker);
    require(first >= 0 && template.indexOf(marker, first + marker.length()) < 0,
        "Expected one source marker: " + marker);
    return first;
  }

  /** Rejects malformed input before any destination write can take place. */
  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
