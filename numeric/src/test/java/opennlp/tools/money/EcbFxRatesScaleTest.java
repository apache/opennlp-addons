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
import java.math.BigInteger;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

import static opennlp.tools.money.EcbFxRatesTestSupport.assertRate;
import static opennlp.tools.money.EcbFxRatesTestSupport.load;

/** Tests decimal-scale limits without allocating powers of ten for the exponent. */
class EcbFxRatesScaleTest {

  private static final LocalDate DATE = LocalDate.of(2026, 7, 14);
  private static final Span SPAN = new Span(2, 8, "amount");
  private static final String USD = "USD";
  private static final String JPY = "JPY";
  private static final String EQUAL_QUOTE = "1.2345";

  /**
   * Supplies conversions with small coefficients and boundary-scale results.
   *
   * @return Source quote, target quote, amount coefficient/scale, expected coefficient/scale.
   */
  private static Stream<Arguments> conversions() {
    return Stream.of(
        Arguments.of(EQUAL_QUOTE, EQUAL_QUOTE, "1", Integer.MAX_VALUE, "1", Integer.MAX_VALUE),
        Arguments.of(EQUAL_QUOTE, EQUAL_QUOTE, "-1", Integer.MAX_VALUE, "-1", Integer.MAX_VALUE),
        Arguments.of(EQUAL_QUOTE, EQUAL_QUOTE, "1", Integer.MAX_VALUE - 7,
            "1", Integer.MAX_VALUE - 7),
        Arguments.of(EQUAL_QUOTE, EQUAL_QUOTE, "1", Integer.MIN_VALUE, "1", Integer.MIN_VALUE),
        Arguments.of("3", "1", "3", Integer.MAX_VALUE, "1", Integer.MAX_VALUE),
        Arguments.of("1", "10", "1", Integer.MIN_VALUE, "10", Integer.MIN_VALUE),
        Arguments.of("1", "1E+1", "1", Integer.MIN_VALUE, "10", Integer.MIN_VALUE),
        Arguments.of("1", "1E+15", "1", Integer.MIN_VALUE,
            "1000000000000000", Integer.MIN_VALUE),
        Arguments.of("1E+1", "1", "10", Integer.MAX_VALUE, "1", Integer.MAX_VALUE),
        Arguments.of("1E-2147483647", "1", "1", Integer.MAX_VALUE, "1", 0),
        Arguments.of("1", "1E-2147483647", "1", Integer.MIN_VALUE, "1", -1),
        Arguments.of("1E-2147483647", "2E-2147483647", "1", Integer.MAX_VALUE,
            "2", Integer.MAX_VALUE),
        Arguments.of("1E+2147483647", "2E+2147483647", "1", Integer.MIN_VALUE,
            "2", Integer.MIN_VALUE),
        Arguments.of(EQUAL_QUOTE, EQUAL_QUOTE, "12345678901234565", Integer.MAX_VALUE,
            "1234567890123456", Integer.MAX_VALUE - 1),
        Arguments.of(EQUAL_QUOTE, EQUAL_QUOTE, "12345678901234575", Integer.MAX_VALUE,
            "1234567890123458", Integer.MAX_VALUE - 1),
        Arguments.of(EQUAL_QUOTE, EQUAL_QUOTE, "-12345678901234565", Integer.MAX_VALUE,
            "-1234567890123456", Integer.MAX_VALUE - 1),
        Arguments.of(EQUAL_QUOTE, EQUAL_QUOTE, "-12345678901234575", Integer.MAX_VALUE,
            "-1234567890123458", Integer.MAX_VALUE - 1));
  }

  /**
   * Representable results do not fail because an intermediate scale is out of range.
   *
   * @param from The source quote.
   * @param to The target quote.
   * @param coefficient The amount's unscaled value.
   * @param scale The amount's scale.
   * @param expectedCoefficient The rounded result's unscaled value.
   * @param expectedScale The rounded result's scale.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @MethodSource("conversions")
  void testRepresentableConversion(String from, String to, String coefficient, int scale,
      String expectedCoefficient, int expectedScale) throws IOException {
    final EcbFxRates rates = table(from, to);
    final MoneyAmount source = new MoneyAmount(SPAN,
        new BigDecimal(new BigInteger(coefficient), scale), USD);
    final MoneyAmount converted = Assertions.assertDoesNotThrow(
        () -> rates.convert(source, JPY, DATE)).orElseThrow();
    final BigDecimal expected = new BigDecimal(new BigInteger(expectedCoefficient), expectedScale);
    Assertions.assertEquals(0, expected.compareTo(converted.amount()));
    Assertions.assertEquals(SPAN, converted.span());
    Assertions.assertEquals(JPY, converted.currency());
    Assertions.assertTrue(converted.amount().precision() <= 16);
  }

  /**
   * Rate division works when quote exponents cancel or the result fits at a scale limit.
   *
   * @param from The source quote.
   * @param to The target quote.
   * @param expected The expected cross rate.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @CsvSource({"1E-2147483647, 2E-2147483647, 2", "1E+2147483647, 2E+2147483647, 2",
      "1, 1E-2147483647, 1E-2147483647", "1E-2147483647, 1, 1E+2147483647",
      "3E-2147483647, 1E-2147483647, 0.3333333333333333"})
  void testRepresentableRate(String from, String to, String expected) throws IOException {
    assertRate(expected, Assertions.assertDoesNotThrow(() -> table(from, to).rate(USD, JPY, DATE)));
  }

  /**
   * Zero remains convertible when the quotes and amount have extreme scales.
   *
   * @param scale The zero amount's scale.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, 0, Integer.MAX_VALUE})
  void testZeroConversion(int scale) throws IOException {
    final EcbFxRates rates = table("1E+2147483647", "1E-2147483647");
    final MoneyAmount source = new MoneyAmount(SPAN, new BigDecimal(BigInteger.ZERO, scale), USD);
    final MoneyAmount converted = Assertions.assertDoesNotThrow(
        () -> rates.convert(source, JPY, DATE)).orElseThrow();
    Assertions.assertEquals(0, converted.amount().signum());
    Assertions.assertEquals(SPAN, converted.span());
  }

  /**
   * An unrepresentable cross rate raises an arithmetic error instead of returning no quote.
   *
   * @param from The source quote.
   * @param to The target quote.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @CsvSource({"1E+2147483647, 1E-2147483647", "1E-2147483647, 1E+2147483647"})
  void testUnrepresentableRate(String from, String to) throws IOException {
    final EcbFxRates rates = table(from, to);
    Assertions.assertThrows(ArithmeticException.class, () -> rates.rate(USD, JPY, DATE));
    Assertions.assertThrows(ArithmeticException.class,
        () -> rates.convert(new MoneyAmount(SPAN, BigDecimal.ONE, USD), JPY, DATE));
  }

  /**
   * Results outside the 16-digit decimal range are not rounded to zero or expanded indefinitely.
   *
   * @param scale The input amount scale.
   * @param quote The target quote.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @CsvSource({"-2147483648, 1E+16", "2147483647, 0.1"})
  void testUnrepresentableConversion(int scale, String quote) throws IOException {
    final EcbFxRates rates = table("1", quote);
    final MoneyAmount source = new MoneyAmount(SPAN, new BigDecimal(BigInteger.ONE, scale), USD);
    Assertions.assertThrows(ArithmeticException.class, () -> rates.convert(source, JPY, DATE));
  }

  /**
   * Supplies independent amount and common-quote scale shifts.
   *
   * @return The scale combinations, with room for 16-digit results at both limits.
   */
  private static Stream<Arguments> scaleShifts() {
    return Stream.of(Integer.MIN_VALUE + 64, 0, Integer.MAX_VALUE - 64)
        .flatMap(amountScale -> Stream.of(Integer.MIN_VALUE + 64, 0, Integer.MAX_VALUE - 64)
            .map(quoteScale -> Arguments.of(amountScale, quoteScale)));
  }

  /**
   * Moving both quotes by the same decimal exponent preserves rates and converted amounts.
   *
   * @param amountScale The source amount scale.
   * @param quoteScale The common scale shift for both quotes.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @MethodSource("scaleShifts")
  void testGeneratedScaleEquivalence(int amountScale, int quoteScale) throws IOException {
    final Random random = new Random(20260905L);
    for (int i = 0; i < 100; i++) {
      final BigDecimal from = BigDecimal.valueOf(random.nextLong(1, 1_000_000), random.nextInt(6));
      final BigDecimal to = BigDecimal.valueOf(random.nextLong(1, 1_000_000), random.nextInt(6));
      final BigDecimal amount = BigDecimal.valueOf(random.nextLong(-1_000_000_000_000_000L,
          1_000_000_000_000_000L));
      final BigDecimal expectedRate = to.divide(from, MathContext.DECIMAL64);
      final BigDecimal expectedAmount = amount.multiply(to).divide(from, MathContext.DECIMAL64);
      final BigDecimal shiftedFrom = new BigDecimal(from.unscaledValue(),
          Math.addExact(from.scale(), quoteScale));
      final BigDecimal shiftedTo = new BigDecimal(to.unscaledValue(),
          Math.addExact(to.scale(), quoteScale));
      final EcbFxRates rates = table(shiftedFrom.toString(), shiftedTo.toString());
      Assertions.assertEquals(expectedRate, rates.rate(USD, JPY, DATE).orElseThrow(),
          "rate case " + i);
      final MoneyAmount source = new MoneyAmount(SPAN,
          new BigDecimal(amount.unscaledValue(), amountScale), USD);
      final BigDecimal converted = rates.convert(source, JPY, DATE).orElseThrow().amount();
      Assertions.assertEquals(new BigDecimal(expectedAmount.unscaledValue(),
          Math.addExact(expectedAmount.scale(), amountScale)), converted, "amount case " + i);
    }
  }

  /**
   * Creates a table containing one date and both currencies.
   *
   * @param from The source quote.
   * @param to The target quote.
   * @return The loaded table.
   * @throws IOException If loading fails.
   */
  private EcbFxRates table(String from, String to) throws IOException {
    return load("Date,USD,JPY\n2026-07-14," + from + "," + to + "\n");
  }
}
