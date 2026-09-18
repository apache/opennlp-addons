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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import opennlp.tools.util.Span;

import static opennlp.tools.money.EcbFxRatesTestSupport.assertRate;
import static opennlp.tools.money.EcbFxRatesTestSupport.load;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests reference-rate loading and dated lookups with synthetic data.
 */
public class EcbFxRatesTest {

  private static final String FIXTURE = String.join("\n",
      "Date,USD,JPY,GBP,",
      "2026-07-10,1.2000,160.00,0.8500,",
      "2026-07-09,1.1000,155.00,N/A,",
      "2026-06-01,1.0000,150.00,0.9000,",
      "") + "\n";

  /**
   * Loads the shared table.
   *
   * @return The synthetic reference table used by these tests.
   * @throws IOException If reading fails.
   */
  private EcbFxRates rates() throws IOException {
    return load(FIXTURE);
  }

  /**
   * Euro conversions use the quoted rate or its reciprocal.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testEuroBaseRateOnAReferenceDay() throws IOException {
    assertRate("1.2000", rates().rate("EUR", "USD", LocalDate.parse("2026-07-10")));
    assertRate("0.8333333333333333",
        rates().rate("USD", "EUR", LocalDate.parse("2026-07-10")));
  }

  /**
   * Cross-currency lookup divides the target quote by the source quote.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testCrossRateGoesThroughTheEuroBase() throws IOException {
    // JPY per USD = 160.00 / 1.2000
    assertRate("133.3333333333333",
        rates().rate("USD", "JPY", LocalDate.parse("2026-07-10")));
  }

  /**
   * Weekend lookups use the most recent reference date.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testWeekendFallsBackToThePreviousReferenceDay() throws IOException {
    assertRate("1.2000", rates().rate("EUR", "USD", LocalDate.parse("2026-07-12")));
  }

  /**
   * Dates outside the permitted age interval have no result.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testStaleRatesAreAbsentBeyondTheLimit() throws IOException {
    assertTrue(rates().rate("EUR", "USD", LocalDate.parse("2026-07-01")).isEmpty());
    assertTrue(rates().rate("EUR", "USD", LocalDate.parse("2026-05-01")).isEmpty());
  }

  /**
   * The maximum reference age is inclusive.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testStalenessLimitIsInclusive() throws IOException {
    assertRate("1.2000", rates().rate("EUR", "USD", LocalDate.parse("2026-07-17")));
    assertTrue(rates().rate("EUR", "USD", LocalDate.parse("2026-07-18")).isEmpty());
  }

  /**
   * Unquoted currencies have no result.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testNotAvailableCellsAndUnknownCurrenciesAreAbsent() throws IOException {
    assertTrue(rates().rate("EUR", "GBP", LocalDate.parse("2026-07-09")).isEmpty());
    assertTrue(rates().rate("EUR", "CHF", LocalDate.parse("2026-07-10")).isEmpty());
  }

  /**
   * Available same-currency rates equal one.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testIdentityRate() throws IOException {
    assertRate("1", rates().rate("EUR", "EUR", LocalDate.parse("2026-07-10")));
    assertRate("1", rates().rate("USD", "USD", LocalDate.parse("2026-07-10")));
  }

  /**
   * Conversion preserves the input text span.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testConvertKeepsTheSpan() throws IOException {
    final MoneyAmount mention =
        new MoneyAmount(new Span(3, 7), new BigDecimal("10"), "EUR");
    final Optional<MoneyAmount> converted =
        rates().convert(mention, "USD", LocalDate.parse("2026-07-10"));
    assertTrue(converted.isPresent());
    assertEquals(new Span(3, 7), converted.get().span());
    assertEquals(0, new BigDecimal("12").compareTo(converted.get().amount()));
    assertEquals("USD", converted.get().currency());
  }

  /** Missing input, headers, dates, and records are rejected. */
  @Test
  void testMalformedContentFailsLoud() {
    assertThrows(IllegalArgumentException.class, () -> load("no header here"));
    assertThrows(IllegalArgumentException.class, () -> load("Date,USD,\nyesterday,1.1,\n"));
    assertThrows(IllegalArgumentException.class, () -> load("Date,USD,\n"));
    assertThrows(IllegalArgumentException.class, () -> EcbFxRates.load((InputStream) null));
  }

  /**
   * An invalid rate error identifies the currency and input record.
   */
  @Test
  void testMalformedRateCellFailsLoudWithRowContext() {
    final String csv = "Date,USD,JPY,\n2026-07-10,1.08x,160.00,\n";
    final IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> load(csv));
    assertEquals("not a reference history rate for USD: 1.08x in row: "
        + "2026-07-10,1.08x,160.00,", e.getMessage());
  }

  /**
   * Lookup and conversion reject invalid arguments.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testArgumentValidation() throws IOException {
    final EcbFxRates rates = rates();
    final LocalDate date = LocalDate.parse("2026-07-10");
    assertThrows(IllegalArgumentException.class, () -> rates.rate(" ", "USD", date));
    assertThrows(IllegalArgumentException.class, () -> rates.rate("EUR", null, date));
    assertThrows(IllegalArgumentException.class, () -> rates.rate("EUR", "USD", null));
    assertThrows(IllegalArgumentException.class, () -> rates.convert(null, "USD", date));
  }
}
