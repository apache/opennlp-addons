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
import java.util.Optional;

import org.junit.jupiter.api.Assertions;

/** Shared helpers for synthetic reference-rate tables. */
final class EcbFxRatesTestSupport {

  /** Prevents utility instances. */
  private EcbFxRatesTestSupport() {
  }

  /**
   * Loads synthetic CSV text.
   *
   * @param csv The input content.
   * @return The parsed table.
   * @throws IOException If reading fails.
   */
  static EcbFxRates load(String csv) throws IOException {
    return EcbFxRates.load(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Compares an available rate by numeric value, independent of decimal scale.
   *
   * @param expected The expected decimal value.
   * @param actual The lookup result.
   */
  static void assertRate(String expected, Optional<BigDecimal> actual) {
    Assertions.assertTrue(actual.isPresent());
    Assertions.assertEquals(0, new BigDecimal(expected).compareTo(actual.orElseThrow()));
  }
}
