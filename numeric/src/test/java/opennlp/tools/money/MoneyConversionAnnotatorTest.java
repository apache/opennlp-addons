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

package opennlp.tools.money;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.temporal.CursorTemporalExtractor;
import opennlp.tools.temporal.DocumentDateAnnotator;
import opennlp.tools.temporal.TemporalAnnotator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the location-aware end of the money story: a region-resolved symbol table
 * identifies the right currency, and the conversion annotator restates the money layer
 * in one target currency as of a date. The rate fixture is project-authored and
 * synthetic.
 */
public class MoneyConversionAnnotatorTest {

  private static final String FIXTURE = String.join("\n",
      "Date,USD,AUD,",
      "2026-07-10,1.2000,1.8000,",
      "") + "\n";

  private static final Locale AUSTRALIA = Locale.of("en", "AU");

  private static final LocalDate REFERENCE_DATE = LocalDate.parse("2026-07-10");

  private static EcbFxRates rates() throws IOException {
    return EcbFxRates.load(
        new ByteArrayInputStream(FIXTURE.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void testRegionalSymbolsFeedConversion() throws IOException {
    // an Australian document: $ means AUD, then everything is restated in USD
    final Document document = DocumentAnalyzer.builder()
        .add(new MoneyAnnotator(CursorMoneyExtractor.forRegion(AUSTRALIA)))
        .add(new MoneyConversionAnnotator(rates(), "USD", REFERENCE_DATE))
        .build()
        .analyze("the house sold for $900,000 last week");

    final List<Annotation<MoneyAmount>> money = document.get(MoneyAnnotator.MONEY);
    assertEquals(1, money.size());
    assertEquals("AUD", money.get(0).value().currency());

    final List<Annotation<MoneyAmount>> converted =
        document.get(MoneyConversionAnnotator.CONVERTED_MONEY);
    assertEquals(1, converted.size());
    final MoneyAmount usd = converted.get(0).value();
    assertEquals("USD", usd.currency());
    // 900000 AUD * (1.2 / 1.8) = 600000 USD
    assertEquals(0, new BigDecimal("600000").compareTo(usd.amount()));
    assertEquals(money.get(0).span(), converted.get(0).span());
  }

  @Test
  void testUnconvertibleMentionsAreOmitted() throws IOException {
    final Document document = DocumentAnalyzer.builder()
        .add(new MoneyAnnotator(new CursorMoneyExtractor()))
        .add(new MoneyConversionAnnotator(rates(), "USD", REFERENCE_DATE))
        .build()
        .analyze("we spent $10 in transit and CHF 20 at the airport");

    assertEquals(2, document.get(MoneyAnnotator.MONEY).size());
    final List<Annotation<MoneyAmount>> converted =
        document.get(MoneyConversionAnnotator.CONVERTED_MONEY);
    // no CHF column in the fixture, so only the USD mention converts
    assertEquals(1, converted.size());
    assertEquals(0, BigDecimal.TEN.compareTo(converted.get(0).value().amount()));
  }

  @Test
  void testDocumentDateAnchorsTheConversion() throws IOException {
    final Document document = DocumentAnalyzer.builder()
        .add(new TemporalAnnotator(new CursorTemporalExtractor()))
        .add(new DocumentDateAnnotator())
        .add(new MoneyAnnotator(CursorMoneyExtractor.forRegion(AUSTRALIA)))
        .add(new MoneyConversionAnnotator(rates(), "USD"))
        .build()
        .analyze("Sydney, 2026-07-10. The house sold for $900,000 at auction.");

    final List<Annotation<MoneyAmount>> converted =
        document.get(MoneyConversionAnnotator.CONVERTED_MONEY);
    assertEquals(1, converted.size());
    assertEquals("USD", converted.get(0).value().currency());
    assertEquals(0, new BigDecimal("600000").compareTo(converted.get(0).value().amount()));
  }

  @Test
  void testNoDocumentDateConvertsNothing() throws IOException {
    final Document document = DocumentAnalyzer.builder()
        .add(new TemporalAnnotator(new CursorTemporalExtractor()))
        .add(new DocumentDateAnnotator())
        .add(new MoneyAnnotator(new CursorMoneyExtractor()))
        .add(new MoneyConversionAnnotator(rates(), "USD"))
        .build()
        .analyze("the undated draft mentions $10");

    assertEquals(1, document.get(MoneyAnnotator.MONEY).size());
    assertEquals(0, document.get(MoneyConversionAnnotator.CONVERTED_MONEY).size());
  }

  @Test
  void testRegionWithoutASingleCurrencySignKeepsTheDefaults() {
    // the South African rand is written with a letter, which the table refuses
    final CursorMoneyExtractor extractor =
        CursorMoneyExtractor.forRegion(Locale.of("en", "ZA"));
    assertEquals("USD", extractor.extract("$5").get(0).currency());
  }

  /**
   * Verifies the region resolution for another ambiguous dollar region: in a Canadian
   * document, the dollar sign denotes the Canadian dollar.
   */
  @Test
  void testRegionResolvesTheDollarForCanada() {
    final CursorMoneyExtractor extractor =
        CursorMoneyExtractor.forRegion(Locale.of("en", "CA"));
    assertEquals("CAD", extractor.extract("$5").get(0).currency());
  }

  @Test
  void testForRegionValidation() {
    assertThrows(IllegalArgumentException.class,
        () -> CursorMoneyExtractor.forRegion(null));
    assertThrows(IllegalArgumentException.class,
        () -> CursorMoneyExtractor.forRegion(Locale.of("en")));
  }

  @Test
  void testAnnotatorValidation() throws IOException {
    final EcbFxRates rates = rates();
    assertThrows(IllegalArgumentException.class,
        () -> new MoneyConversionAnnotator(null, "USD", REFERENCE_DATE));
    assertThrows(IllegalArgumentException.class,
        () -> new MoneyConversionAnnotator(rates, " ", REFERENCE_DATE));
    assertThrows(IllegalArgumentException.class,
        () -> new MoneyConversionAnnotator(rates, "USD", null));
    assertThrows(IllegalArgumentException.class,
        () -> new MoneyConversionAnnotator(rates, "USD", REFERENCE_DATE).annotate(null));
  }

  /**
   * Verifies that an absent required layer fails loud, naming the missing layer: the
   * money layer in fixed-date mode, and the document date layer in document-dated mode.
   */
  @Test
  void testMissingRequiredLayersFailLoud() throws IOException {
    final EcbFxRates rates = rates();
    final Document text = Document.of("we spent $10 in transit");

    final IllegalArgumentException missingMoney = assertThrows(
        IllegalArgumentException.class,
        () -> new MoneyConversionAnnotator(rates, "USD", REFERENCE_DATE).annotate(text));
    assertEquals("document lacks the required layer opennlp:money-amounts<MoneyAmount>",
        missingMoney.getMessage());

    final Document withMoney = new MoneyAnnotator(new CursorMoneyExtractor()).annotate(text);
    final IllegalArgumentException missingDate = assertThrows(
        IllegalArgumentException.class,
        () -> new MoneyConversionAnnotator(rates, "USD").annotate(withMoney));
    assertEquals("document lacks the required layer opennlp:document.date<LocalDate>",
        missingDate.getMessage());
  }
}
