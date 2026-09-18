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
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;
import opennlp.tools.geo.DocumentRegionAnnotator;
import opennlp.tools.geo.GazetteerEntry;
import opennlp.tools.geo.GeoPoint;
import opennlp.tools.geo.GeoResolution;
import opennlp.tools.geo.GeoTestUtil;
import opennlp.tools.geo.GeocodeAnnotator;
import opennlp.tools.geo.Geocoder;
import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that the winning region picks the symbol table per document, and drives the
 * full chain from location entities to converted money. The rate fixture is
 * project-authored and synthetic.
 */
public class RegionAwareMoneyAnnotatorTest {

  @Test
  void testWinningRegionResolvesTheSymbol() {
    final Document document = new RegionAwareMoneyAnnotator()
        .annotate(GeoTestUtil.withRegionBallot("the deal is worth $5", "AU"));
    assertEquals("AUD",
        document.get(MoneyAnnotator.MONEY).get(0).value().currency());
  }

  @Test
  void testEmptyBallotFallsBackToTheDefaultTable() {
    final Document document = new RegionAwareMoneyAnnotator()
        .annotate(Document.of("costs $5")
            .with(DocumentRegionAnnotator.REGIONS, List.of()));
    assertEquals("USD",
        document.get(MoneyAnnotator.MONEY).get(0).value().currency());
  }

  @Test
  void testEndToEndFromEntitiesToConvertedMoney() throws IOException {
    // entities -> geocode -> region ballot -> region-aware money -> FX conversion
    final Geocoder geocoder = (text, mentions) -> {
      final List<GeoResolution> resolutions = new ArrayList<>();
      for (final Span mention : mentions) {
        resolutions.add(new GeoResolution(mention,
            new GazetteerEntry("test", "sydney", "Sydney", List.of(),
                new GeoPoint(-33.87, 151.21), "AU", List.of(), 5_300_000,
                GazetteerEntry.FEATURE_CLASS_CITY, Map.of()), 0.9));
      }
      return resolutions;
    };
    final EcbFxRates rates = EcbFxRates.load(new ByteArrayInputStream(String.join("\n",
        "Date,USD,AUD,", "2026-07-10,1.2000,1.8000,", "").getBytes(StandardCharsets.UTF_8)));

    final String text = "a Sydney startup raised $3 million this week";
    final DocumentAnnotator entities = new DocumentAnnotator() {

      @Override
      public Document annotate(Document document) {
        final int start = text.indexOf("Sydney");
        return document.with(Layers.ENTITIES, List.of(
            new Annotation<>(new Span(start, start + 6), "location")));
      }

      @Override
      public Set<LayerKey<?>> provides() {
        return Set.of(Layers.ENTITIES);
      }
    };

    final Document document = DocumentAnalyzer.builder()
        .add(entities)
        .add(new GeocodeAnnotator(geocoder))
        .add(new DocumentRegionAnnotator())
        .add(new RegionAwareMoneyAnnotator())
        .add(new MoneyConversionAnnotator(rates, "USD", LocalDate.parse("2026-07-10")))
        .build()
        .analyze(text);

    final Annotation<MoneyAmount> identified =
        document.get(MoneyAnnotator.MONEY).get(0);
    assertEquals("AUD", identified.value().currency());
    assertEquals("$3 million",
        identified.span().getCoveredText(document.text()).toString());

    final Annotation<MoneyAmount> converted =
        document.get(MoneyConversionAnnotator.CONVERTED_MONEY).get(0);
    assertEquals("USD", converted.value().currency());
    // 3,000,000 AUD * (1.2 / 1.8) = 2,000,000 USD
    assertEquals(0, new BigDecimal("2000000").compareTo(converted.value().amount()));
    assertEquals(identified.span(), converted.span());
  }

  /**
   * Verifies that a country with several installed locales resolves through one whose
   * currency symbol is usable: Canada's minority-language locales write the Canadian
   * dollar as {@code CA$}, which is not a single currency sign, while {@code en-CA}
   * writes it {@code $}, so a document speaking from Canada identifies {@code $5} as
   * {@code CAD} rather than falling back to the default table's {@code USD}.
   */
  @Test
  void testCountryWithMinorityLanguageLocalesResolvesTheSymbol() {
    final Document document = new RegionAwareMoneyAnnotator()
        .annotate(GeoTestUtil.withRegionBallot("the grant is $5", "CA"));
    assertEquals("CAD",
        document.get(MoneyAnnotator.MONEY).get(0).value().currency());
  }

  /**
   * Verifies that the installed-locale scan skips candidates whose currency symbol is
   * not a single currency sign, using Guinea as the discriminating case in the JDK's
   * locale data: {@code en-GN} and the lexicographically first Guinean locale
   * ({@code ff-Adlm-GN}, which spells the franc {@code FG}) are both unusable, so only
   * the symbol filter makes the scan pass them over for {@code nqo-GN}, whose
   * single-sign spelling (U+07FF, the N'Ko taman sign) resolves {@code GNF}. The
   * assumptions skip the test if the runtime's locale data changes shape.
   */
  @Test
  void testLocaleScanSkipsUnusableSymbolCandidates() {
    Assumptions.assumeFalse(singleCurrencySign(Locale.of("en", "GN")));
    Assumptions.assumeFalse(singleCurrencySign(Locale.forLanguageTag("ff-Adlm-GN")));
    Assumptions.assumeTrue(singleCurrencySign(Locale.forLanguageTag("nqo-GN")));

    final Document document = new RegionAwareMoneyAnnotator()
        .annotate(GeoTestUtil.withRegionBallot("price 100\u07FF today", "GN"));
    assertEquals("GNF",
        document.get(MoneyAnnotator.MONEY).get(0).value().currency());
  }

  /**
   * Mirrors the annotator's usability rule for the assumption guards above: a locale is
   * usable when its currency is written there as one currency-sign code point.
   *
   * @param locale The locale to test. Must not be {@code null}.
   * @return {@code true} if the locale's currency symbol is a single currency sign.
   */
  private static boolean singleCurrencySign(Locale locale) {
    final Currency currency;
    try {
      currency = Currency.getInstance(locale);
    } catch (IllegalArgumentException e) {
      return false;
    }
    if (currency == null) {
      return false;
    }
    final String symbol = currency.getSymbol(locale);
    return symbol.codePointCount(0, symbol.length()) == 1
        && Character.getType(symbol.codePointAt(0)) == Character.CURRENCY_SYMBOL;
  }

  /**
   * Verifies that the per-country cache serves repeated documents without changing the
   * outcome: two documents from the same country resolve the symbol identically, so the
   * cached extractor is the resolved one rather than a fallback.
   */
  @Test
  void testRepeatedDocumentsFromOneCountryResolveIdentically() {
    final RegionAwareMoneyAnnotator annotator = new RegionAwareMoneyAnnotator();
    final Document first = annotator.annotate(GeoTestUtil.withRegionBallot("the grant is $5", "CA"));
    final Document second = annotator.annotate(GeoTestUtil.withRegionBallot("the rebate is $9", "CA"));
    assertEquals("CAD", first.get(MoneyAnnotator.MONEY).get(0).value().currency());
    assertEquals("CAD", second.get(MoneyAnnotator.MONEY).get(0).value().currency());
  }

  @Test
  void testUnknownCountryFallsBackToTheDefaultTable() {
    final Document document = new RegionAwareMoneyAnnotator()
        .annotate(GeoTestUtil.withRegionBallot("costs $5", "ZZ"));
    assertEquals("USD",
        document.get(MoneyAnnotator.MONEY).get(0).value().currency());
  }

  @Test
  void testNullDocumentThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> new RegionAwareMoneyAnnotator().annotate(null));
  }

  /**
   * Verifies that an absent region layer is rejected as an assembly error: the
   * annotator requires the region ballot, so a document that never passed through the
   * region annotator is refused with an exception naming the layer, rather than
   * silently falling back to the default table.
   */
  @Test
  void testMissingRegionLayerIsRejectedNamingTheLayer() {
    final RegionAwareMoneyAnnotator annotator = new RegionAwareMoneyAnnotator();
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> annotator.annotate(Document.of("costs $5")));
    assertEquals("document lacks the required layer opennlp:regions<RegionVote>",
        e.getMessage());
  }
}
