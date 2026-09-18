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

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Tests audit display escaping without changing stored types or tokens. */
class PiiAuditOutputTest {

  private static final HmacTokenizer TOKENIZER = new HmacTokenizer(new byte[32]);

  private enum EntryPoint {
    LIST, DOCUMENT, COUNTS_ONLY
  }

  private enum Mutation {
    COUNTS_CLEAR, COUNT_ENTRY_VALUE, DISTINCT_CLEAR, DISTINCT_ENTRY_VALUE, TYPES_CLEAR,
    SAMPLES_CLEAR, SAMPLE_ITERATOR_REMOVE
  }

  /**
   * Provides controls, Unicode formatting characters and literal backslashes.
   *
   * @return The code point, its position in the type and the report entry point.
   */
  private static Stream<Arguments> escapedTypes() {
    final List<Integer> codePoints = new ArrayList<>();
    for (int cp = 0; cp <= 0x1f; cp++) {
      codePoints.add(cp);
    }
    for (int cp = 0x7f; cp <= 0x9f; cp++) {
      codePoints.add(cp);
    }
    codePoints.addAll(List.of(0x2c, 0x3a, 0x5b, 0x5c, 0x5d, 0xad, 0x61c,
        0x200b, 0x200c, 0x200d, 0x200e, 0x200f,
        0x2028, 0x2029, 0x202a, 0x202b, 0x202c, 0x202d, 0x202e, 0x2060, 0x2066, 0x2067,
        0x2068, 0x2069, 0xfeff, 0x1bca0, 0x1d173, 0xe0001, 0xe0020, 0xe007f));
    final List<Arguments> cases = new ArrayList<>();
    for (final int cp : codePoints) {
      for (int position = 0; position < 3; position++) {
        for (final EntryPoint entry : EntryPoint.values()) {
          cases.add(Arguments.of(cp, position, entry));
        }
      }
    }
    return cases.stream();
  }

  /**
   * Checks exact display output and unmodified accessor values.
   *
   * @param codePoint The character to escape.
   * @param position Its insertion offset in an ASCII type name.
   * @param entry The report entry point.
   */
  @ParameterizedTest(name = "code point {0}, position={1}, via={2}")
  @MethodSource("escapedTypes")
  void testEscapesTypesAndSamplePrefixes(int codePoint, int position, EntryPoint entry) {
    final String inserted = new String(Character.toChars(codePoint));
    final String type = "XY".substring(0, position) + inserted + "XY".substring(position);
    final String display = "XY".substring(0, position) + escaped(codePoint) + "XY".substring(position);
    final List<PiiMention> mentions = mentions(type);
    final PiiAuditReport report = switch (entry) {
      case LIST -> PiiAuditReport.of(mentions, TOKENIZER);
      case DOCUMENT -> PiiAuditReport.of(Document.of("a b a").with(PiiAnnotator.PII,
          mentions.stream().map(mention -> new Annotation<>(mention.span(), mention)).toList()), TOKENIZER);
      case COUNTS_ONLY -> PiiAuditReport.of(mentions, TOKENIZER, 0);
    };
    final String first = TOKENIZER.token(mentions.get(0));
    final String second = TOKENIZER.token(mentions.get(1));
    final String samples = entry == EntryPoint.COUNTS_ONLY ? ""
        : " [" + display + first.substring(type.length()) + ", "
            + display + second.substring(type.length()) + ']';

    Assertions.assertEquals(display + ": 3 mentions, 2 distinct" + samples, report.toString());
    Assertions.assertEquals(1, report.toString().lines().count());
    Assertions.assertEquals(Map.of(type, 3), report.counts());
    Assertions.assertEquals(Map.of(type, 2), report.distinctCounts());
    Assertions.assertEquals(List.of(type), List.copyOf(report.types()));
    Assertions.assertEquals(entry == EntryPoint.COUNTS_ONLY ? List.of() : List.of(first, second),
        report.samples(type));
  }

  /**
   * Keeps printable Unicode, punctuation and valid surrogate pairs readable.
   *
   * @param type The printable custom type.
   */
  @ParameterizedTest
  @ValueSource(strings = {"email", "ID-2", "résumé", "Ελλάδα", "東京", "اسم", "emoji😀",
      "𝕒", "a\u0301", "x\ufe0fy", "custom(){}<>\"'"})
  void testPrintableTypesKeepTheirDisplay(String type) {
    final List<PiiMention> mentions = mentions(type);
    final List<String> tokens = List.of(TOKENIZER.token(mentions.get(0)), TOKENIZER.token(mentions.get(1)));
    final PiiAuditReport report = PiiAuditReport.of(mentions, TOKENIZER);

    Assertions.assertEquals(type + ": 3 mentions, 2 distinct " + tokens, report.toString());
    Assertions.assertEquals(tokens, report.samples(type));
  }

  /**
   * Escapes malformed type text when a report does not create token samples.
   *
   * @param unit The unpaired UTF-16 code unit.
   */
  @ParameterizedTest
  @ValueSource(ints = {0xd800, 0xdbff, 0xdc00, 0xdfff})
  void testCountOnlyReportEscapesUnpairedSurrogates(int unit) {
    final String type = "X" + (char) unit + "Y";
    final PiiAuditReport report = PiiAuditReport.of(mentions(type), TOKENIZER, 0);

    Assertions.assertEquals("X" + escaped(unit) + "Y: 3 mentions, 2 distinct", report.toString());
    Assertions.assertEquals(Map.of(type, 3), report.counts());
  }

  /** Checks that literal escape text and an actual line feed remain distinguishable. */
  @Test
  void testLiteralEscapeIsNotRenderedAsAControl() {
    final String escape = escaped('\n');
    final List<PiiMention> mentions = List.of(
        new PiiMention(new Span(0, 1), "X\nY", "a"),
        new PiiMention(new Span(2, 3), "X" + escape + "Y", "b"));
    final PiiAuditReport report = PiiAuditReport.of(mentions, TOKENIZER, 0);

    Assertions.assertEquals("X" + escape + "Y: 1 mentions, 1 distinct" + System.lineSeparator()
        + "X\\" + escape + "Y: 1 mentions, 1 distinct", report.toString());
    Assertions.assertEquals(2, report.toString().lines().count());
  }

  /** Checks that a custom type cannot insert report fields or a sample list. */
  @Test
  void testReportSyntaxInTypeIsEscaped() {
    final String type = "email: 0 mentions, 0 distinct [sample]";
    final String display = "email" + escaped(':') + " 0 mentions" + escaped(',')
        + " 0 distinct " + escaped('[') + "sample" + escaped(']');
    final PiiAuditReport report = PiiAuditReport.of(mentions(type), TOKENIZER, 0);

    Assertions.assertEquals(display + ": 3 mentions, 2 distinct", report.toString());
    Assertions.assertEquals(Map.of(type, 3), report.counts());
  }

  /**
   * Checks that report views remain immutable after rendering.
   *
   * @param mutation The attempted change through a report accessor.
   */
  @ParameterizedTest
  @EnumSource(Mutation.class)
  void testRenderedReportViewsAreImmutable(Mutation mutation) {
    final PiiAuditReport report = PiiAuditReport.of(mentions("email"), TOKENIZER);
    final String rendered = report.toString();
    Assertions.assertThrows(UnsupportedOperationException.class, () -> {
      switch (mutation) {
        case COUNTS_CLEAR -> report.counts().clear();
        case COUNT_ENTRY_VALUE -> report.counts().entrySet().iterator().next().setValue(0);
        case DISTINCT_CLEAR -> report.distinctCounts().clear();
        case DISTINCT_ENTRY_VALUE -> report.distinctCounts().entrySet().iterator().next().setValue(0);
        case TYPES_CLEAR -> report.types().clear();
        case SAMPLES_CLEAR -> report.samples("email").clear();
        case SAMPLE_ITERATOR_REMOVE -> {
          final var iterator = report.samples("email").iterator();
          iterator.next();
          iterator.remove();
        }
        default -> throw new AssertionError(mutation);
      }
    });
    Assertions.assertEquals(rendered, report.toString());
  }

  /** Checks that a report does not retain the mutable input list. */
  @Test
  void testReportOwnsItsData() {
    final List<PiiMention> input = new ArrayList<>(mentions("email"));
    final PiiAuditReport report = PiiAuditReport.of(input, TOKENIZER);
    final String rendered = report.toString();
    input.clear();

    Assertions.assertEquals(3, report.total());
    Assertions.assertEquals(Map.of("email", 3), report.counts());
    Assertions.assertEquals(Map.of("email", 2), report.distinctCounts());
    Assertions.assertEquals(2, report.samples("email").size());
    Assertions.assertEquals(rendered, report.toString());
  }

  /** Checks the manual's count-only report for a type containing a line feed. */
  @Test
  void testManualCustomTypeReport() {
    final List<PiiMention> customMentions = List.of(
        new PiiMention(new Span(0, 1), "custom\nid", "a"));
    final PiiAuditReport customReport = PiiAuditReport.of(customMentions, TOKENIZER, 0);

    Assertions.assertEquals("custom" + escaped('\n') + "id: 1 mentions, 1 distinct",
        customReport.toString());
    Assertions.assertEquals(List.of("custom\nid"), List.copyOf(customReport.types()));
  }

  /**
   * Provides three mentions with two distinct normalized values.
   *
   * @param type The custom type.
   * @return Original input mentions in source order.
   */
  private List<PiiMention> mentions(String type) {
    return List.of(new PiiMention(new Span(0, 1), type, "a"),
        new PiiMention(new Span(2, 3), type, "b"),
        new PiiMention(new Span(4, 5), type, "a"));
  }

  /**
   * Produces the expected escape using JDK hexadecimal formatting.
   *
   * @param codePoint The code point to render.
   * @return A doubled backslash or UTF-16 hexadecimal escapes.
   */
  private String escaped(int codePoint) {
    if (codePoint == '\\') {
      return "\\\\";
    }
    final StringBuilder out = new StringBuilder();
    for (final char unit : Character.toChars(codePoint)) {
      out.append("\\u").append(HexFormat.of().toHexDigits(unit));
    }
    return out.toString();
  }
}
