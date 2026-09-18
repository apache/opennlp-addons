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
import java.util.Set;

import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.temporal.CursorTemporalExtractor;
import opennlp.tools.temporal.DocumentDateAnnotator;
import opennlp.tools.temporal.TemporalAnnotator;
import opennlp.tools.temporal.TemporalExpression;
import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates the numeric extraction pipeline end to end on one realistic document: a
 * dateline elects the document date, two amounts in different currencies are extracted
 * with normalized values, and both are restated in one target currency as of the
 * document date. The rate fixture is project-authored and synthetic.
 */
public class NumericExtractionExampleTest {

  /** The example document: a dateline, a euro amount, and a dollar amount. */
  private static final String TEXT =
      "Berlin, 14 July 2026. The seller asked for \u20AC2.5 million, "
          + "but the buyer paid $2,400,000 after a week of talks.";

  /** A minimal reference-rate table with one row shortly before the document date. */
  private static final String RATES_CSV = String.join("\n",
      "Date,USD,",
      "2026-07-13,1.2000,",
      "") + "\n";

  /**
   * Runs the full pipeline over the example document: temporal mentions, document date
   * election, money extraction, and document-dated conversion into US dollars.
   *
   * @return The fully annotated {@link Document}. Never {@code null}.
   * @throws IOException Thrown if the in-memory rate fixture cannot be read.
   */
  private static Document analyzed() throws IOException {
    final FxRates rates = EcbFxRates.load(
        new ByteArrayInputStream(RATES_CSV.getBytes(StandardCharsets.UTF_8)));
    return DocumentAnalyzer.builder()
        .add(new TemporalAnnotator(new CursorTemporalExtractor()))
        .add(new DocumentDateAnnotator())
        .add(new MoneyAnnotator(new CursorMoneyExtractor()))
        .add(new MoneyConversionAnnotator(rates, "USD"))
        .build()
        .analyze(TEXT);
  }

  /**
   * Locates a fragment that occurs exactly once in the example text and returns its
   * span, so expected offsets stay readable in the assertions below.
   *
   * @param fragment The fragment to locate. Must occur exactly once in {@link #TEXT}.
   * @return The {@link Span} of the fragment in {@link #TEXT}. Never {@code null}.
   * @throws IllegalStateException Thrown if the fragment is absent or ambiguous.
   */
  private static Span spanOf(String fragment) {
    final int start = TEXT.indexOf(fragment);
    if (start < 0) {
      throw new IllegalStateException("fragment not found in the example text: " + fragment);
    }
    if (TEXT.indexOf(fragment, start + 1) >= 0) {
      throw new IllegalStateException("fragment is ambiguous in the example text: " + fragment);
    }
    return new Span(start, start + fragment.length());
  }

  /**
   * Asserts that the pipeline leaves exactly the four expected layers on the document.
   */
  @Test
  void testPipelineProvidesExactlyTheExpectedLayers() throws IOException {
    assertEquals(Set.of(TemporalAnnotator.TEMPORALS, DocumentDateAnnotator.DOCUMENT_DATE,
            MoneyAnnotator.MONEY, MoneyConversionAnnotator.CONVERTED_MONEY),
        analyzed().layers());
  }

  /**
   * Asserts that the dateline is the only temporal mention, with its exact span and its
   * ISO-normalized day value.
   */
  @Test
  void testDatelineIsTheOnlyTemporalMention() throws IOException {
    final List<Annotation<TemporalExpression>> temporals =
        analyzed().get(TemporalAnnotator.TEMPORALS);
    assertEquals(1, temporals.size());
    assertEquals(spanOf("14 July 2026"), temporals.get(0).span());
    assertEquals("2026-07-14", temporals.get(0).value().value());
    assertEquals(TemporalExpression.Granularity.DAY, temporals.get(0).value().granularity());
  }

  /**
   * Asserts that the dateline elects the document date and that the electing mention's
   * span is kept on the annotation.
   */
  @Test
  void testDatelineElectsTheDocumentDate() throws IOException {
    final List<Annotation<LocalDate>> dates =
        analyzed().get(DocumentDateAnnotator.DOCUMENT_DATE);
    assertEquals(1, dates.size());
    assertEquals(LocalDate.parse("2026-07-14"), dates.get(0).value());
    assertEquals(spanOf("14 July 2026"), dates.get(0).span());
  }

  /**
   * Asserts both money mentions: their exact spans, their normalized
   * {@link BigDecimal} amounts with the scale word and the digit grouping applied, and
   * their currencies.
   */
  @Test
  void testBothAmountsAreExtractedAndNormalized() throws IOException {
    final List<Annotation<MoneyAmount>> money = analyzed().get(MoneyAnnotator.MONEY);
    assertEquals(2, money.size());

    assertEquals(spanOf("\u20AC2.5 million"), money.get(0).span());
    assertEquals(0, new BigDecimal("2500000").compareTo(money.get(0).value().amount()));
    assertEquals("EUR", money.get(0).value().currency());

    assertEquals(spanOf("$2,400,000"), money.get(1).span());
    assertEquals(0, new BigDecimal("2400000").compareTo(money.get(1).value().amount()));
    assertEquals("USD", money.get(1).value().currency());
  }

  /**
   * Asserts the document-dated conversion: both mentions are restated in US dollars on
   * their original spans, the euro amount through the fixture rate of 1.2000 dollars
   * per euro and the dollar amount unchanged.
   */
  @Test
  void testBothAmountsAreConvertedAsOfTheDocumentDate() throws IOException {
    final List<Annotation<MoneyAmount>> converted =
        analyzed().get(MoneyConversionAnnotator.CONVERTED_MONEY);
    assertEquals(2, converted.size());

    // 2500000 EUR * 1.2000 USD per EUR = 3000000 USD
    assertEquals(spanOf("\u20AC2.5 million"), converted.get(0).span());
    assertEquals(0, new BigDecimal("3000000").compareTo(converted.get(0).value().amount()));
    assertEquals("USD", converted.get(0).value().currency());

    assertEquals(spanOf("$2,400,000"), converted.get(1).span());
    assertEquals(0, new BigDecimal("2400000").compareTo(converted.get(1).value().amount()));
    assertEquals("USD", converted.get(1).value().currency());

    assertTrue(converted.stream().allMatch(a -> "USD".equals(a.value().currency())));
  }
}
