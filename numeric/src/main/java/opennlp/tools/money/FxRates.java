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
import java.time.LocalDate;
import java.util.Optional;

/**
 * Provides exchange rates for a requested date and converts {@link MoneyAmount} values.
 *
 * <p>OpenNLP does not bundle rate data. Use a date from document metadata or an
 * extracted temporal expression to convert amounts for the document date.</p>
 *
 * @see <a href="https://www.iso.org/iso-4217-currency-codes.html">ISO 4217</a>
 * @since 3.0.0
 */
public interface FxRates {

  /**
   * Retrieves the exchange rate between two currencies as of a date.
   *
   * @param from The ISO 4217 code of the source currency. Must not be {@code null} or
   *             blank.
   * @param to The ISO 4217 code of the target currency. Must not be {@code null} or
   *           blank.
   * @param asOf The date the rate should hold for. Must not be {@code null}.
   * @return The number of target units per source unit, or empty when the provider has
   *         no usable rate for the pair at that date. Never {@code null}.
   * @throws IllegalArgumentException Thrown if any parameter is {@code null} or a code
   *         is blank.
   * @throws ArithmeticException If the result exceeds the supported decimal range.
   */
  Optional<BigDecimal> rate(String from, String to, LocalDate asOf);

  /**
   * Converts a money mention into another currency, keeping its span.
   *
   * @param money The mention to convert. Must not be {@code null}.
   * @param to The ISO 4217 code of the target currency. Must not be {@code null} or
   *           blank.
   * @param asOf The date the conversion should hold for. Must not be {@code null}.
   * @return A {@link MoneyAmount} in the target currency on the original span, or empty
   *         when no usable rate exists. Never {@code null}.
   * @throws IllegalArgumentException Thrown if any parameter is {@code null} or
   *         {@code to} is blank.
   * @throws ArithmeticException If the result exceeds the supported decimal range.
   */
  default Optional<MoneyAmount> convert(MoneyAmount money, String to, LocalDate asOf) {
    if (money == null) {
      throw new IllegalArgumentException("money must not be null");
    }
    if (to == null || to.isBlank()) {
      throw new IllegalArgumentException("to must not be null or blank");
    }
    if (asOf == null) {
      throw new IllegalArgumentException("asOf must not be null");
    }
    return rate(money.currency(), to, asOf)
        .map(rate -> new MoneyAmount(money.span(), money.amount().multiply(rate), to));
  }
}
