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

package opennlp.tools.numeric;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.money.CursorMoneyExtractor;
import opennlp.tools.money.MoneyAmount;
import opennlp.tools.money.MoneyAnnotator;
import opennlp.tools.quantity.Quantity;
import opennlp.tools.quantity.QuantityAnnotator;
import opennlp.tools.temporal.DocumentDateAnnotator;
import opennlp.tools.temporal.TemporalAnnotator;
import opennlp.tools.temporal.TemporalExpression;

/** Tests numeric pipeline composition, notation and reference dates. */
public class NumericPacksTest {

  @Test
  void testMoneyPackProvidesOnlyTheMoneyLayer() {
    final Document document = NumericPacks.money().analyze("the buyer paid $2,400,000");

    Assertions.assertEquals(Set.of(MoneyAnnotator.MONEY), document.layers());
    final List<Annotation<MoneyAmount>> money = document.get(MoneyAnnotator.MONEY);
    Assertions.assertEquals(1, money.size());
    Assertions.assertEquals(0,
        new BigDecimal("2400000").compareTo(money.get(0).value().amount()));
    Assertions.assertEquals("USD", money.get(0).value().currency());
  }

  @Test
  void testQuantityPackProvidesOnlyTheQuantityLayer() {
    final Document document = NumericPacks.quantity().analyze("battery lasts 12 hr at 45% load");

    Assertions.assertEquals(Set.of(QuantityAnnotator.QUANTITIES), document.layers());
    final List<Annotation<Quantity>> quantities = document.get(QuantityAnnotator.QUANTITIES);
    Assertions.assertEquals(2, quantities.size());
    Assertions.assertEquals("hr", quantities.get(0).value().unit());
    Assertions.assertEquals("%", quantities.get(1).value().unit());
  }

  /** The absolute date supplies the reference for relative-date resolution. */
  @Test
  void testTemporalPackElectsTheDateAndResolvesRelatives() {
    final Document document =
        NumericPacks.temporal().analyze("Berlin, 14 July 2026. The buyer paid yesterday.");

    Assertions.assertEquals(
        Set.of(TemporalAnnotator.TEMPORALS, DocumentDateAnnotator.DOCUMENT_DATE),
        document.layers());
    Assertions.assertEquals(LocalDate.of(2026, 7, 14),
        document.get(DocumentDateAnnotator.DOCUMENT_DATE).get(0).value());
    final List<Annotation<TemporalExpression>> temporals =
        document.get(TemporalAnnotator.TEMPORALS);
    Assertions.assertEquals(2, temporals.size());
    Assertions.assertEquals("2026-07-13", temporals.get(1).value().value());
  }

  /** A configured reference resolves relative dates without adding an absolute mention. */
  @Test
  void testTemporalPackWithAFixedReferenceResolvesWithoutADateline() {
    final Document document =
        NumericPacks.temporal(LocalDate.of(2026, 7, 14)).analyze("it shipped yesterday");

    final List<Annotation<TemporalExpression>> temporals =
        document.get(TemporalAnnotator.TEMPORALS);
    Assertions.assertEquals(1, temporals.size());
    Assertions.assertEquals("2026-07-13", temporals.get(0).value().value());
    Assertions.assertTrue(document.get(DocumentDateAnnotator.DOCUMENT_DATE).isEmpty());
  }

  @Test
  void testFullPipelineProvidesEveryNumericLayer() {
    final Document document = NumericPacks.fullPipeline().analyze(
        "Chicago, 14 July 2026. The buyer paid $2,400,000 yesterday for 1,250 GB of storage.");

    Assertions.assertEquals(Set.of(TemporalAnnotator.TEMPORALS,
        DocumentDateAnnotator.DOCUMENT_DATE, MoneyAnnotator.MONEY,
        QuantityAnnotator.QUANTITIES), document.layers());
    Assertions.assertEquals(LocalDate.of(2026, 7, 14),
        document.get(DocumentDateAnnotator.DOCUMENT_DATE).get(0).value());
    Assertions.assertEquals("2026-07-13",
        document.get(TemporalAnnotator.TEMPORALS).get(1).value().value());
    Assertions.assertEquals(0, new BigDecimal("2400000")
        .compareTo(document.get(MoneyAnnotator.MONEY).get(0).value().amount()));
    Assertions.assertEquals("GB",
        document.get(QuantityAnnotator.QUANTITIES).get(0).value().unit());
  }

  /** The regional pipeline accepts German notation; the default pipeline rejects it. */
  @Test
  void testRegionalFullPipelineReadsTheRegionsNotation() {
    final String text = "Berlin, 14 July 2026. Der Kaufer zahlte 2.400.000 EUR "
        + "fur 1.250,5 GB Speicher.";

    final Document german = NumericPacks.fullPipeline(Locale.GERMANY).analyze(text);
    Assertions.assertEquals(0, new BigDecimal("2400000")
        .compareTo(german.get(MoneyAnnotator.MONEY).get(0).value().amount()));
    Assertions.assertEquals(0, new BigDecimal("1250.5")
        .compareTo(german.get(QuantityAnnotator.QUANTITIES).get(0).value().value()));

    final Document unitedStates = NumericPacks.fullPipeline().analyze(text);
    Assertions.assertTrue(unitedStates.get(MoneyAnnotator.MONEY).isEmpty());
    Assertions.assertTrue(unitedStates.get(QuantityAnnotator.QUANTITIES).isEmpty());
  }

  /** The Australian locale assigns AUD to the dollar sign. */
  @Test
  void testRegionalMoneyPackResolvesTheAmbiguousSymbol() {
    final Document document =
        NumericPacks.money(Locale.of("en", "AU")).analyze("the tender came to $1,234.56");

    final MoneyAmount amount = document.get(MoneyAnnotator.MONEY).get(0).value();
    Assertions.assertEquals("AUD", amount.currency());
    Assertions.assertEquals(0, new BigDecimal("1234.56").compareTo(amount.amount()));
  }

  @Test
  void testRegionalQuantityPackReadsTheRegionsNotation() {
    final Document document = NumericPacks.quantity(Locale.FRANCE).analyze("il pese 12,5 kg");

    Assertions.assertEquals(0, new BigDecimal("12.5")
        .compareTo(document.get(QuantityAnnotator.QUANTITIES).get(0).value().value()));
  }

  /** The factory list provides the required layers in execution order. */
  @Test
  void testAnnotatorListIsTheFullPipelineInOrder() {
    final List<DocumentAnnotator> annotators = NumericPacks.annotators();

    Assertions.assertEquals(4, annotators.size());
    Assertions.assertEquals(Set.of(TemporalAnnotator.TEMPORALS), annotators.get(0).provides());
    Assertions.assertEquals(Set.of(DocumentDateAnnotator.DOCUMENT_DATE),
        annotators.get(1).provides());
    Assertions.assertEquals(Set.of(MoneyAnnotator.MONEY), annotators.get(2).provides());
    Assertions.assertEquals(Set.of(QuantityAnnotator.QUANTITIES), annotators.get(3).provides());

    final DocumentAnalyzer.Builder builder = DocumentAnalyzer.builder();
    annotators.forEach(builder::add);
    Assertions.assertEquals(4, builder.build()
        .analyze("Chicago, 14 July 2026. It cost $5 yesterday.").layers().size());
  }

  /** Duplicate output layers are rejected when building the analyzer. */
  @Test
  void testAnnotatorListRejectsDuplicateProvider() {
    final List<DocumentAnnotator> annotators = NumericPacks.annotators();

    annotators.add(new MoneyAnnotator(new CursorMoneyExtractor()));

    Assertions.assertEquals(5, annotators.size());
    final DocumentAnalyzer.Builder builder = DocumentAnalyzer.builder();
    annotators.forEach(builder::add);
    Assertions.assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  void testInvalidArguments() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> NumericPacks.money(null));
    Assertions.assertThrows(IllegalArgumentException.class, () -> NumericPacks.quantity(null));
    Assertions.assertThrows(IllegalArgumentException.class, () -> NumericPacks.temporal(null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> NumericPacks.fullPipeline(null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> NumericPacks.annotators(null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> NumericPacks.money(Locale.of("en")));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> NumericPacks.annotators(Locale.of("en")));
  }
}
