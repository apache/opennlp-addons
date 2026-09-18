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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Loads euro foreign exchange reference rates from an uncompressed, UTF-8 ECB history
 * CSV file. OpenNLP does not download or bundle the data.
 *
 * <p>The header begins with {@code Date,} followed by unique currency codes of 3
 * uppercase ASCII letters. Fields are unquoted. ASCII whitespace padding is accepted;
 * other ASCII control characters are rejected. Dates must be unique, and records must
 * include all columns. A header or record may end with one additional empty field.
 * Blank quotes and {@code N/A} indicate unavailable currencies; numeric quotes must be
 * positive. An explicit {@code EUR} quote must equal 1.</p>
 *
 * <p>A lookup selects the latest reference date on or before the requested date, up to
 * {@link #MAX_STALENESS_DAYS} days earlier. Both currencies must be available on that
 * reference date; an unavailable currency does not trigger a search of older records.
 * The euro base rate is always 1 on a usable reference date.</p>
 *
 * <p>Cross-currency rates and amounts use {@link MathContext#DECIMAL64}: 16 significant
 * digits with half-even rounding. Conversion multiplies the amount by the target quote
 * before dividing by the source quote. Same-currency conversion preserves the original
 * amount and scale, but still requires an available quote on a usable date. Rates and
 * cross-currency amounts that cannot fit the {@link BigDecimal} scale range at this
 * precision raise {@link ArithmeticException}.</p>
 *
 * <p>Instances are immutable after loading and safe to share between threads.</p>
 *
 * @see <a href="https://www.ecb.europa.eu/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/index.en.html">ECB euro foreign exchange reference rates</a>
 * @see <a href="https://www.ecb.europa.eu/services/using-our-site/disclaimer/html/index.en.html">ECB data use terms</a>
 * @see <a href="https://www.iso.org/iso-4217-currency-codes.html">ISO 4217</a>
 * @since 3.0.0
 */
public class EcbFxRates implements FxRates {

  /**
   * Maximum reference-rate age in calendar days, inclusive.
   */
  public static final int MAX_STALENESS_DAYS = 7;

  private static final String BASE_CURRENCY = "EUR";
  private static final String NOT_AVAILABLE = "N/A";
  private static final char FIELD_SEPARATOR = ',';
  private static final String HEADER_PREFIX = "Date,";

  private final TreeMap<LocalDate, Map<String, BigDecimal>> table;

  /**
   * Initializes a table owned by the loader.
   *
   * @param table The parsed reference dates and quotes.
   */
  private EcbFxRates(TreeMap<LocalDate, Map<String, BigDecimal>> table) {
    this.table = table;
  }

  /**
   * Loads a reference history table from a file.
   *
   * @param csv The history CSV file. Must not be {@code null}.
   * @return A loaded {@link EcbFxRates}. Never {@code null}.
   * @throws IOException Thrown if reading or UTF-8 decoding fails.
   * @throws IllegalArgumentException Thrown if {@code csv} is {@code null} or the
   *         content is not in the expected format.
   */
  public static EcbFxRates load(Path csv) throws IOException {
    if (csv == null) {
      throw new IllegalArgumentException("csv must not be null");
    }
    try (InputStream in = Files.newInputStream(csv)) {
      return load(in);
    }
  }

  /**
   * Loads a reference history table from a stream.
   *
   * @param in The history CSV content. Must not be {@code null}. The stream is read
   *           fully but not closed.
   * @return A loaded {@link EcbFxRates}. Never {@code null}.
   * @throws IOException Thrown if reading or UTF-8 decoding fails.
   * @throws IllegalArgumentException Thrown if {@code in} is {@code null} or the
   *         content is not in the expected format.
   */
  public static EcbFxRates load(InputStream in) throws IOException {
    if (in == null) {
      throw new IllegalArgumentException("in must not be null");
    }
    final BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)));
    final String header = reader.readLine();
    if (header == null || !header.startsWith(HEADER_PREFIX)) {
      throw new IllegalArgumentException("not a reference history CSV: header is " + header);
    }
    final List<String> currencies = currencyColumns(header);
    final TreeMap<LocalDate, Map<String, BigDecimal>> table = new TreeMap<>();
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isBlank()) {
        continue;
      }
      final List<String> fields = fields(line);
      final int columns = currencies.size();
      if (fields.size() != columns
          && !(fields.size() == columns + 1 && fields.getLast().isEmpty())) {
        throw new IllegalArgumentException("expected " + columns
            + " reference history columns in row: " + line);
      }
      final LocalDate date;
      try {
        date = LocalDate.parse(fields.getFirst());
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException("not a reference history row: " + line, e);
      }
      final Map<String, BigDecimal> rates = new HashMap<>();
      for (int i = 1; i < columns; i++) {
        final String value = fields.get(i);
        final String currency = currencies.get(i);
        if (!value.isEmpty() && !NOT_AVAILABLE.equals(value)) {
          final BigDecimal rate;
          try {
            rate = new BigDecimal(value);
          } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a reference history rate for "
                + currency + ": " + value + " in row: " + line, e);
          }
          if (rate.signum() <= 0) {
            throw new IllegalArgumentException("reference history rate must be positive for "
                + currency + " in row: " + line);
          }
          if (BASE_CURRENCY.equals(currency) && rate.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("reference history EUR rate must equal 1 in row: " + line);
          }
          rates.put(currency, rate);
        }
      }
      if (table.putIfAbsent(date, rates) != null) {
        throw new IllegalArgumentException("duplicate reference history date: " + date);
      }
    }
    if (table.isEmpty()) {
      throw new IllegalArgumentException("the reference history contains no rows");
    }
    return new EcbFxRates(table);
  }

  /**
   * Parses unique currency columns with an optional empty final column.
   *
   * @param header The CSV header beginning with {@link #HEADER_PREFIX}.
   * @return The date column followed by the currency codes.
   * @throws IllegalArgumentException If no currency is present, a code is malformed,
   *         or a currency is repeated.
   */
  private static List<String> currencyColumns(String header) {
    final List<String> columns = fields(header);
    if (columns.getLast().isEmpty()) {
      columns.removeLast();
    }
    if (columns.size() < 2) {
      throw new IllegalArgumentException("reference history header must name a currency");
    }
    final Set<String> seen = new HashSet<>();
    for (int i = 1; i < columns.size(); i++) {
      final String code = columns.get(i);
      if (!currencyCode(code)) {
        throw new IllegalArgumentException("invalid reference history currency code: " + code);
      }
      if (!seen.add(code)) {
        throw new IllegalArgumentException("duplicate reference history currency code: " + code);
      }
    }
    return columns;
  }

  /**
   * Checks the syntax of a currency code without depending on a currency registry version.
   *
   * @param code The trimmed currency column name.
   * @return Whether the code contains 3 uppercase ASCII letters.
   */
  private static boolean currencyCode(String code) {
    if (code.length() != 3) {
      return false;
    }
    for (int i = 0; i < code.length(); i++) {
      if (code.charAt(i) < 'A' || code.charAt(i) > 'Z') {
        return false;
      }
    }
    return true;
  }

  /**
   * Separates unquoted CSV fields, preserving empty fields and trimming ASCII whitespace.
   *
   * @param line The input line.
   * @return The fields in input order.
   * @throws IllegalArgumentException If an ASCII control character is not Java whitespace.
   */
  private static List<String> fields(String line) {
    final List<String> fields = new ArrayList<>();
    int start = 0;
    for (int i = 0; i <= line.length(); i++) {
      if (i == line.length() || line.charAt(i) == FIELD_SEPARATOR) {
        fields.add(line.substring(start, i).trim());
        start = i + 1;
      } else {
        final char value = line.charAt(i);
        if (value < ' ' && !Character.isWhitespace(value)) {
          throw new IllegalArgumentException("invalid control character at CSV offset " + i);
        }
      }
    }
    return fields;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Divides the target quote by the source quote using {@link MathContext#DECIMAL64}.</p>
   */
  @Override
  public Optional<BigDecimal> rate(String from, String to, LocalDate asOf) {
    if (from == null || from.isBlank()) {
      throw new IllegalArgumentException("from must not be null or blank");
    }
    if (to == null || to.isBlank()) {
      throw new IllegalArgumentException("to must not be null or blank");
    }
    if (asOf == null) {
      throw new IllegalArgumentException("asOf must not be null");
    }
    final Map<String, BigDecimal> row = usableRow(asOf);
    if (row == null) {
      return Optional.empty();
    }
    final BigDecimal perFrom = perEuro(row, from);
    final BigDecimal perTo = perEuro(row, to);
    if (perFrom == null || perTo == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(from.equals(to) ? BigDecimal.ONE
          : perTo.divide(perFrom, MathContext.DECIMAL64));
    } catch (ArithmeticException e) {
      return Optional.of(decimalQuotient(perTo.unscaledValue(), perFrom.unscaledValue(),
          (long) perTo.scale() - perFrom.scale()));
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Cross-currency amounts are rounded once after multiplication and division.</p>
   */
  @Override
  public Optional<MoneyAmount> convert(MoneyAmount money, String to, LocalDate asOf) {
    if (money == null) {
      throw new IllegalArgumentException("money must not be null");
    }
    if (to == null || to.isBlank()) {
      throw new IllegalArgumentException("to must not be null or blank");
    }
    if (asOf == null) {
      throw new IllegalArgumentException("asOf must not be null");
    }
    final Map<String, BigDecimal> row = usableRow(asOf);
    if (row == null) {
      return Optional.empty();
    }
    final BigDecimal perFrom = perEuro(row, money.currency());
    final BigDecimal perTo = perEuro(row, to);
    if (perFrom == null || perTo == null) {
      return Optional.empty();
    }
    if (money.currency().equals(to)) {
      return Optional.of(money);
    }
    final BigDecimal amount;
    try {
      amount = money.amount().multiply(perTo).divide(perFrom, MathContext.DECIMAL64);
    } catch (ArithmeticException e) {
      final BigDecimal converted = decimalQuotient(
          money.amount().unscaledValue().multiply(perTo.unscaledValue()), perFrom.unscaledValue(),
          (long) money.amount().scale() + perTo.scale() - perFrom.scale());
      return Optional.of(new MoneyAmount(money.span(), converted, to));
    }
    return Optional.of(new MoneyAmount(money.span(), amount, to));
  }

  /**
   * Divides integer coefficients, rounds once, then applies the combined decimal scale.
   *
   * @param numerator The non-zero product of the numerator coefficients.
   * @param denominator The positive divisor coefficient.
   * @param scale The combined operand scale, before quotient rounding.
   * @return The quotient with at most 16 digits of precision.
   * @throws ArithmeticException If the rounded result cannot be represented within
   *         the decimal scale range at the configured precision.
   */
  private BigDecimal decimalQuotient(BigInteger numerator, BigInteger denominator, long scale) {
    BigDecimal quotient = new BigDecimal(numerator)
        .divide(new BigDecimal(denominator), MathContext.DECIMAL64);
    long resultScale = scale + quotient.scale();
    if (resultScale > Integer.MAX_VALUE) {
      quotient = quotient.stripTrailingZeros();
      resultScale = scale + quotient.scale();
    }
    if (resultScale < Integer.MIN_VALUE) {
      final long padding = Integer.MIN_VALUE - resultScale;
      if (padding <= MathContext.DECIMAL64.getPrecision() - quotient.precision()) {
        return new BigDecimal(quotient.unscaledValue().multiply(BigInteger.TEN.pow((int) padding)),
            Integer.MIN_VALUE);
      }
    }
    if (resultScale < Integer.MIN_VALUE || resultScale > Integer.MAX_VALUE) {
      throw new ArithmeticException("result is outside the supported decimal scale range");
    }
    return new BigDecimal(quotient.unscaledValue(), (int) resultScale);
  }

  /**
   * Selects the reference rates for a lookup date.
   *
   * @param asOf The date to look up. Must not be {@code null}.
   * @return The rates of the latest row on or before {@code asOf}, or {@code null} when
   *         no row is within {@link #MAX_STALENESS_DAYS} of it.
   */
  private Map<String, BigDecimal> usableRow(LocalDate asOf) {
    final Map.Entry<LocalDate, Map<String, BigDecimal>> row = table.floorEntry(asOf);
    if (row == null || ChronoUnit.DAYS.between(row.getKey(), asOf) > MAX_STALENESS_DAYS) {
      return null;
    }
    return row.getValue();
  }

  /**
   * The value of one euro in a currency, with the base currency itself worth one.
   *
   * @param rates The rates of one reference row. Must not be {@code null}.
   * @param currency The ISO 4217 code to look up. Must not be {@code null}.
   * @return The units of {@code currency} per euro, or {@code null} when the row does
   *         not quote it.
   */
  private BigDecimal perEuro(Map<String, BigDecimal> rates, String currency) {
    return BASE_CURRENCY.equals(currency) ? BigDecimal.ONE : rates.get(currency);
  }
}
