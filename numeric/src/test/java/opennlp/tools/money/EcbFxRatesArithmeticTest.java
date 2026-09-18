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
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

import static opennlp.tools.money.EcbFxRatesTestSupport.assertRate;
import static opennlp.tools.money.EcbFxRatesTestSupport.load;

/** Tests lookup date limits, identity conversions, and missing-quote behavior. */
class EcbFxRatesArithmeticTest {

  private static final LocalDate DATE = LocalDate.of(2026, 7, 14);

  /**
   * Checking the age of a quote does not require a date beyond LocalDate.MAX.
   *
   * @param age The age of a quote queried on the latest supported date.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 6, 7, 8, 99})
  void testLatestDateLookup(int age) throws IOException {
    final LocalDate date = LocalDate.MAX.minusDays(age);
    final EcbFxRates rates = load("Date,USD\n" + date + ",1.25\n");
    final Optional<BigDecimal> rate = Assertions.assertDoesNotThrow(
        () -> rates.rate("EUR", "USD", LocalDate.MAX));
    final MoneyAmount source = new MoneyAmount(new Span(2, 7), BigDecimal.TEN, "EUR");
    final Optional<MoneyAmount> converted = Assertions.assertDoesNotThrow(
        () -> rates.convert(source, "USD", LocalDate.MAX));
    if (age <= EcbFxRates.MAX_STALENESS_DAYS) {
      assertRate("1.25", rate);
      Assertions.assertEquals(0, new BigDecimal("12.5")
          .compareTo(converted.orElseThrow().amount()));
      Assertions.assertEquals(source.span(), converted.orElseThrow().span());
    } else {
      Assertions.assertTrue(rate.isEmpty());
      Assertions.assertTrue(converted.isEmpty());
    }
  }

  /**
   * Earliest-date lookups include the configured age limit but exclude future quotes.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testEarliestDateLookup() throws IOException {
    final EcbFxRates rates = load("Date,USD\n" + LocalDate.MIN + ",1.25\n");
    Assertions.assertTrue(rates.rate("EUR", "USD", LocalDate.MIN).isPresent());
    Assertions.assertTrue(rates.rate("EUR", "USD", LocalDate.MIN.plusDays(7)).isPresent());
    Assertions.assertTrue(rates.rate("EUR", "USD", LocalDate.MIN.plusDays(8)).isEmpty());
    final EcbFxRates future = load("Date,USD\n" + LocalDate.MIN.plusDays(1) + ",1.25\n");
    Assertions.assertTrue(future.rate("EUR", "USD", LocalDate.MIN).isEmpty());
  }

  /**
   * Converting to the source currency preserves the original decimal value and scale.
   *
   * @param amount The exact source amount.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {"12345678901234567890.123456789", "-12345678901234567890.123456789",
      "0.00000000000000000000000001", "0.000000", "1E-2147483647"})
  void testIdentityConversionPreservesAmount(String amount) throws IOException {
    final EcbFxRates rates = load("Date,USD\n2026-07-14,1.2345\n");
    final MoneyAmount source = new MoneyAmount(new Span(4, 8), new BigDecimal(amount), "USD");
    Assertions.assertEquals(source,
        Assertions.assertDoesNotThrow(() -> rates.convert(source, "USD", DATE)).orElseThrow());
  }

  /**
   * Identity conversion still requires an available quote on a usable date.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testIdentityRequiresUsableQuote() throws IOException {
    final EcbFxRates rates = load("Date,USD,JPY\n2026-07-14,1.25,N/A\n");
    final MoneyAmount quoted = new MoneyAmount(new Span(0, 1), BigDecimal.ONE, "USD");
    final MoneyAmount missing = new MoneyAmount(new Span(0, 1), BigDecimal.ONE, "JPY");
    Assertions.assertTrue(rates.convert(quoted, "USD", DATE.minusDays(1)).isEmpty());
    Assertions.assertTrue(rates.convert(quoted, "USD", DATE.plusDays(8)).isEmpty());
    Assertions.assertTrue(rates.convert(missing, "JPY", DATE).isEmpty());
    Assertions.assertTrue(rates.rate("JPY", "JPY", DATE).isEmpty());
  }

  /**
   * A missing quote on the selected date is not replaced by an older currency quote.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testNewestAvailableDateIsSelected() throws IOException {
    final EcbFxRates rates = load("Date,USD,JPY\n2026-07-14,1.25,N/A\n"
        + "2026-07-10,1.2,150\n2026-07-17,1.3,160\n");
    assertRate("1.25", rates.rate("EUR", "USD", DATE.plusDays(1)));
    Assertions.assertTrue(rates.rate("EUR", "JPY", DATE.plusDays(1)).isEmpty());
  }

  /**
   * Cross rates and converted amounts agree with a higher-precision decimal calculation.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testGeneratedCrossRates() throws IOException {
    final Random random = new Random(20260905L);
    for (int i = 0; i < 500; i++) {
      final BigDecimal usd = BigDecimal.valueOf(random.nextLong(1, 1_000_000), 3);
      final BigDecimal jpy = BigDecimal.valueOf(random.nextLong(1, 1_000_000), 2);
      final BigDecimal amount = BigDecimal.valueOf(random.nextLong(-100_000_000_000L,
          100_000_000_000L), 6);
      final EcbFxRates rates = load("Date,USD,JPY\n2026-07-14," + usd + "," + jpy + "\n");
      final BigDecimal expectedRate = jpy.divide(usd, MathContext.DECIMAL128)
          .round(MathContext.DECIMAL64);
      final BigDecimal expectedAmount = amount.multiply(jpy).divide(usd, MathContext.DECIMAL128)
          .round(MathContext.DECIMAL64);
      assertRate(expectedRate.toString(), rates.rate("USD", "JPY", DATE));
      final MoneyAmount source = new MoneyAmount(new Span(i, i + 1), amount, "USD");
      final MoneyAmount converted = rates.convert(source, "JPY", DATE).orElseThrow();
      Assertions.assertEquals(0, expectedAmount.compareTo(converted.amount()));
      Assertions.assertEquals(source.span(), converted.span());
      Assertions.assertEquals("JPY", converted.currency());
    }
  }

  /**
   * Cross-currency conversion uses 16 significant digits and half-even rounding.
   *
   * @param amount The input amount.
   * @param expected The rounded amount.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @CsvSource({"12345678901234565, 12345678901234560",
      "12345678901234575, 12345678901234580",
      "-12345678901234565, -12345678901234560",
      "-12345678901234575, -12345678901234580"})
  void testConversionRounding(String amount, String expected) throws IOException {
    final EcbFxRates rates = load("Date,USD\n2026-07-14,1\n");
    final MoneyAmount source = new MoneyAmount(new Span(0, 1), new BigDecimal(amount), "EUR");
    Assertions.assertEquals(0, new BigDecimal(expected)
        .compareTo(rates.convert(source, "USD", DATE).orElseThrow().amount()));
  }

  /**
   * Conversion divides after multiplication to avoid rounding an intermediate cross rate.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testConversionRoundsOnce() throws IOException {
    final EcbFxRates rates = load("Date,USD,JPY\n2026-07-14,3,1\n");
    final MoneyAmount source = new MoneyAmount(new Span(0, 1), new BigDecimal("3"), "USD");
    assertRate("0.3333333333333333", rates.rate("USD", "JPY", DATE));
    Assertions.assertEquals(0, BigDecimal.ONE
        .compareTo(rates.convert(source, "JPY", DATE).orElseThrow().amount()));
  }

  /**
   * Shared-table lookups do not retain a date, amount, or missing result from another call.
   *
   * @throws Exception If loading or a worker fails.
   */
  @Test
  void testConcurrentLookups() throws Exception {
    final EcbFxRates rates = load("Date,USD,JPY\n2026-07-14,1.25,150\n");
    final List<Callable<Void>> tasks = new ArrayList<>();
    for (int i = 1; i <= 200; i++) {
      final int value = i;
      tasks.add(() -> {
        final LocalDate date = DATE.plusDays(value % 9);
        final MoneyAmount source = new MoneyAmount(new Span(value, value + 1),
            BigDecimal.valueOf(value), "USD");
        final Optional<MoneyAmount> result = rates.convert(source, "JPY", date);
        if (value % 9 == 8) {
          Assertions.assertTrue(result.isEmpty());
          Assertions.assertTrue(rates.rate("USD", "JPY", date).isEmpty());
        } else {
          assertRate("120", rates.rate("USD", "JPY", date));
          final MoneyAmount converted = result.orElseThrow();
          Assertions.assertEquals(0, BigDecimal.valueOf(value * 120L).compareTo(converted.amount()));
          Assertions.assertEquals(source.span(), converted.span());
          Assertions.assertEquals("JPY", converted.currency());
        }
        return null;
      });
    }
    try (var executor = Executors.newFixedThreadPool(4)) {
      for (var result : executor.invokeAll(tasks)) {
        result.get();
      }
    }
  }
}
