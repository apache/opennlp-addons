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

package opennlp.tools.quantity;

import java.math.BigDecimal;

import opennlp.tools.util.Span;

/**
 * One quantity mention in a text: the {@link Span} it covers in the original text, the
 * numeric value, and the unit it was expressed in.
 *
 * <p>The value is an exact {@link BigDecimal} with digit grouping removed. The unit is
 * the matched unit token, or {@code %} for percentages, including those written as the
 * word {@code percent}. No unit conversion is performed; {@code 2.5km} keeps the value
 * {@code 2.5} and the unit {@code km}.</p>
 *
 * @param span The location of the mention in the original text. Must not be {@code null}.
 * @param value The numeric value. Must not be {@code null}.
 * @param unit The unit token, or {@code %} for percentages. Must not be {@code null} or
 *             blank.
 *
 * @since 3.0.0
 */
public record Quantity(Span span, BigDecimal value, String unit) {

  /**
   * Validates the mention.
   *
   * @throws IllegalArgumentException Thrown if {@code span} or {@code value} is
   *         {@code null}, or {@code unit} is {@code null} or blank.
   */
  public Quantity {
    if (span == null) {
      throw new IllegalArgumentException("span must not be null");
    }
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    if (unit == null || unit.isBlank()) {
      throw new IllegalArgumentException("unit must not be null or blank");
    }
  }
}
