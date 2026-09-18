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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import opennlp.tools.extraction.NumberNotation;
import opennlp.tools.extraction.NumberScan;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

/**
 * A deterministic {@link MoneyExtractor}: a single forward scan over the text, no
 * regular expressions, recognizing the common written money shapes.
 *
 * <p>Recognized forms: a currency symbol before or after the number ({@code $1,234.56},
 * {@code 50\u20AC}), an ISO 4217 code before or after the number ({@code USD 100},
 * {@code 100 USD}), a spelled-out currency word after the number
 * ({@code 1.2 million dollars}), an optional leading minus ({@code -$5}), and scale
 * markers, either
 * an immediate suffix ({@code $1.2M}, {@code \u00A32.5k}, {@code $3bn}) or a following word
 * ({@code $3 billion}). Digit grouping is validated: once a group separator appears, every
 * further group must have exactly three digits. An amount grouped in a convention the
 * scanner cannot parse, for example the Indian-grouped {@code 1,00,000}, is rejected
 * entirely rather than truncated to a wrong value, and the separator-adjoined tail of such
 * a number never seeds a mention of its own. A bare number without a currency marker is
 * never money.</p>
 *
 * <p>Amounts are read in one {@link NumberNotation}, {@link NumberNotation#LATIN_US} by
 * default: {@code $1,234.56} is a little over a thousand dollars. A document written in
 * the European convention is read by an extractor built for it, either with the notation
 * given directly or with {@link #forRegion(Locale)}, and then {@code 1.234,56 EUR} is a
 * little over a thousand euros. Inputs valid in both notations are interpreted using
 * the selected one; invalid grouping is rejected.</p>
 *
 * <p>Currency symbols are inherently ambiguous; the default table maps each symbol to
 * the ISO code it most commonly denotes, for example {@code $} to {@code USD}. Callers
 * working in another convention supply their own mapping through
 * {@link #CursorMoneyExtractor(Map)}. ISO codes are taken from
 * {@link Currency#getAvailableCurrencies()}.</p>
 *
 * <p>English currency words use fixed defaults, including {@code dollar} for USD,
 * {@code rupee} for INR, and {@code pound} for GBP. Singular and plural forms are
 * supported. The words {@code peso}, {@code franc}, {@code krone}, and {@code won}
 * are excluded, as are subunits such as {@code cents}. Weight phrases such as
 * {@code 3 pounds of flour} are interpreted as GBP amounts.</p>
 *
 * <p>Not recognized: accounting negatives in parentheses and multi-character symbols such
 * as {@code kr} or {@code HK$}. A known symbol directly preceded by an ASCII letter is
 * read as part of such a longer symbol and rejected, so {@code HK$50} and {@code US$50}
 * yield no mention rather than a wrong one. The extractor holds no per-call state and is
 * safe to share between threads.</p>
 *
 * @see <a href="https://www.iso.org/iso-4217-currency-codes.html">ISO 4217</a>
 * @since 3.0.0
 */
public class CursorMoneyExtractor implements MoneyExtractor {

  private static final String USD = "USD";
  private static final String EUR = "EUR";
  private static final String GBP = "GBP";
  private static final String JPY = "JPY";
  private static final String INR = "INR";
  private static final String RUB = "RUB";
  private static final String ILS = "ILS";
  private static final String THB = "THB";
  private static final String CNY = "CNY";

  private static final Map<Integer, String> DEFAULT_SYMBOLS = Map.ofEntries(
      Map.entry((int) '$', USD),
      Map.entry(0x20AC, EUR),   // euro sign
      Map.entry(0x00A3, GBP),   // pound sign
      Map.entry(0x00A5, JPY),   // yen sign
      Map.entry(0x20B9, INR),   // rupee sign
      Map.entry(0x20A9, "KRW"),   // won sign
      Map.entry(0x20BD, RUB),   // ruble sign
      Map.entry(0x20BA, "TRY"),   // lira sign
      Map.entry(0x20AA, ILS),   // sheqel sign
      Map.entry(0x0E3F, THB),   // baht sign
      Map.entry(0x20AB, "VND"));  // dong sign

  /**
   * English currency words mapped to their conventional default ISO 4217 code. See the
   * class documentation for deliberate omissions and ambiguities.
   */
  private static final Map<String, String> CURRENCY_WORDS = Map.ofEntries(
      Map.entry("dollar", USD),
      Map.entry("dollars", USD),
      Map.entry("euro", EUR),
      Map.entry("euros", EUR),
      Map.entry("pound", GBP),
      Map.entry("pounds", GBP),
      Map.entry("sterling", GBP),
      Map.entry("yen", JPY),
      Map.entry("rupee", INR),
      Map.entry("rupees", INR),
      Map.entry("yuan", CNY),
      Map.entry("renminbi", CNY),
      Map.entry("ruble", RUB),
      Map.entry("rubles", RUB),
      Map.entry("rouble", RUB),
      Map.entry("roubles", RUB),
      Map.entry("shekel", ILS),
      Map.entry("shekels", ILS),
      Map.entry("baht", THB));

  /** The length of the longest currency word, {@code sterling} and {@code renminbi}. */
  private static final int MAX_CURRENCY_WORD_LENGTH = 8;

  private static final Set<String> ISO_CODES = isoCodes();

  /**
   * The symbol table flattened into parallel arrays: the code point at an index denotes
   * the currency code at the same index. The scan looks a symbol up at every text
   * position, and the primitive form keeps that lookup free of boxing.
   */
  private final int[] symbolCodePoints;
  private final String[] currencyCodes;

  private final NumberNotation notation;

  /**
   * Initializes the extractor with the default symbol table and
   * {@link NumberNotation#LATIN_US}.
   */
  public CursorMoneyExtractor() {
    this(DEFAULT_SYMBOLS, NumberNotation.LATIN_US);
  }

  /**
   * Initializes the extractor with the default symbol table and a number notation.
   *
   * @param notation The written convention amounts group digits and mark fractions in.
   *                 Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code notation} is {@code null}.
   */
  public CursorMoneyExtractor(NumberNotation notation) {
    this(DEFAULT_SYMBOLS, notation);
  }

  /**
   * Initializes the extractor with a custom symbol table and
   * {@link NumberNotation#LATIN_US}.
   *
   * @param symbolCurrencies Maps a currency symbol code point to the ISO 4217 code it
   *                         denotes. Must not be {@code null} or empty, no key may be
   *                         {@code null}, every key must be a Unicode currency-symbol
   *                         code point, and every value must be a known ISO 4217 code.
   * @throws IllegalArgumentException Thrown if the map is {@code null} or empty, maps a
   *         {@code null} or non-currency-symbol code point, or names an unknown code.
   */
  public CursorMoneyExtractor(Map<Integer, String> symbolCurrencies) {
    this(symbolCurrencies, NumberNotation.LATIN_US);
  }

  /**
   * Initializes the extractor with a custom symbol table and a number notation.
   *
   * @param symbolCurrencies Maps a currency symbol code point to the ISO 4217 code it
   *                         denotes. Must not be {@code null} or empty, no key may be
   *                         {@code null}, every key must be a Unicode currency-symbol
   *                         code point, and every value must be a known ISO 4217 code.
   * @param notation The written convention amounts group digits and mark fractions in.
   *                 Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if the map is {@code null} or empty, maps a
   *         {@code null} or non-currency-symbol code point, names an unknown currency
   *         code, or {@code notation} is {@code null}.
   */
  public CursorMoneyExtractor(Map<Integer, String> symbolCurrencies, NumberNotation notation) {
    if (symbolCurrencies == null || symbolCurrencies.isEmpty()) {
      throw new IllegalArgumentException("symbolCurrencies must not be null or empty");
    }
    if (notation == null) {
      throw new IllegalArgumentException("notation must not be null");
    }
    this.notation = notation;
    final List<Map.Entry<Integer, String>> symbols =
        new ArrayList<>(symbolCurrencies.entrySet());
    for (final Map.Entry<Integer, String> symbol : symbols) {
      if (symbol.getKey() == null) {
        throw new IllegalArgumentException("symbolCurrencies must not map a null code point");
      }
      if (!Character.isValidCodePoint(symbol.getKey())) {
        throw new IllegalArgumentException("not a valid Unicode code point: " + symbol.getKey());
      }
      if (Character.getType(symbol.getKey()) != Character.CURRENCY_SYMBOL) {
        throw new IllegalArgumentException("not a currency symbol code point: "
            + symbol.getKey());
      }
      final String code = symbol.getValue();
      if (code == null || !ISO_CODES.contains(code)) {
        throw new IllegalArgumentException("not an ISO 4217 currency code: " + code);
      }
    }
    symbols.sort(Map.Entry.comparingByKey());
    this.symbolCodePoints = new int[symbols.size()];
    this.currencyCodes = new String[symbols.size()];
    int i = 0;
    for (final Map.Entry<Integer, String> symbol : symbols) {
      symbolCodePoints[i] = symbol.getKey();
      currencyCodes[i] = symbol.getValue();
      i++;
    }
  }

  /**
   * Creates an extractor for a region: its symbol table resolves ambiguous symbols the
   * way the region does, so in an Australian document {@code $} denotes {@code AUD}, and
   * it reads numbers in the notation the region writes them in, so a German document's
   * {@code 1.234,56} is a little over a thousand.
   *
   * <p>Both decisions are derived from JDK locale data rather than from a hard-coded list
   * of countries. The symbol override applies when the region's own currency is written
   * with a single currency-sign code point in that region; that symbol then maps to the
   * region's currency and all other defaults stay. Regions whose conventional symbol is
   * not a single currency sign, for example a letter, keep the default table unchanged.
   * The notation comes from {@link NumberNotation#forLocale(Locale)}. This is the hook for
   * document-level location evidence: once a pipeline knows where a document speaks from,
   * money in it is identified in the right currency and can be converted through
   * {@link FxRates}.</p>
   *
   * @param region A locale with a country component. Must not be {@code null} and must
   *               name a region with a currency.
   * @return A {@link CursorMoneyExtractor} for the region. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code region} is {@code null} or has no
   *         currency.
   */
  public static CursorMoneyExtractor forRegion(Locale region) {
    if (region == null) {
      throw new IllegalArgumentException("region must not be null");
    }
    final Currency currency;
    try {
      currency = Currency.getInstance(region);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("region has no currency: " + region, e);
    }
    if (currency == null) {
      throw new IllegalArgumentException("region has no currency: " + region);
    }
    final NumberNotation regionNotation = NumberNotation.forLocale(region);
    final String symbol = currency.getSymbol(region);
    if (symbol.codePointCount(0, symbol.length()) != 1) {
      return new CursorMoneyExtractor(regionNotation);
    }
    final int cp = symbol.codePointAt(0);
    if (Character.getType(cp) != Character.CURRENCY_SYMBOL) {
      return new CursorMoneyExtractor(regionNotation);
    }
    final Map<Integer, String> symbols = new HashMap<>(DEFAULT_SYMBOLS);
    symbols.put(cp, currency.getCurrencyCode());
    return new CursorMoneyExtractor(symbols, regionNotation);
  }

  /**
   * {@inheritDoc}
   *
   * <p>The scan resumes behind each reported mention, so mentions never overlap.</p>
   */
  @Override
  public List<MoneyAmount> extract(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    final List<MoneyAmount> mentions = new ArrayList<>();
    int i = 0;
    while (i < text.length()) {
      final MoneyAmount mention = matchAt(text, i);
      if (mention != null) {
        mentions.add(mention);
        i = mention.span().getEnd();
      } else {
        i += Character.charCount(Character.codePointAt(text, i));
      }
    }
    return Collections.unmodifiableList(mentions);
  }

  /**
   * Tries the three mention shapes at one position: symbol first, ISO code first,
   * number first. A leading {@code -} counts as a minus sign only at a left boundary,
   * on the symbol-first path exactly as on the number-first path, so the hyphen in a
   * range such as {@code $100-$200} never negates the second amount. A digit that
   * continues a comma-grouped number the scanner rejected never starts a number-first
   * mention, so the tail {@code 000} of {@code 1,00,000 USD} is not read as 0 USD.
   * A known symbol directly preceded by an ASCII letter never starts a symbol-first
   * mention, since it is the tail of a longer symbol the table does not know, so
   * {@code HK$50} is not read as 50 USD.
   *
   * @param text The text being scanned.
   * @param start The offset the candidate mention would start at.
   * @return The mention starting at {@code start}, or {@code null} when none matches.
   */
  private MoneyAmount matchAt(CharSequence text, int start) {
    int i = start;
    boolean negative = false;
    if (NumberScan.charAt(text, i) == '-') {
      negative = true;
      i++;
    }
    final int cp = NumberScan.codePointAt(text, i);
    final String symbolCurrency = currencyFor(cp);
    if (symbolCurrency != null
        && !asciiLetterBefore(text, i)
        && (!negative || NumberScan.signBoundaryBefore(text, start))) {
      return symbolFirst(text, start, i, symbolCurrency, negative);
    }
    if (!negative && isUpperAscii(cp) && NumberScan.boundaryBefore(text, i)) {
      final MoneyAmount isoFirst = isoFirst(text, start, i);
      if (isoFirst != null) {
        return isoFirst;
      }
    }
    if (NumberScan.isAsciiDigit(cp)
        && !NumberScan.continuesNumber(text, i, notation)
        && (negative ? NumberScan.signBoundaryBefore(text, start)
            : NumberScan.boundaryBefore(text, i))) {
      return numberFirst(text, start, i, negative);
    }
    return null;
  }

  /**
   * Matches {@code $1,234.56}, {@code -$5}, {@code $1.2M}, and {@code $ 100}.
   *
   * @param text The text being scanned.
   * @param start The offset the mention starts at, the minus sign included.
   * @param symbolIndex The offset of the currency symbol.
   * @param currency The ISO 4217 code the symbol denotes.
   * @param negative {@code true} if a minus sign opens the mention.
   * @return The mention, or {@code null} when no number follows the symbol.
   */
  private MoneyAmount symbolFirst(CharSequence text, int start, int symbolIndex,
      String currency, boolean negative) {
    int i = symbolIndex + Character.charCount(NumberScan.codePointAt(text, symbolIndex));
    if (NumberScan.charAt(text, i) == ' ') {
      i++;
    }
    return mention(text, start, NumberScan.parse(text, i, true, notation), currency, negative);
  }

  /**
   * Matches {@code USD 100} and {@code USD 1.2 million}.
   *
   * @param text The text being scanned.
   * @param start The offset the mention starts at.
   * @param codeIndex The offset of the ISO 4217 code.
   * @return The mention, or {@code null} when no known code and number follow.
   */
  private MoneyAmount isoFirst(CharSequence text, int start, int codeIndex) {
    final String code = isoCodeAt(text, codeIndex);
    if (code == null || NumberScan.charAt(text, codeIndex + 3) != ' ') {
      return null;
    }
    return mention(text, start, NumberScan.parse(text, codeIndex + 4, true, notation),
        code, false);
  }

  /**
   * Matches {@code 100 USD}, {@code 50\u20AC} (euro sign), {@code 3.5m USD}, and
   * {@code 1.2 million dollars}.
   *
   * @param text The text being scanned.
   * @param start The offset the mention starts at, the minus sign included.
   * @param digitIndex The offset of the first digit.
   * @param negative {@code true} if a minus sign opens the mention.
   * @return The mention, or {@code null} when no currency marker follows the number.
   */
  private MoneyAmount numberFirst(CharSequence text, int start, int digitIndex,
      boolean negative) {
    final NumberScan.Result number = NumberScan.parse(text, digitIndex, true, notation);
    if (number == null) {
      return null;
    }
    final int cp = NumberScan.codePointAt(text, number.end());
    final String currency = currencyFor(cp);
    if (currency != null) {
      final NumberScan.Result extended = new NumberScan.Result(number.value(),
          number.end() + Character.charCount(cp));
      return mention(text, start, extended, currency, negative);
    }
    if (cp == ' ') {
      final String code = isoCodeAt(text, number.end() + 1);
      if (code != null) {
        return mention(text, start,
            new NumberScan.Result(number.value(), number.end() + 4), code, negative);
      }
      final CurrencyWord word = currencyWordAt(text, number.end() + 1);
      if (word != null) {
        return mention(text, start,
            new NumberScan.Result(number.value(), word.end()), word.currency(), negative);
      }
    }
    return null;
  }

  /**
   * Reads a spelled-out currency word at a position, as in the {@code dollars} of
   * {@code 1.2 million dollars}. The scale word, if any, was consumed with the number,
   * so the currency word is the token directly behind it.
   *
   * <p>A hyphen directly behind the word makes it the first element of a compound such as
   * {@code euro-cent} or {@code dollar-denominated}, which names no amount, so the
   * candidate is rejected there as it is when the word runs into further letters or
   * digits.</p>
   *
   * @param text The text being scanned.
   * @param start The offset of the first letter of the candidate word.
   * @return The word, or {@code null} when the letters at {@code start} name no currency
   *         or are part of a longer token.
   */
  private CurrencyWord currencyWordAt(CharSequence text, int start) {
    final int end = NumberScan.wordEnd(text, start, MAX_CURRENCY_WORD_LENGTH);
    if (end < 0 || NumberScan.charAt(text, end) == '-') {
      return null;
    }
    final String currency = CURRENCY_WORDS.get(StringUtil.toLowerCase(text.subSequence(start, end)));
    return currency == null ? null : new CurrencyWord(currency, end);
  }

  /**
   * Builds a mention from a scanned number, rejecting it when the number is missing or
   * does not end at a boundary.
   *
   * @param text The text being scanned.
   * @param start The offset the mention starts at.
   * @param number The scanned number, or {@code null} when the scan failed.
   * @param currency The ISO 4217 code of the mention.
   * @param negative {@code true} if a minus sign opens the mention.
   * @return The mention, or {@code null} when it is not a complete match.
   */
  private MoneyAmount mention(CharSequence text, int start, NumberScan.Result number,
      String currency, boolean negative) {
    if (number == null || !NumberScan.boundaryAfter(text, number.end())) {
      return null;
    }
    final BigDecimal amount = negative ? number.value().negate() : number.value();
    return new MoneyAmount(new Span(start, number.end()), amount, currency);
  }

  /**
   * Reads a known ISO 4217 code at a position.
   *
   * @param text The text being scanned.
   * @param start The offset of the first letter of the candidate code.
   * @return The code, or {@code null} when the three letters at {@code start} are no
   *         known code or are part of a longer run.
   */
  private String isoCodeAt(CharSequence text, int start) {
    if (start + 3 > text.length() || !NumberScan.boundaryBefore(text, start)) {
      return null;
    }
    for (int i = start; i < start + 3; i++) {
      if (!isUpperAscii(text.charAt(i))) {
        return null;
      }
    }
    if (!NumberScan.boundaryAfter(text, start + 3)) {
      return null;
    }
    final String code = text.subSequence(start, start + 3).toString();
    return ISO_CODES.contains(code) ? code : null;
  }

  /**
   * Resolves the currency a symbol code point denotes.
   *
   * @param cp The code point to look up; {@link NumberScan#NO_CODE_POINT} matches no
   *           symbol.
   * @return The ISO 4217 code the symbol denotes, or {@code null} when {@code cp} is no
   *         known currency symbol.
   */
  private String currencyFor(int cp) {
    final int index = Arrays.binarySearch(symbolCodePoints, cp);
    return index < 0 ? null : currencyCodes[index];
  }

  /**
   * @param cp The code point to classify.
   * @return {@code true} if {@code cp} is an ASCII capital letter.
   */
  private boolean isUpperAscii(int cp) {
    return cp >= 'A' && cp <= 'Z';
  }

  /**
   * Checks whether the preceding base character is an ASCII letter, skipping its marks. A
   * known currency symbol in that context is the tail of a multi-character symbol the
   * table does not know, such as {@code HK$}, and must not match as the bare symbol.
   *
   * @param text The text being scanned. Must not be {@code null}.
   * @param index The offset of the candidate symbol.
   * @return {@code true} if an ASCII letter, with optional combining marks, precedes the symbol.
   */
  private boolean asciiLetterBefore(CharSequence text, int index) {
    final int cp = NumberScan.baseCodePointBefore(text, index);
    return isUpperAscii(cp) || (cp >= 'a' && cp <= 'z');
  }

  /**
   * An intermediate parse result: the currency a spelled-out word names and the exclusive
   * end offset behind it.
   */
  private record CurrencyWord(String currency, int end) {
  }

  /**
   * @return The alphabetic codes of every currency the JDK knows. Never {@code null}.
   */
  private static Set<String> isoCodes() {
    final Set<String> codes = new HashSet<>();
    for (final Currency currency : Currency.getAvailableCurrencies()) {
      codes.add(currency.getCurrencyCode());
    }
    return Collections.unmodifiableSet(codes);
  }
}
