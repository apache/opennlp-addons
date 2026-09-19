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

package opennlp.tools.formats.conllu;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import opennlp.tools.coref.CorefAnnotator;
import opennlp.tools.coref.CorefMention;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;
import opennlp.tools.util.InputStreamFactory;
import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

/**
 * Reads documents with gold coreference chains from
 * <a href="https://universaldependencies.org/format.html">CoNLL-U</a> with the {@code Entity}
 * attribute of the MISC column, the encoding of the Universal Anaphora and OntoGUM
 * releases: {@code (3} opens a mention of entity 3 on the token, {@code 3)} closes the
 * innermost open mention of that entity, and {@code (3)} does both. A rich id such as
 * {@code 3-person-new-...} counts by the part before its first hyphen, and each part of a
 * discontinuous mention such as {@code (e5[1/2]-person} is read as a mention of {@code e5}.
 *
 * <p>Each {@code # newdoc} comment starts a document; a file without one is a single
 * document. The document text is rebuilt from the word forms, separated by single
 * spaces except after a token marked {@code SpaceAfter=No}, with sentences separated by
 * newlines. Every document carries {@link Layers#SENTENCES}, {@link Layers#TOKENS},
 * {@link Layers#POS_TAGS} from the chosen tag set, {@link CorefAnnotator#GOLD_CHAINS}
 * with one chain per entity id in order of first mention, and, when {@code # speaker}
 * comments are present, {@link CorefAnnotator#SPEAKERS} over each such sentence.
 * Multiword token ranges and empty nodes are skipped.</p>
 *
 * @since 3.0.0
 */
public class ConlluCorefDocumentStream implements ObjectStream<Document> {

  private static final String NEWDOC = "# newdoc";
  private static final String SPEAKER = "# speaker =";
  private static final String ENTITY = "Entity";
  private static final String SPACE_AFTER = "SpaceAfter";

  private final InputStreamFactory in;
  private final ConlluTagset tagset;
  private BufferedReader reader;
  private String pendingLine;

  /**
   * Initializes the stream.
   *
   * @param in The CoNLL-U input. Must not be {@code null}.
   * @param tagset Which tag column fills the POS layer. Must not be {@code null}.
   * @throws IOException Thrown if the input cannot be opened.
   * @throws IllegalArgumentException Thrown if an argument is {@code null}.
   */
  public ConlluCorefDocumentStream(InputStreamFactory in, ConlluTagset tagset)
      throws IOException {
    if (in == null) {
      throw new IllegalArgumentException("in must not be null");
    }
    if (tagset == null) {
      throw new IllegalArgumentException("tagset must not be null");
    }
    this.in = in;
    this.tagset = tagset;
    reset();
  }

  /** {@inheritDoc} Returns {@code null} once the input holds no further document. */
  @Override
  public Document read() throws IOException {
    while (true) {
      final List<String> lines = new ArrayList<>();
      String line = pendingLine != null ? pendingLine : reader.readLine();
      pendingLine = null;
      while (line != null) {
        if (line.startsWith(NEWDOC) && !lines.isEmpty()) {
          pendingLine = line;
          break;
        }
        lines.add(line);
        line = reader.readLine();
      }
      for (final String candidate : lines) {
        if (!candidate.isEmpty() && candidate.charAt(0) != '#') {
          return parse(lines);
        }
      }
      if (pendingLine == null) {
        return null;
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public void reset() throws IOException {
    close();
    reader = new BufferedReader(new InputStreamReader(in.createInputStream(),
        StandardCharsets.UTF_8));
    pendingLine = null;
  }

  /** {@inheritDoc} */
  @Override
  public void close() throws IOException {
    if (reader != null) {
      reader.close();
      reader = null;
    }
  }

  /**
   * Builds one document from its CoNLL-U lines.
   *
   * @param lines The document lines.
   * @return The parsed document.
   * @throws InvalidFormatException Thrown if a row or entity annotation is malformed.
   */
  private Document parse(List<String> lines) throws InvalidFormatException {
    final StringBuilder text = new StringBuilder();
    final List<Annotation<String>> sentences = new ArrayList<>();
    final List<Annotation<String>> tokens = new ArrayList<>();
    final List<Annotation<String>> tags = new ArrayList<>();
    final List<Annotation<String>> speakers = new ArrayList<>();
    final Map<String, List<Integer>> openStarts = new HashMap<>();
    final Map<String, List<Span>> entities = new LinkedHashMap<>();
    String speaker = null;
    int sentenceStart = -1;
    int expectedWordId = 1;
    boolean glue = false;
    for (final String line : lines) {
      if (line.isEmpty()) {
        if (sentenceStart >= 0) {
          rejectOpenMentions(openStarts, " crosses a sentence boundary");
          sentences.add(new Annotation<>(new Span(sentenceStart, text.length()),
              text.substring(sentenceStart)));
          if (speaker != null) {
            speakers.add(new Annotation<>(new Span(sentenceStart, text.length()), speaker));
          }
          sentenceStart = -1;
          speaker = null;
        }
        expectedWordId = 1;
        continue;
      }
      if (line.charAt(0) == '#') {
        if (line.startsWith(SPEAKER)) {
          speaker = line.substring(SPEAKER.length()).trim();
          if (StringUtil.isBlank(speaker)) {
            throw new InvalidFormatException("speaker label must not be blank");
          }
        }
        continue;
      }
      final String[] fields = fields(line);
      if (fields.length != 10) {
        throw new InvalidFormatException("expected 10 columns: " + line);
      }
      for (int f = 0; f < fields.length; f++) {
        if (StringUtil.isBlank(fields[f])) {
          throw new InvalidFormatException(
              "column " + (f + 1) + " must not be blank: " + line);
        }
      }
      if (!plainId(fields[0])) {
        continue;
      }
      if (!fields[0].equals(Integer.toString(expectedWordId))) {
        throw new InvalidFormatException(
            "expected token id " + expectedWordId + " but found " + fields[0]);
      }
      expectedWordId++;
      if (sentenceStart < 0) {
        if (text.length() > 0) {
          text.append('\n');
        }
        sentenceStart = text.length();
        glue = true;
      }
      if (!glue) {
        text.append(' ');
      }
      final int start = text.length();
      text.append(fields[1]);
      final int end = text.length();
      tokens.add(new Annotation<>(new Span(start, end), fields[1]));
      tags.add(new Annotation<>(new Span(start, end),
          tagset == ConlluTagset.U ? fields[3] : fields[4]));
      glue = "No".equals(misc(fields[9], SPACE_AFTER));
      final String entity = misc(fields[9], ENTITY);
      if (entity != null) {
        brackets(entity, start, end, openStarts, entities);
      }
    }
    if (sentenceStart >= 0) {
      sentences.add(new Annotation<>(new Span(sentenceStart, text.length()),
          text.substring(sentenceStart)));
      if (speaker != null) {
        speakers.add(new Annotation<>(new Span(sentenceStart, text.length()), speaker));
      }
    }
    rejectOpenMentions(openStarts, " is never closed");
    Document document = Document.of(text.toString())
        .with(Layers.SENTENCES, sentences)
        .with(Layers.TOKENS, tokens)
        .with(Layers.POS_TAGS, tags)
        .with(CorefAnnotator.GOLD_CHAINS, chains(entities));
    if (!speakers.isEmpty()) {
      document = document.with(CorefAnnotator.SPEAKERS, speakers);
    }
    return document;
  }

  /**
   * Converts source entity groups to the gold-chain layer.
   *
   * @param entities The mention spans grouped by source entity id.
   * @return The gold chain layer in text order, numbered by first mention.
   */
  private List<Annotation<CorefMention>> chains(Map<String, List<Span>> entities) {
    final List<Annotation<CorefMention>> layer = new ArrayList<>();
    int chain = 0;
    for (final List<Span> mentions : entities.values()) {
      for (final Span mention : mentions) {
        layer.add(new Annotation<>(mention,
            new CorefMention(chain, CorefMention.KIND_GOLD, CorefMention.NO_ENTITY)));
      }
      chain++;
    }
    layer.sort((a, b) -> a.span().getStart() != b.span().getStart()
        ? Integer.compare(a.span().getStart(), b.span().getStart())
        : Integer.compare(b.span().getEnd(), a.span().getEnd()));
    return layer;
  }

  /**
   * Splits a CoNLL-U row into columns.
   *
   * @param line The CoNLL-U row.
   * @return All tab-separated columns, including empty columns.
   */
  private String[] fields(String line) {
    final List<String> fields = new ArrayList<>(10);
    int start = 0;
    for (int i = 0; i <= line.length(); i++) {
      if (i == line.length() || line.charAt(i) == '\t') {
        fields.add(line.substring(start, i));
        start = i + 1;
      }
    }
    return fields.toArray(new String[0]);
  }

  /**
   * Returns whether an id denotes a word, while accepting the valid multiword-range and
   * empty-node forms that this reader skips.
   *
   * @param id The token id.
   * @return Whether the id denotes a word row.
   * @throws InvalidFormatException Thrown if the id has none of the valid forms.
   */
  private boolean plainId(String id) throws InvalidFormatException {
    if (positiveInteger(id)) {
      return true;
    }
    if (compoundId(id, '-') || compoundId(id, '.')) {
      return false;
    }
    throw new InvalidFormatException("invalid token id: " + id);
  }

  /**
   * Checks whether text is a positive decimal integer.
   *
   * @param value The text to inspect.
   * @return Whether it is a positive decimal integer without leading zeroes.
   */
  private boolean positiveInteger(String value) {
    if (value.isEmpty() || value.charAt(0) < '1' || value.charAt(0) > '9') {
      return false;
    }
    for (int i = 1; i < value.length(); i++) {
      if (value.charAt(i) < '0' || value.charAt(i) > '9') {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks whether a token id has a compound form.
   *
   * @param id The token id.
   * @param separator The range or empty-node separator.
   * @return Whether the id has a valid compound form with that separator.
   */
  private boolean compoundId(String id, char separator) {
    final int split = id.indexOf(separator);
    if (split <= 0 || id.indexOf(separator, split + 1) >= 0) {
      return false;
    }
    final boolean validPrefix = separator == '.'
        ? nonnegativeInteger(id.substring(0, split))
        : positiveInteger(id.substring(0, split));
    final String suffix = id.substring(split + 1);
    if (!validPrefix || !positiveInteger(suffix)) {
      return false;
    }
    return separator == '.' || lessThan(id.substring(0, split), suffix);
  }

  /**
   * Checks whether text is a nonnegative decimal integer.
   *
   * @param value The text to inspect.
   * @return Whether it is a nonnegative decimal integer without leading zeroes.
   */
  private boolean nonnegativeInteger(String value) {
    return "0".equals(value) || positiveInteger(value);
  }

  /**
   * Compares two nonnegative decimal integers without converting them.
   *
   * @param first The first nonnegative decimal integer.
   * @param second The second nonnegative decimal integer.
   * @return Whether the first value is less than the second without numeric conversion.
   */
  private boolean lessThan(String first, String second) {
    return first.length() != second.length()
        ? first.length() < second.length()
        : first.compareTo(second) < 0;
  }

  /**
   * Reads an exact attribute from the MISC column.
   *
   * @param misc The MISC column.
   * @param key The exact attribute key.
   * @return The attribute value, or {@code null}.
   */
  private String misc(String misc, String key) {
    int start = 0;
    while (start < misc.length()) {
      int end = misc.indexOf('|', start);
      if (end < 0) {
        end = misc.length();
      }
      final String attribute = misc.substring(start, end);
      if (attribute.startsWith(key) && attribute.length() > key.length()
          && attribute.charAt(key.length()) == '=') {
        return attribute.substring(key.length() + 1);
      }
      start = end + 1;
    }
    return null;
  }

  /**
   * Applies one token's bracket notation.
   *
   * @param entity The Entity attribute value.
   * @param start The token start offset.
   * @param end The token end offset.
   * @param openStarts The open mention starts by entity id.
   * @param entities The completed mention spans by entity id.
   * @throws InvalidFormatException Thrown if the bracket notation is malformed.
   */
  private void brackets(String entity, int start, int end,
      Map<String, List<Integer>> openStarts, Map<String, List<Span>> entities)
      throws InvalidFormatException {
    if (entity.isEmpty()) {
      throw new InvalidFormatException("entity id must not be empty");
    }
    int i = 0;
    while (i < entity.length()) {
      if (entity.charAt(i) == '(') {
        int j = i + 1;
        while (j < entity.length() && entity.charAt(j) != '(' && entity.charAt(j) != ')') {
          j++;
        }
        final String id = canonicalId(entity.substring(i + 1, j));
        if (j < entity.length() && entity.charAt(j) == ')') {
          entities.computeIfAbsent(id, key -> new ArrayList<>()).add(new Span(start, end));
          j++;
        } else {
          entities.computeIfAbsent(id, key -> new ArrayList<>());
          openStarts.computeIfAbsent(id, key -> new ArrayList<>()).add(start);
        }
        i = j;
      } else {
        int j = i;
        while (j < entity.length() && entity.charAt(j) != ')') {
          j++;
        }
        if (j == entity.length()) {
          throw new InvalidFormatException("entity close is missing ')': " + entity);
        }
        final String id = canonicalId(entity.substring(i, j));
        final List<Integer> starts = openStarts.get(id);
        if (starts == null || starts.isEmpty()) {
          throw new InvalidFormatException("entity " + id + " is closed but not open");
        }
        entities.computeIfAbsent(id, key -> new ArrayList<>())
            .add(new Span(starts.remove(starts.size() - 1), end));
        i = j + 1;
      }
    }
  }

  /**
   * Rejects an entity mention still open at a boundary.
   *
   * @param openStarts The open mention starts by entity id.
   * @param reason The boundary-specific error suffix.
   * @throws InvalidFormatException Thrown if any mention remains open.
   */
  private void rejectOpenMentions(Map<String, List<Integer>> openStarts, String reason)
      throws InvalidFormatException {
    for (final Map.Entry<String, List<Integer>> open : openStarts.entrySet()) {
      if (!open.getValue().isEmpty()) {
        throw new InvalidFormatException("entity " + open.getKey() + reason);
      }
    }
  }

  /**
   * Strips the attribute tail of a rich entity id and the part marker of a
   * discontinuous mention, so {@code e5[1/2]-person} and {@code e5[2/2]} are entity
   * {@code e5} and each part becomes a mention of it.
   *
   * @param id The raw entity id.
   * @return The canonical entity id.
   * @throws InvalidFormatException Thrown if the canonical id is empty.
   */
  private String canonicalId(String id) throws InvalidFormatException {
    int end = id.length();
    for (int i = 0; i < id.length(); i++) {
      if (id.charAt(i) == '-' || id.charAt(i) == '[') {
        end = i;
        break;
      }
    }
    if (end == 0) {
      throw new InvalidFormatException("entity id must not be empty");
    }
    return id.substring(0, end);
  }
}
