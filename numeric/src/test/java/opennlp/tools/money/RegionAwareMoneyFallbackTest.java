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

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;
import opennlp.tools.geo.DocumentRegionAnnotator;
import opennlp.tools.geo.GeoTestUtil;
import opennlp.tools.geo.GeocodeAnnotator;
import opennlp.tools.geo.RegionVote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the fallback paths of region-aware money extraction: a pipeline-produced empty
 * ballot and a winning country whose currency symbol is spelled with letters both keep
 * the default symbol table, under which the ambiguous {@code $} sign denotes
 * {@code USD}.
 */
public class RegionAwareMoneyFallbackTest {

  /**
   * Verifies the empty-ballot fallback on a ballot the region annotator itself
   * produced: a document that ran the pipeline but holds no location entity and nothing
   * geocoded gets an empty region layer, so the money annotator uses the default symbol
   * table and identifies {@code $7} as {@code USD}. The region annotator's required
   * layers are present and empty, which is what a location-free document looks like
   * downstream of the geocoding stage.
   */
  @Test
  void testPipelineProducedEmptyBallotUsesTheDefaultTable() {
    final Document input = Document.of("the fee is $7")
        .with(Layers.ENTITIES, List.of())
        .with(GeocodeAnnotator.LOCATIONS, List.of());
    final Document document = new RegionAwareMoneyAnnotator().annotate(
        new DocumentRegionAnnotator().annotate(input));

    final List<Annotation<MoneyAmount>> money = document.get(MoneyAnnotator.MONEY);
    assertEquals(1, money.size());
    assertEquals("USD", money.get(0).value().currency());
    assertEquals(0, new BigDecimal("7").compareTo(money.get(0).value().amount()));
    assertEquals("$7",
        money.get(0).span().getCoveredText(document.text()).toString());
  }

  /**
   * Verifies the letter-symbol fallback: Switzerland wins the ballot, but the Swiss
   * franc is conventionally written {@code CHF}, not a single currency-sign code point,
   * so the symbol table stays at the default and {@code $5} is identified as
   * {@code USD}, not remapped.
   */
  @Test
  void testLetterSymbolCountryKeepsTheDefaultTable() {
    final Document document = new RegionAwareMoneyAnnotator()
        .annotate(GeoTestUtil.withRegionBallot("the invoice totals $5", "CH"));

    final List<Annotation<MoneyAmount>> money = document.get(MoneyAnnotator.MONEY);
    assertEquals(1, money.size());
    assertEquals("USD", money.get(0).value().currency());
  }

  /**
   * Builds a document that carries a two-row tie ballot: Australia and Canada at half
   * a share each, ranked in that order.
   *
   * @param text The document text. Must not be {@code null}.
   * @return A {@link Document} with a tied region ballot. Never {@code null}.
   */
  private static Document withTieBallot(String text) {
    return Document.of(text).with(DocumentRegionAnnotator.REGIONS,
        List.of(Annotation.of(new RegionVote("AU", 0.5)),
            Annotation.of(new RegionVote("CA", 0.5))));
  }

  /**
   * Verifies the margin fallback: with a minimum margin of {@code 0.1}, a tie ballot
   * is not decisive, so the annotator keeps the default table and reads {@code $5} as
   * {@code USD} exactly like the empty-ballot path, instead of silently taking the
   * alphabetically first tied country.
   */
  @Test
  void testTieBelowTheMarginFallsBackToTheDefaultTable() {
    final Document document = new RegionAwareMoneyAnnotator(0.1)
        .annotate(withTieBallot("the deal is worth $5"));
    assertEquals("USD",
        document.get(MoneyAnnotator.MONEY).get(0).value().currency());
  }

  /**
   * Verifies that the default zero margin pins the existing behavior: a tie ballot
   * still elects its top row, so Australia's table reads {@code $5} as {@code AUD}.
   */
  @Test
  void testZeroMarginKeepsTheTopRowOnATie() {
    final Document document = new RegionAwareMoneyAnnotator()
        .annotate(withTieBallot("the deal is worth $5"));
    assertEquals("AUD",
        document.get(MoneyAnnotator.MONEY).get(0).value().currency());
  }

  /**
   * Verifies that an invalid minimum margin is rejected at construction rather than
   * surfacing on the first document.
   */
  @Test
  void testMarginOutsideTheUnitIntervalIsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> new RegionAwareMoneyAnnotator(-0.1));
    assertThrows(IllegalArgumentException.class,
        () -> new RegionAwareMoneyAnnotator(1.1));
    assertThrows(IllegalArgumentException.class,
        () -> new RegionAwareMoneyAnnotator(Double.NaN));
  }
}
