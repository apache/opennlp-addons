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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

/** Tests argument validation and delegation in the default conversion method. */
class FxRatesContractTest {

  private static final LocalDate DATE = LocalDate.of(2026, 7, 14);
  private static final MoneyAmount SOURCE =
      new MoneyAmount(new Span(3, 7), new BigDecimal("-10.125"), "EUR");

  /**
   * Invalid target codes are rejected before a provider is queried.
   *
   * @param target The invalid target code.
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void testInvalidTarget(String target) {
    final AtomicInteger calls = new AtomicInteger();
    final FxRates provider = (from, to, date) -> {
      calls.incrementAndGet();
      return Optional.empty();
    };
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> provider.convert(SOURCE, target, DATE));
    Assertions.assertEquals(0, calls.get());
  }

  /** A missing date is rejected before a provider is queried. */
  @Test
  void testMissingDate() {
    final AtomicInteger calls = new AtomicInteger();
    final FxRates provider = (from, to, date) -> {
      calls.incrementAndGet();
      return Optional.empty();
    };
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> provider.convert(SOURCE, "USD", null));
    Assertions.assertEquals(0, calls.get());
  }

  /** A null amount is rejected before a provider is queried. */
  @Test
  void testMissingAmount() {
    final FxRates provider = (from, to, date) -> {
      Assertions.fail("invalid arguments must not query the provider");
      return Optional.empty();
    };
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> provider.convert(null, "USD", DATE));
  }

  /** Valid conversion delegates once and retains the original span. */
  @Test
  void testValidConversion() {
    final AtomicInteger calls = new AtomicInteger();
    final FxRates provider = (from, to, date) -> {
      calls.incrementAndGet();
      Assertions.assertEquals("EUR", from);
      Assertions.assertEquals("USD", to);
      Assertions.assertEquals(DATE, date);
      return Optional.of(new BigDecimal("1.25"));
    };
    final MoneyAmount converted = provider.convert(SOURCE, "USD", DATE).orElseThrow();
    Assertions.assertEquals(1, calls.get());
    Assertions.assertEquals(new MoneyAmount(SOURCE.span(), new BigDecimal("-12.65625"), "USD"), converted);
    final FxRates missing = (from, to, date) -> Optional.empty();
    Assertions.assertTrue(missing.convert(SOURCE, "USD", DATE).isEmpty());
  }
}
