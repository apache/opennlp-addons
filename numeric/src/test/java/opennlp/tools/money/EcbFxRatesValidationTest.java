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
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

import static opennlp.tools.money.EcbFxRatesTestSupport.assertRate;
import static opennlp.tools.money.EcbFxRatesTestSupport.load;

/** Tests CSV structure, rate values, decoding, and stream ownership with synthetic data. */
class EcbFxRatesValidationTest {

  private static final LocalDate DATE = LocalDate.of(2026, 7, 14);

  /**
   * Both lookup methods reject missing or blank currency codes.
   *
   * @param code The invalid code.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void testInvalidLookupCode(String code) throws IOException {
    final EcbFxRates rates = load("Date,USD\n2026-07-14,1.25\n");
    final MoneyAmount source = new MoneyAmount(new Span(0, 1), BigDecimal.ONE, "EUR");
    Assertions.assertThrows(IllegalArgumentException.class, () -> rates.rate(code, "USD", DATE));
    Assertions.assertThrows(IllegalArgumentException.class, () -> rates.rate("EUR", code, DATE));
    Assertions.assertThrows(IllegalArgumentException.class, () -> rates.convert(source, code, DATE));
  }

  /**
   * Both lookup methods reject a missing date before searching the table.
   *
   * @throws IOException If loading fails.
   */
  @Test
  void testMissingLookupDate() throws IOException {
    final EcbFxRates rates = load("Date,USD\n2026-07-14,1.25\n");
    final MoneyAmount source = new MoneyAmount(new Span(0, 1), BigDecimal.ONE, "EUR");
    Assertions.assertThrows(IllegalArgumentException.class, () -> rates.rate("EUR", "USD", null));
    Assertions.assertThrows(IllegalArgumentException.class, () -> rates.convert(source, "USD", null));
  }

  /**
   * Invalid headers cannot define ambiguous or unnamed currency columns.
   *
   * @param header The invalid CSV header.
   */
  @ParameterizedTest
  @ValueSource(strings = {"Date,", "Date,,", "Date,USD,USD", "Date,USD, USD ",
      "Date,USD,,JPY", "Date,usd", "Date,US", "Date,USDX", "Date,US1",
      "Date,US\u0301", "Date,\u20ACUR"})
  void testInvalidHeader(String header) {
    int columns = (int) header.chars().filter(character -> character == ',').count();
    if (header.endsWith(",")) {
      columns--;
    }
    final StringBuilder csv = new StringBuilder(header).append('\n').append("2026-07-14");
    for (int i = 0; i < columns; i++) {
      csv.append(",1.2");
    }
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> load(csv.append('\n').toString()));
  }

  /**
   * All declared columns must be present; only one optional empty final column is allowed.
   *
   * @param row The invalid data row for a Date,USD,JPY header.
   */
  @ParameterizedTest
  @ValueSource(strings = {"2026-07-14", "2026-07-14,1.2", "2026-07-14,1.2,150,extra",
      "2026-07-14,1.2,150,,", "2026-07-14,1.2,150, , "})
  void testInvalidColumnCount(String row) {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> load("Date,USD,JPY,\n" + row + "\n"));
  }

  /**
   * Duplicate dates cannot replace a previously loaded quote.
   *
   * @param secondValue The repeated or conflicting quote on the same date.
   */
  @ParameterizedTest
  @ValueSource(strings = {"1.2", "1.3", "N/A"})
  void testDuplicateDate(String secondValue) {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> load("Date,USD\n2026-07-14,1.2\n2026-07-14," + secondValue + "\n"));
  }

  /**
   * Quotes must be positive decimal values.
   *
   * @param value The invalid quote.
   */
  @ParameterizedTest
  @ValueSource(strings = {"0", "0.0000", "-0", "-0.0000", "0E+20", "-1", "-0.001",
      "NaN", "Infinity", "1.2x"})
  void testInvalidRate(String value) {
    final IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
        () -> load("Date,USD\n2026-07-14," + value + "\n"));
    Assertions.assertTrue(error.getMessage().contains("USD"));
    Assertions.assertTrue(error.getMessage().contains("2026-07-14"));
  }

  /**
   * An explicit euro quote cannot contradict the implicit base rate of one.
   *
   * @param value The contradictory euro quote.
   */
  @ParameterizedTest
  @ValueSource(strings = {"0.9", "1.1", "2"})
  void testInvalidBaseRate(String value) {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> load("Date,EUR,USD\n2026-07-14," + value + ",1.2\n"));
  }

  /**
   * Optional trailing commas, missing quotes, whitespace, and historical codes remain valid.
   *
   * @param csv A valid synthetic reference table.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {"Date,USD,JPY,\n2026-07-14,1.2,N/A,\n",
      "Date,USD,JPY\n2026-07-14,1.2,N/A\n", "Date,USD,JPY,\n2026-07-14,1.2,\n",
      "Date,USD,JPY\n2026-07-14,1.2,,\n", "Date, USD , JPY ,\r\n2026-07-14, 1.2 , ,\r\n",
      "Date,USD,EEK\n2026-07-14,1.2,N/A\n", "Date,EUR,USD\n2026-07-14,1.0000,1.2\n"})
  void testSupportedLayout(String csv) throws IOException {
    final EcbFxRates rates = load(csv);
    assertRate("1.2", rates.rate("EUR", "USD", DATE));
    Assertions.assertTrue(rates.rate("EUR", "JPY", DATE).isEmpty());
  }

  /**
   * Positive scientific notation remains accepted as a decimal quote.
   *
   * @param value The supported BigDecimal value.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {"1E-30", "1E+30", "+1.25", ".125"})
  void testPositiveDecimalForms(String value) throws IOException {
    final EcbFxRates rates = load("Date,USD\n2026-07-14," + value + "\n");
    assertRate(value, rates.rate("EUR", "USD", DATE));
  }

  /**
   * Malformed UTF-8 is not replaced with a character in a parsed field.
   *
   * @param suffix Whether to insert invalid bytes after a complete data record.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testMalformedUtf8(boolean suffix) {
    final byte[] prefix = (suffix ? "Date,USD\n2026-07-14,1.2\n" : "Date,US")
        .getBytes(StandardCharsets.UTF_8);
    final byte[] bytes = java.util.Arrays.copyOf(prefix, prefix.length + 1);
    bytes[prefix.length] = (byte) 0xC3;
    final TrackingStream in = new TrackingStream(bytes);
    Assertions.assertThrows(IOException.class, () -> EcbFxRates.load(in));
    Assertions.assertFalse(in.closed);
  }

  /**
   * Stream ownership remains with the source on both success and invalid content.
   *
   * @param valid Whether to provide valid input.
   * @throws IOException If reading valid input fails.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testStreamOwnership(boolean valid) throws IOException {
    final TrackingStream in = new TrackingStream((valid
        ? "Date,USD\n2026-07-14,1.2\n" : "Date,USD\n2026-07-14,broken\n")
        .getBytes(StandardCharsets.UTF_8));
    if (valid) {
      EcbFxRates.load(in);
    } else {
      Assertions.assertThrows(IllegalArgumentException.class, () -> EcbFxRates.load(in));
    }
    Assertions.assertFalse(in.closed);
  }

  /** I/O failures propagate without closing the input stream. */
  @Test
  void testReadFailure() {
    final IOException failure = new IOException("test read failure");
    final InputStream in = new InputStream() {
      /** {@inheritDoc} */
      @Override
      public int read() throws IOException {
        throw failure;
      }

      /** {@inheritDoc} */
      @Override
      public void close() {
        Assertions.fail("load must not close its input");
      }
    };
    Assertions.assertSame(failure, Assertions.assertThrows(IOException.class, () -> EcbFxRates.load(in)));
  }

  /**
   * File loading uses the same validation as stream loading.
   *
   * @param directory The temporary directory.
   * @throws IOException If fixture creation or reading fails.
   */
  @Test
  void testPathLoading(@TempDir Path directory) throws IOException {
    final Path path = directory.resolve("rates.csv");
    Files.writeString(path, "Date,USD\n2026-07-14,1.2\n", StandardCharsets.UTF_8);
    assertRate("1.2", EcbFxRates.load(path).rate("EUR", "USD", DATE));
    Assertions.assertThrows(IllegalArgumentException.class, () -> EcbFxRates.load((Path) null));
    Assertions.assertThrows(IOException.class, () -> EcbFxRates.load(directory.resolve("missing.csv")));
  }

  /** Tracks close calls without changing byte-stream behavior. */
  private static final class TrackingStream extends ByteArrayInputStream {

    private boolean closed;

    /**
     * Initializes the stream.
     *
     * @param bytes The content to read.
     */
    private TrackingStream(byte[] bytes) {
      super(bytes);
    }

    /** {@inheritDoc} */
    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
