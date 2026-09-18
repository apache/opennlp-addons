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

import opennlp.tools.util.Span;

/**
 * One monetary mention in a text: the {@link Span} it covers in the original text, the
 * normalized amount, and the currency it was expressed in.
 *
 * <p>The amount is normalized: digit grouping is removed and scale markers are applied,
 * so {@code $1.2M} carries the amount {@code 1200000}. Amounts are exact
 * {@link BigDecimal} values; monetary quantities are never represented in floating
 * point. The currency is an ISO 4217 alphabetic code.</p>
 *
 * @param span The location of the mention in the original text. Must not be {@code null}.
 * @param amount The normalized amount. Must not be {@code null}.
 * @param currency The ISO 4217 alphabetic currency code, for example {@code USD}. Must
 *                 not be {@code null} or blank.
 *
 * @see <a href="https://www.iso.org/iso-4217-currency-codes.html">ISO 4217</a>
 * @since 3.0.0
 */
public record MoneyAmount(Span span, BigDecimal amount, String currency) {

  /**
   * Validates the mention.
   *
   * @throws IllegalArgumentException Thrown if {@code span} or {@code amount} is
   *         {@code null}, or {@code currency} is {@code null} or blank.
   */
  public MoneyAmount {
    if (span == null) {
      throw new IllegalArgumentException("span must not be null");
    }
    if (amount == null) {
      throw new IllegalArgumentException("amount must not be null");
    }
    if (currency == null || currency.isBlank()) {
      throw new IllegalArgumentException("currency must not be null or blank");
    }
  }
}
