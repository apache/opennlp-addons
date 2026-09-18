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

package opennlp.tools.quantity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.extraction.NumberNotation;
import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the recognized quantity shapes, the percent normalization, and the reject cases
 * that keep bare numbers and money mentions out of the quantity layer.
 */
public class CursorQuantityExtractorTest {

  private final CursorQuantityExtractor extractor = new CursorQuantityExtractor();

  private Quantity single(String text) {
    final List<Quantity> mentions = extractor.extract(text);
    assertEquals(1, mentions.size(), "expected one mention in: " + text);
    return mentions.get(0);
  }

  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "50%; 50; %",
      "3.5 %; 3.5; %",
      "50 percent; 50; %",
      "-2%; -2; %",
      "0%; 0; %",
      "2.5km; 2.5; km",
      "80 kg; 80; kg",
      "1,250 GB; 1250; GB",
      "3 GHz; 3; GHz",
      "26.2 mi; 26.2; mi",
      "500 ms; 500; ms"
  })
  void testRecognizedShapesCoverTheFullMention(String text, String value, String unit) {
    final Quantity mention = single(text);
    assertEquals(new Span(0, text.length()), mention.span(), text);
    assertEquals(0, new BigDecimal(value).compareTo(mention.value()), text);
    assertEquals(unit, mention.unit(), text);
  }

  @Test
  void testMentionInsideSentenceHasExactSpan() {
    final Quantity mention = single("the route is 26.2 mi long");
    assertEquals(new Span(13, 20), mention.span());
    assertEquals("mi", mention.unit());
  }

  @Test
  void testMultipleMentions() {
    final List<Quantity> mentions = extractor.extract("lost 5 kg and 3% body fat");
    assertEquals(2, mentions.size());
    assertEquals("kg", mentions.get(0).unit());
    assertEquals("%", mentions.get(1).unit());
  }

  /**
   * Verifies that the hyphen in a percentage range is not read as a minus sign: both
   * amounts of {@code 5%-10%} are positive, although the code point before the hyphen
   * is a percent sign rather than a digit.
   */
  @Test
  void testHyphenatedPercentRangeYieldsTwoPositiveMentions() {
    final List<Quantity> mentions = extractor.extract("5%-10%");
    assertEquals(2, mentions.size());
    assertEquals(new Span(0, 2), mentions.get(0).span());
    assertEquals(0, new BigDecimal("5").compareTo(mentions.get(0).value()));
    assertEquals(new Span(3, 6), mentions.get(1).span());
    assertEquals(0, new BigDecimal("10").compareTo(mentions.get(1).value()));
  }

  /**
   * Verifies that a quantity grouped in a convention {@link opennlp.tools.extraction.NumberScan}
   * cannot parse is rejected entirely rather than truncated by restarting the scan inside
   * the group: {@code 1,00,000 kg} must not report {@code 0 kg}, and {@code 1,2345 km}
   * must not report {@code 2345 km}. Money already has this guard; quantity must match it.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "1,00,000 kg",          // Indian grouping
      "1,2345 km",            // long trailing group
      "12,34 m",              // short trailing group
      "1.234,5 kg"            // European decimal comma after a dot-read decimal
  })
  void testGroupingTheScannerCannotParseYieldsNoMention(String text) {
    assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Verifies that a comma not followed by a digit is ordinary punctuation behind the
   * mention: {@code 5 kg, then} keeps its mention and the span ends before the comma.
   */
  @Test
  void testTrailingCommaWithoutDigitStillEndsTheMatch() {
    final Quantity mention = single("5 kg, then");
    assertEquals(new Span(0, 4), mention.span());
    assertEquals(0, new BigDecimal("5").compareTo(mention.value()));
    assertEquals("kg", mention.unit());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "the 100 things",      // bare number
      "$3 billion",          // money, not a unit quantity
      "100 USD",             // money, not a unit quantity
      "50kmh",               // not a known unit token
      "5 in the morning",    // the preposition is not a unit
      "50 gb",               // units are case-sensitive
      "50 km2",              // digit after the unit token
      "version 2.5m1"        // letter suffix runs into a digit
  })
  void testRejectedShapes(String text) {
    assertTrue(extractor.extract(text).isEmpty(), text);
  }

  @Test
  void testCustomUnits() {
    final CursorQuantityExtractor custom =
        new CursorQuantityExtractor(Set.of("bbl"));
    assertEquals("bbl", custom.extract("500 bbl").get(0).unit());
    assertTrue(custom.extract("80 kg").isEmpty());
  }

  @Test
  void testCustomUnitValidation() {
    assertThrows(IllegalArgumentException.class,
        () -> new CursorQuantityExtractor((Set<String>) null));
    assertThrows(IllegalArgumentException.class,
        () -> new CursorQuantityExtractor(Set.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new CursorQuantityExtractor(Set.of("toolongunit")));
    assertThrows(IllegalArgumentException.class,
        () -> new CursorQuantityExtractor(Set.of("m2")));
    assertThrows(IllegalArgumentException.class,
        () -> new CursorQuantityExtractor(Set.of("kg/m")));
    assertThrows(IllegalArgumentException.class,
        () -> new CursorQuantityExtractor(Set.of("\u00b0C")));
  }

  /** Verifies that malformed repeated-decimal quantities fail closed in both notations. */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "1.2.3 kg; LATIN_US",
      "1,2,3 kg; LATIN_EU"
  })
  void testRepeatedDecimalSeparatorYieldsNoMention(String text,
      NumberNotation notation) {
    assertTrue(new CursorQuantityExtractor(notation).extract(text).isEmpty(), text);
  }

  /**
   * Verifies the European notation end to end: dots group digits and a comma marks the
   * fraction, so a German measurement is read at its real magnitude.
   */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "1.250 GB; 1250; GB",
      "1.234,5 kg; 1234.5; kg",
      "1.250,75 kWh; 1250.75; kWh",
      "2,5km; 2.5; km",
      "45,5 %; 45.5; %",
      "-2,5 kg; -2.5; kg"
  })
  void testEuropeanNotationShapesCoverTheFullMention(String text, String value, String unit) {
    final CursorQuantityExtractor european =
        new CursorQuantityExtractor(NumberNotation.LATIN_EU);
    final List<Quantity> mentions = european.extract(text);

    assertEquals(1, mentions.size(), "expected one mention in: " + text);
    assertEquals(new Span(0, text.length()), mentions.get(0).span(), text);
    assertEquals(0, new BigDecimal(value).compareTo(mentions.get(0).value()), text);
    assertEquals(unit, mentions.get(0).unit(), text);
  }

  /**
   * Verifies that a number written in one notation yields no mention under the other,
   * including the Indian grouping, which neither notation knows: a quantity at a wrong
   * magnitude is worse than a missing one.
   */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "1,234.56 kg; LATIN_EU",
      "1,234,567.89 kg; LATIN_EU",
      "1,00,000 kg; LATIN_EU",
      "1.234,56 kg; LATIN_US",
      "1.234.567,89 kg; LATIN_US",
      "1.00.000 kg; LATIN_US"
  })
  void testQuantityInTheOtherNotationYieldsNoMention(String text, NumberNotation notation) {
    assertTrue(new CursorQuantityExtractor(notation).extract(text).isEmpty(), text);
  }

  /**
   * Verifies that text valid in both notations is read as the notation says, a factor of a
   * thousand apart.
   */
  @Test
  void testQuantityValidInBothNotationsMeansWhatTheNotationSays() {
    final CursorQuantityExtractor european =
        new CursorQuantityExtractor(NumberNotation.LATIN_EU);
    assertEquals(0, new BigDecimal("1250")
        .compareTo(european.extract("1.250 GB").get(0).value()));
    assertEquals(0, new BigDecimal("1250")
        .compareTo(extractor.extract("1,250 GB").get(0).value()));
    assertEquals(0, new BigDecimal("1.250")
        .compareTo(european.extract("1,250 GB").get(0).value()));
  }

  @Test
  void testNotationAndUnitsCombine() {
    final CursorQuantityExtractor custom =
        new CursorQuantityExtractor(Set.of("bbl"), NumberNotation.LATIN_EU);
    assertEquals(0, new BigDecimal("1500")
        .compareTo(custom.extract("1.500 bbl").get(0).value()));
    assertTrue(custom.extract("80 kg").isEmpty());
  }

  @Test
  void testNotationValidation() {
    assertThrows(IllegalArgumentException.class,
        () -> new CursorQuantityExtractor((NumberNotation) null));
    assertThrows(IllegalArgumentException.class,
        () -> new CursorQuantityExtractor(Set.of("bbl"), null));
  }

  /**
   * Verifies the scan around a supplementary-plane neighbor: the code point before the
   * number is read whole, so an emoji is an ordinary boundary and the mention span
   * starts behind its surrogate pair.
   */
  @Test
  void testSupplementaryPlaneNeighborIsABoundary() {
    // U+1F4E6 (package) takes two chars; the mention span starts behind it
    final Quantity mention = single("\uD83D\uDCE62.5km");
    assertEquals(new Span(2, 7), mention.span());
    assertEquals(0, new BigDecimal("2.5").compareTo(mention.value()));
    assertEquals("km", mention.unit());
  }

  @Test
  void testNullTextThrows() {
    assertThrows(IllegalArgumentException.class, () -> extractor.extract(null));
  }

  @Test
  void testQuantityValidation() {
    assertThrows(IllegalArgumentException.class,
        () -> new Quantity(null, BigDecimal.ONE, "%"));
    assertThrows(IllegalArgumentException.class,
        () -> new Quantity(new Span(0, 1), null, "%"));
    assertThrows(IllegalArgumentException.class,
        () -> new Quantity(new Span(0, 1), BigDecimal.ONE, " "));
  }

  @Test
  void testAnnotatorProvidesTheQuantityLayer() {
    final Document document = DocumentAnalyzer.builder()
        .add(new QuantityAnnotator(extractor))
        .build()
        .analyze("battery lasts 12 hr at 45% load");
    final List<Annotation<Quantity>> quantities =
        document.get(QuantityAnnotator.QUANTITIES);
    assertEquals(2, quantities.size());
    assertEquals("hr", quantities.get(0).value().unit());
    assertEquals("45%",
        quantities.get(1).span().getCoveredText(document.text()).toString());
  }

  @Test
  void testAnnotatorValidation() {
    assertThrows(IllegalArgumentException.class, () -> new QuantityAnnotator(null));
    assertThrows(IllegalArgumentException.class,
        () -> new QuantityAnnotator(extractor).annotate(null));
  }
}
