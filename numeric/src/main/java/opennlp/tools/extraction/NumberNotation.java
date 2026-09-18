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

package opennlp.tools.extraction;

import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * The written conventions for grouping digits and marking a decimal fraction that the
 * numeric extractors read.
 *
 * <p>A caller selects one convention, directly or through {@link #forLocale(Locale)}.
 * Some inputs are valid in both but have different values: {@code 1.234} means 1.234 in
 * {@link #LATIN_US} and 1234 in {@link #LATIN_EU}. The extractor does not infer which
 * value the author intended. Invalid grouping, such as {@code 1,23} in LATIN_US, is
 * rejected.</p>
 *
 * @see NumberScan
 * @since 3.0.0
 */
public enum NumberNotation {

  /**
   * Commas group digits and a dot marks the fraction, as in {@code 1,234.56}.
   */
  LATIN_US(',', '.'),

  /**
   * Dots group digits and a comma marks the fraction, as in {@code 1.234,56}.
   */
  LATIN_EU('.', ',');

  private final char groupSeparator;
  private final char decimalSeparator;

  /**
   * Stores the notation's separators.
   *
   * @param groupSeparator The character separating digit groups.
   * @param decimalSeparator The character separating the fractional part.
   */
  NumberNotation(char groupSeparator, char decimalSeparator) {
    this.groupSeparator = groupSeparator;
    this.decimalSeparator = decimalSeparator;
  }

  /**
   * @return The character that separates groups of three digits.
   */
  public char groupSeparator() {
    return groupSeparator;
  }

  /**
   * @return The character that separates the integer part from the fraction.
   */
  public char decimalSeparator() {
    return decimalSeparator;
  }

  /**
   * Resolves the notation a locale writes numbers in.
   *
   * <p>The decision is taken from the locale's own {@link DecimalFormatSymbols}, so it
   * follows the JDK's locale data: a comma decimal separator selects {@link #LATIN_EU};
   * every other decimal separator selects {@link #LATIN_US}. This selection does not
   * add support for the locale's other grouping characters or non-ASCII digits.</p>
   *
   * @param locale The locale to resolve. Must not be {@code null}.
   * @return The notation of {@code locale}. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code locale} is {@code null}.
   */
  public static NumberNotation forLocale(Locale locale) {
    if (locale == null) {
      throw new IllegalArgumentException("locale must not be null");
    }
    return DecimalFormatSymbols.getInstance(locale).getDecimalSeparator()
        == LATIN_EU.decimalSeparator ? LATIN_EU : LATIN_US;
  }
}
