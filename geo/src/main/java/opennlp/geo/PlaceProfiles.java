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

package opennlp.geo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.StringUtil;

/**
 * Compares places by cosine similarity of standardized numeric metrics, such as
 * population density and median income. Place names and surrounding text are not used.
 *
 * <p>The table is tab-separated: a header line with {@code id} followed by metric
 * names, then one place per line with an identifier and one value per metric.
 * Identifiers and metric names must be unique. No metric data is bundled; users
 * are responsible for the licenses of their sources. Columns are standardized to
 * mean zero and unit population variance at load. Constant columns contribute zero.</p>
 *
 * <p>Values are parsed as finite doubles. Load-time calculations use decimal arithmetic
 * over those double values, with exact centering and 34-digit division and square roots.
 * Profiles are rescaled before conversion to doubles for queries. This supports columns
 * spanning the finite double range without overflowing their statistics.</p>
 *
 * <p>Instances are immutable and safe to share between threads.</p>
 *
 * @since 3.0.0
 */
public final class PlaceProfiles {

  /**
   * A place identifier and similarity score for a query.
   *
   * @param id The place identifier. Must not be {@code null} or empty.
   * @param similarity The finite cosine similarity in {@code [-1, 1]}.
   */
  public record Neighbor(String id, double similarity) {

    /**
     * Validates a similarity result.
     *
     * @throws IllegalArgumentException Thrown if the identifier is missing or the score
     *         is non-finite or outside {@code [-1, 1]}.
     */
    public Neighbor {
      if (id == null || id.isEmpty()) {
        throw new IllegalArgumentException("id must not be null or empty");
      }
      if (!Double.isFinite(similarity) || similarity < -1.0 || similarity > 1.0) {
        throw new IllegalArgumentException("similarity must be finite and in [-1, 1]");
      }
    }
  }

  /** The header cell naming the identifier column. */
  private static final String ID_COLUMN = "id";

  /** The prefix of the failure naming a cell that is not usable table data. */
  private static final String MALFORMED_VALUE = "malformed value in row ";

  /** Precision for load-time division and square roots. */
  private static final MathContext STATISTICS_PRECISION = MathContext.DECIMAL128;

  private final Map<String, double[]> profiles;
  private final List<String> metrics;

  /**
   * Stores privately owned profile arrays and immutable metric names.
   *
   * @param profiles The normalized profile arrays.
   * @param metrics The ordered metric names.
   */
  private PlaceProfiles(Map<String, double[]> profiles, List<String> metrics) {
    this.profiles = profiles;
    this.metrics = metrics;
  }

  /**
   * Loads a profile table.
   *
   * @param table The tab-separated table, UTF-8: a header with {@code id} and metric
   *              names, then one place per line. Must not be {@code null}.
   * @return The loaded profiles.
   * @throws IOException Thrown if reading fails.
   * @throws InvalidFormatException Thrown if the content is not a valid profile table
   *         as described by {@link #load(InputStream)}.
   * @throws IllegalArgumentException Thrown if {@code table} is {@code null}.
   */
  public static PlaceProfiles load(Path table) throws IOException {
    if (table == null) {
      throw new IllegalArgumentException("table must not be null");
    }
    try (InputStream in = Files.newInputStream(table)) {
      return load(in);
    }
  }

  /**
   * Loads a UTF-8 table from a stream. Lines may end with LF, CRLF, or CR. Blank lines
   * and lines starting with {@code #} after surrounding whitespace is removed are
   * ignored, including before the header. The first data line must contain {@code id}
   * and at least one metric name, separated by tabs.
   *
   * <p>Cells are stripped of surrounding toolkit-defined whitespace. Place identifiers
   * and metric names must be non-empty and unique. Data lines must contain one finite
   * decimal value per metric; exponent notation is accepted, but hexadecimal numbers
   * and Java type suffixes are not.</p>
   *
   * @param tableStream The table content. Must not be {@code null}. Not closed.
   * @return The loaded profiles.
   * @throws IOException Thrown if reading fails.
   * @throws InvalidFormatException Thrown if UTF-8 decoding fails, the header or a data
   *         line is invalid, or the table has no places.
   * @throws IllegalArgumentException Thrown if {@code tableStream} is {@code null}.
   */
  public static PlaceProfiles load(InputStream tableStream) throws IOException {
    if (tableStream == null) {
      throw new IllegalArgumentException("tableStream must not be null");
    }
    try {
      return readTable(GazetteerIndex.utf8Reader(tableStream));
    } catch (CharacterCodingException e) {
      throw new InvalidFormatException("profile table is not valid UTF-8", e);
    }
  }

  /**
   * Parses a table without closing the reader.
   *
   * @param reader The UTF-8 reader.
   * @return Standardized profiles.
   * @throws IOException Thrown if reading fails.
   * @throws InvalidFormatException Thrown if the table is malformed.
   */
  private static PlaceProfiles readTable(BufferedReader reader) throws IOException {
    String line;
    int row = 0;
    do {
      line = reader.readLine();
      row++;
      if (line == null) {
        throw new InvalidFormatException("the profile table has no header");
      }
    } while (isIgnored(line));
    final String[] header = GazetteerIndex.split(line, '\t');
    if (header.length < 2 || !ID_COLUMN.equals(strip(header[0]))) {
      throw new InvalidFormatException("the header must be: id, then at least one metric");
    }
    final int width = header.length - 1;
    final List<String> strippedNames = new ArrayList<>(width);
    final Set<String> uniqueNames = new HashSet<>();
    for (int m = 1; m < header.length; m++) {
      final String name = strip(header[m]);
      if (name.isEmpty()) {
        throw new InvalidFormatException("empty metric name in header column " + (m + 1));
      }
      if (!uniqueNames.add(name)) {
        throw new InvalidFormatException(
            "duplicate metric name in header column " + (m + 1) + ": " + name);
      }
      strippedNames.add(name);
    }
    final List<String> metricNames = List.copyOf(strippedNames);
    final Map<String, double[]> raw = new HashMap<>();
    while ((line = reader.readLine()) != null) {
      row++;
      if (isIgnored(line)) {
        continue;
      }
      final String[] fields = GazetteerIndex.split(line, '\t');
      if (fields.length != header.length) {
        throw new InvalidFormatException("row " + row + " has " + fields.length
            + " fields, expected " + header.length);
      }
      final String id = strip(fields[0]);
      if (id.isEmpty()) {
        throw new InvalidFormatException("empty id in row " + row);
      }
      if (raw.containsKey(id)) {
        throw new InvalidFormatException("duplicate id in row " + row + ": " + id);
      }
      final double[] profile = new double[width];
      for (int m = 0; m < width; m++) {
        final String value = strip(fields[m + 1]);
        if (value.isEmpty()) {
          throw new InvalidFormatException("empty value in row " + row + ", column "
              + (m + 2) + " (" + metricNames.get(m) + ")");
        }
        profile[m] = metricValue(value, row);
      }
      raw.put(id, profile);
    }
    if (raw.isEmpty()) {
      throw new InvalidFormatException("the profile table lists no places");
    }
    standardize(raw, width);
    return new PlaceProfiles(Map.copyOf(raw), metricNames);
  }

  /**
   * Parses one metric cell, accepting only finite numbers.
   *
   * @param text The cell content, already stripped of surrounding whitespace.
   * @param row The one-based line number of the row, for the failure message.
   * @return The parsed value, always finite.
   * @throws InvalidFormatException Thrown if the cell is not a number, has a Java literal
   *         suffix, or is not finite.
   */
  private static double metricValue(String text, int row) throws InvalidFormatException {
    if (hasJavaLiteralSuffix(text) || hasHexMarker(text)) {
      throw new InvalidFormatException(MALFORMED_VALUE + row + ": " + text);
    }
    final double value;
    try {
      value = Double.parseDouble(text);
    } catch (NumberFormatException e) {
      throw new InvalidFormatException(MALFORMED_VALUE + row + ": " + text, e);
    }
    if (!Double.isFinite(value)) {
      throw new InvalidFormatException("non-finite value in row " + row + ": " + text);
    }
    return value;
  }

  /**
   * Checks for a Java float or double type suffix.
   *
   * @param text The cell content, already stripped of surrounding whitespace.
   * @return {@code true} if the final character is {@code f}, {@code F}, {@code d} or
   *         {@code D}.
   */
  private static boolean hasJavaLiteralSuffix(String text) {
    if (text.isEmpty()) {
      return false;
    }
    final char last = text.charAt(text.length() - 1);
    return last == 'f' || last == 'F' || last == 'd' || last == 'D';
  }

  /**
   * Checks for a hexadecimal number marker.
   *
   * @param text The cell content, already stripped of surrounding whitespace.
   * @return {@code true} if the cell contains {@code x} or {@code X}.
   */
  private static boolean hasHexMarker(String text) {
    return text.indexOf('x') >= 0 || text.indexOf('X') >= 0;
  }

  /**
   * Checks for a blank line or comment.
   *
   * @param line The raw line, without its line ending.
   * @return {@code true} if the line must be skipped.
   */
  private static boolean isIgnored(String line) {
    final String stripped = strip(line);
    return stripped.isEmpty() || stripped.charAt(0) == '#';
  }

  /**
   * Removes leading and trailing whitespace using the toolkit's definition.
   *
   * @param text The text to strip.
   * @return The text without surrounding whitespace. Never {@code null}.
   */
  private static String strip(String text) {
    int start = 0;
    int end = text.length();
    while (start < end && StringUtil.isWhitespace(text.charAt(start))) {
      start++;
    }
    while (end > start && StringUtil.isWhitespace(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(start, end);
  }

  /**
   * Standardizes metric columns and rescales each profile for double-precision cosine.
   * Centering uses {@code count * value - sum} to avoid rounding the column mean.
   * Constant columns become zero. Per-profile rescaling does not change cosine.
   *
   * @param profiles The raw profiles, keyed by place identifier; mutated in place.
   * @param width The number of metric columns.
   */
  private static void standardize(Map<String, double[]> profiles, int width) {
    final List<double[]> rows = new ArrayList<>(profiles.values());
    final BigDecimal count = BigDecimal.valueOf(rows.size());
    final BigDecimal[][] standardized = new BigDecimal[rows.size()][width];
    for (int m = 0; m < width; m++) {
      BigDecimal sum = BigDecimal.ZERO;
      for (int row = 0; row < rows.size(); row++) {
        standardized[row][m] = new BigDecimal(rows.get(row)[m]);
        sum = sum.add(standardized[row][m]);
      }
      BigDecimal squares = BigDecimal.ZERO;
      for (final BigDecimal[] profile : standardized) {
        profile[m] = profile[m].multiply(count).subtract(sum);
        squares = squares.add(profile[m].multiply(profile[m]));
      }
      final BigDecimal deviation = squares.divide(count, STATISTICS_PRECISION)
          .sqrt(STATISTICS_PRECISION);
      for (final BigDecimal[] profile : standardized) {
        profile[m] = deviation.signum() == 0 ? BigDecimal.ZERO
            : profile[m].divide(deviation, STATISTICS_PRECISION);
      }
    }
    for (int row = 0; row < rows.size(); row++) {
      BigDecimal scale = BigDecimal.ZERO;
      for (final BigDecimal value : standardized[row]) {
        scale = scale.max(value.abs());
      }
      for (int m = 0; m < width; m++) {
        rows.get(row)[m] = scale.signum() == 0 ? 0.0
            : standardized[row][m].divide(scale, STATISTICS_PRECISION).doubleValue();
      }
    }
  }

  /**
   * Lists metric names in column order.
   *
   * @return The immutable list of metric names in column order.
   */
  public List<String> metrics() {
    return metrics;
  }

  /**
   * Checks whether a place is profiled.
   *
   * @param id The place identifier. Must not be {@code null}.
   * @return {@code true} if the table lists the place.
   * @throws IllegalArgumentException Thrown if {@code id} is {@code null}.
   */
  public boolean contains(String id) {
    if (id == null) {
      throw new IllegalArgumentException("id must not be null");
    }
    return profiles.containsKey(id);
  }

  /**
   * Compares two places by the cosine of their standardized profiles.
   *
   * @param id The first place identifier. Must not be {@code null} and must be listed.
   * @param otherId The second place identifier. Must not be {@code null} and must be
   *                listed.
   * @return The finite cosine similarity in {@code [-1, 1]}, or zero when either
   *         profile equals the column means for all metrics.
   * @throws IllegalArgumentException Thrown if an identifier is {@code null} or not
   *         listed.
   */
  public double similarity(String id, String otherId) {
    if (id == null) {
      throw new IllegalArgumentException("id must not be null");
    }
    if (otherId == null) {
      throw new IllegalArgumentException("otherId must not be null");
    }
    return cosine(profile(id), profile(otherId));
  }

  /**
   * Finds the places most similar to one place.
   *
   * <p>Equal scores are ordered by ascending identifier, including at the result limit.</p>
   *
   * @param id The query place identifier. Must not be {@code null} and must be listed.
   * @param count The maximum number of neighbors. Must be positive.
   * @return At most {@code count} results, excluding the query place, ordered by
   *         descending similarity and then ascending identifier. The list is immutable.
   * @throws IllegalArgumentException Thrown if {@code id} is {@code null} or not
   *         listed, or {@code count} is not positive.
   */
  public List<Neighbor> mostSimilar(String id, int count) {
    if (id == null) {
      throw new IllegalArgumentException("id must not be null");
    }
    if (count <= 0) {
      throw new IllegalArgumentException("count must be positive: " + count);
    }
    final double[] query = profile(id);
    final List<Neighbor> neighbors = new ArrayList<>();
    for (final Map.Entry<String, double[]> candidate : profiles.entrySet()) {
      if (!candidate.getKey().equals(id)) {
        neighbors.add(new Neighbor(candidate.getKey(),
            cosine(query, candidate.getValue())));
      }
    }
    neighbors.sort(Comparator.comparingDouble(Neighbor::similarity).reversed()
        .thenComparing(Neighbor::id));
    return List.copyOf(neighbors.subList(0, Math.min(count, neighbors.size())));
  }

  /**
   * Looks up a standardized profile for a non-null identifier.
   *
   * @param id The place identifier to resolve.
   * @return The standardized profile.
   * @throws IllegalArgumentException Thrown if {@code id} is not listed.
   */
  private double[] profile(String id) {
    final double[] profile = profiles.get(id);
    if (profile == null) {
      throw new IllegalArgumentException("the table does not list place: " + id);
    }
    return profile;
  }

  /**
   * Computes cosine for profiles rescaled to a largest absolute component of one.
   * Zero profiles return zero. Rounding at either endpoint is limited to [-1, 1].
   *
   * @param a The first vector.
   * @param b The second vector, of the same length as {@code a}.
   * @return The cosine, or zero when either vector is zero.
   */
  private double cosine(double[] a, double[] b) {
    double dot = 0.0;
    double normA = 0.0;
    double normB = 0.0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      normA += a[i] * a[i];
      normB += b[i] * b[i];
    }
    if (normA == 0.0 || normB == 0.0) {
      return 0.0;
    }
    return Math.max(-1.0, Math.min(1.0, dot / (Math.sqrt(normA) * Math.sqrt(normB))));
  }
}
