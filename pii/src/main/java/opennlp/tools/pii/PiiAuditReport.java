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

package opennlp.tools.pii;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import opennlp.tools.document.Document;

/**
 * Reports mention counts, distinct-value counts and sampled HMAC tokens by type.
 *
 * <p>The report stores types, counts and tokens, without normalized values or offsets.
 * Custom types should be labels, not personal data, because they appear in the report.
 * Samples use {@link HmacTokenizer#token(PiiMention)} for the retained mentions,
 * including overlaps. Counts include each retained detection.
 * They remain linkable pseudonymous data and require access control.</p>
 *
 * <p>{@link #toString()} escapes backslashes and uses UTF-16 hexadecimal escapes for
 * colons, commas, square brackets, control characters, Unicode formatting characters,
 * line and paragraph separators, and unpaired surrogates. Accessors return the original
 * types and tokens.</p>
 *
 * <p>Instances are immutable and safe to share between threads.</p>
 *
 * @since 3.0.0
 */
public final class PiiAuditReport {

  /** Default number of sampled distinct values per type. */
  private static final int DEFAULT_SAMPLES = 3;

  private final Map<String, Integer> counts;
  private final Map<String, Integer> distinctCounts;
  private final Map<String, List<String>> samples;
  private final int total;

  /**
   * Initializes a report from immutable maps.
   *
   * @param counts Mention counts by type.
   * @param distinctCounts Distinct normalized-value counts by type.
   * @param samples Immutable token lists by type.
   * @param total The total mention count.
   */
  private PiiAuditReport(Map<String, Integer> counts, Map<String, Integer> distinctCounts,
      Map<String, List<String>> samples, int total) {
    this.counts = counts;
    this.distinctCounts = distinctCounts;
    this.samples = samples;
    this.total = total;
  }

  /**
   * Reports on a list of mentions.
   *
   * @param mentions The mentions to report on. Must not be {@code null} or contain
   *                 {@code null}; may be empty.
   * @param tokenizer Creates tokens for distinct values. Must not be {@code null}.
   * @return The non-null report.
   * @throws IllegalArgumentException Thrown if an argument or a mention is {@code null},
   *         or a sampled mention's type or normalized value contains an unpaired surrogate.
   */
  public static PiiAuditReport of(List<PiiMention> mentions, HmacTokenizer tokenizer) {
    return of(mentions, tokenizer, DEFAULT_SAMPLES);
  }

  /**
   * Reports on a list of mentions, naming at most {@code samplesPerType} distinct values of
   * each type.
   *
   * @param mentions The mentions to report on. Must not be {@code null} or contain
   *                 {@code null}; may be empty.
   * @param tokenizer Creates tokens for distinct values. Must not be {@code null}.
   * @param samplesPerType How many tokens to keep per type. Must not be negative; zero
   *                       reports counts only.
   * @return The non-null report.
   * @throws IllegalArgumentException Thrown if an argument is {@code null}, a mention is
   *         {@code null}, {@code samplesPerType} is negative, or a sampled mention's type
   *         or normalized value contains an unpaired surrogate.
   */
  public static PiiAuditReport of(List<PiiMention> mentions, HmacTokenizer tokenizer,
      int samplesPerType) {
    if (mentions == null) {
      throw new IllegalArgumentException("mentions must not be null");
    }
    if (tokenizer == null) {
      throw new IllegalArgumentException("tokenizer must not be null");
    }
    if (samplesPerType < 0) {
      throw new IllegalArgumentException("samplesPerType must not be negative");
    }
    final Map<String, Integer> counts = new TreeMap<>();
    final Map<String, Set<String>> distinct = new TreeMap<>();
    final Map<String, List<String>> samples = new TreeMap<>();
    int total = 0;
    for (final PiiMention mention : mentions) {
      if (mention == null) {
        throw new IllegalArgumentException("mentions must not contain null");
      }
      final String type = mention.type();
      counts.merge(type, 1, Integer::sum);
      total++;
      final boolean unseen = distinct.computeIfAbsent(type, ignored -> new LinkedHashSet<>())
          .add(mention.normalized());
      if (unseen) {
        final List<String> kept = samples.computeIfAbsent(type, ignored -> new ArrayList<>());
        if (kept.size() < samplesPerType) {
          kept.add(tokenizer.token(mention));
        }
      }
    }
    final Map<String, Integer> distinctCounts = new TreeMap<>();
    final Map<String, List<String>> sampleView = new LinkedHashMap<>();
    for (final Map.Entry<String, Set<String>> entry : distinct.entrySet()) {
      distinctCounts.put(entry.getKey(), entry.getValue().size());
      sampleView.put(entry.getKey(), List.copyOf(
          samples.getOrDefault(entry.getKey(), List.of())));
    }
    return new PiiAuditReport(Collections.unmodifiableMap(counts),
        Collections.unmodifiableMap(distinctCounts),
        Collections.unmodifiableMap(sampleView), total);
  }

  /**
   * Reports on a document's {@link PiiAnnotator#PII} layer.
   *
   * @param document The document to report on. Must be non-null and have a
   *                 {@link PiiAnnotator#PII} layer with matching annotation and mention
   *                 offsets.
   * @param tokenizer Creates tokens for distinct values. Must not be {@code null}.
   * @return The non-null report.
   * @throws IllegalArgumentException Thrown if an argument is null, the document lacks
   *         the PII layer, a mention and annotation have different offsets, or a
   *         sampled mention's type or normalized value contains an unpaired surrogate.
   */
  public static PiiAuditReport of(Document document, HmacTokenizer tokenizer) {
    return of(PiiLayer.mentions(document), tokenizer);
  }

  /**
   * Returns how many mentions of each type were found.
   *
   * @return The non-null, immutable counts in type order. Types with no matches are
   *         not included.
   */
  public Map<String, Integer> counts() {
    return counts;
  }

  /**
   * Returns the distinct-value count per type, comparing exact normalized forms.
   *
   * @return The non-null, immutable distinct-value counts in type order.
   */
  public Map<String, Integer> distinctCounts() {
    return distinctCounts;
  }

  /**
   * Returns the types that were found.
   *
   * @return The non-null, immutable types in type order.
   */
  public Set<String> types() {
    return counts.keySet();
  }

  /**
   * Returns the total number of mentions.
   *
   * @return The total, {@code 0} for a text without PII.
   */
  public int total() {
    return total;
  }

  /**
   * Returns the kept tokens for one type, in the order the values were first seen.
   *
   * @param type The mention type. Must not be {@code null}.
   * @return The non-null, immutable token list, limited by the configured sample count.
   *         Empty for a type with no matches.
   * @throws IllegalArgumentException Thrown if {@code type} is {@code null}.
   */
  public List<String> samples(String type) {
    if (type == null) {
      throw new IllegalArgumentException("type must not be null");
    }
    return samples.getOrDefault(type, List.of());
  }

  /**
   * {@inheritDoc}
   *
   * <p>Formats one line per type with escaped type names and token samples.</p>
   *
   * @return The formatted report, ending without a line separator.
   */
  @Override
  public String toString() {
    if (total == 0) {
      return "no pii found";
    }
    final StringBuilder out = new StringBuilder();
    for (final Map.Entry<String, Integer> entry : counts.entrySet()) {
      final String type = entry.getKey();
      if (out.length() > 0) {
        out.append(System.lineSeparator());
      }
      appendEscaped(type, out);
      out.append(": ").append(entry.getValue()).append(" mentions, ")
          .append(distinctCounts.get(type)).append(" distinct");
      final List<String> kept = samples(type);
      if (!kept.isEmpty()) {
        out.append(" [");
        for (int i = 0; i < kept.size(); i++) {
          if (i > 0) {
            out.append(", ");
          }
          appendEscaped(kept.get(i), out);
        }
        out.append(']');
      }
    }
    return out.toString();
  }

  /**
   * Appends text with report delimiters, controls and formatting characters escaped.
   *
   * @param value The text to display.
   * @param out The report output.
   */
  private void appendEscaped(String value, StringBuilder out) {
    for (int offset = 0; offset < value.length();) {
      final int codePoint = value.codePointAt(offset);
      if (codePoint == '\\') {
        out.append("\\\\");
      } else if (codePoint == ':' || codePoint == ',' || codePoint == '[' || codePoint == ']') {
        appendUnicodeEscape((char) codePoint, out);
      } else {
        switch (Character.getType(codePoint)) {
          case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
               Character.PARAGRAPH_SEPARATOR, Character.SURROGATE -> {
            if (Character.isSupplementaryCodePoint(codePoint)) {
              appendUnicodeEscape(Character.highSurrogate(codePoint), out);
              appendUnicodeEscape(Character.lowSurrogate(codePoint), out);
            } else {
              appendUnicodeEscape((char) codePoint, out);
            }
          }
          default -> out.appendCodePoint(codePoint);
        }
      }
      offset += Character.charCount(codePoint);
    }
  }

  /**
   * Appends a UTF-16 code unit as a 4-digit hexadecimal escape.
   *
   * @param unit The code unit to display.
   * @param out The report output.
   */
  private void appendUnicodeEscape(char unit, StringBuilder out) {
    out.append("\\u");
    for (int shift = 12; shift >= 0; shift -= 4) {
      out.append(Character.forDigit((unit >> shift) & 0xf, 16));
    }
  }
}
