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

package opennlp.tools.glossary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import opennlp.tools.tokenize.uax29.WordSegmenter;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;
import opennlp.tools.util.normalizer.AlignedText;
import opennlp.tools.util.normalizer.OffsetAwareNormalizer;

/**
 * Finds glossary terms with an Aho-Corasick automaton and returns non-overlapping
 * spans in the original text.
 *
 * <p>By default, terms match literally, character for character, so a multiword term
 * matches only with the exact separator characters it was registered with. An optional
 * {@link OffsetAwareNormalizer} folds terms and text before the automaton runs (for
 * example German umlaut expansion, full case folding, whitespace collapse, or dash
 * folding). Hits found in the normalized form are mapped back to original-text spans
 * through the normalizer's {@link AlignedText}. Hits are constrained to the default
 * word boundaries of UAX&#160;#29 on the original text: {@code cat} does not match
 * inside {@code concatenate}, nor {@code can} inside {@code can't}. Han
 * ideographs and hiragana receive character-granularity boundaries, while katakana
 * and Hangul runs stay joined. The default Unicode rules are locale-neutral and do
 * not perform the dictionary or morphological segmentation some scripts require.
 * Overlapping hits are resolved leftmost first, then longest, then by registration
 * order.</p>
 *
 * <p>When the matcher ignores case, terms and text are compared through the
 * per-code-point UnicodeData lowercase mapping, the same mapping
 * {@link opennlp.tools.util.StringUtil#toLowerCase(CharSequence)} applies, which keeps
 * source offsets unchanged but does not apply locale or multi-character case folding.
 * Use an {@link OffsetAwareNormalizer} for expansions such as sharp s to {@code ss}.
 * Word boundaries and case comparison operate on Unicode code points.</p>
 *
 * <p>The automaton is built at construction. Calls share no mutable matching state.
 * Concurrent use requires a thread-safe normalizer, if configured.</p>
 *
 * @see <a href="https://doi.org/10.1145/360825.360855">Aho, Corasick (1975): Efficient
 *      string matching: An aid to bibliographic search</a>
 * @see <a href="https://www.unicode.org/reports/tr29/">Unicode Standard Annex #29:
 *      Unicode Text Segmentation</a>
 * @since 3.0.0
 */
public final class AhoCorasickGlossaryMatcher implements GlossaryMatcher {

  /** The index of the automaton's root state, from which every scan starts. */
  private static final int ROOT = 0;

  /** The registered entries in registration order; raw hits index into this list. */
  private final List<GlossaryEntry> entries;

  /**
   * The term length in chars of each entry as stored in the automaton, aligned with
   * {@link #entries} by index. A hit start in scan coordinates is the hit end minus this
   * length. When a normalizer is present, the length is that of the normalized pattern.
   */
  private final int[] termLengths;

  /** Whether terms and text are compared through per-code-point lowercasing. */
  private final boolean ignoreCase;

  /**
   * Optional fold applied to terms at build time and to text at match time. {@code null}
   * means identity: the automaton scans the original characters.
   */
  private final OffsetAwareNormalizer normalizer;

  /**
   * The goto function: per state, the sorted code points with an outgoing edge; the
   * target of each edge is in {@link #edgeTargets} at the same index.
   */
  private final int[][] edgeCodePoints;

  /** The goto targets, aligned with {@link #edgeCodePoints} per state. */
  private final int[][] edgeTargets;

  /** The failure function: per state, the state to fall back to on a code point miss. */
  private final int[] fail;

  /** Per state, the indexes into {@link #entries} of the terms that end at that state. */
  private final int[][] outputs;

  /**
   * Builds the automaton for a glossary with no additional normalizer.
   *
   * @param glossary The entries to match. Must not be {@code null} or empty, and no
   *                 entry may be {@code null}. When two entries share the same term, the
   *                 one registered first wins.
   * @param ignoreCase Whether to match terms regardless of character case.
   * @throws IllegalArgumentException Thrown if {@code glossary} is {@code null}, empty,
   *         or contains {@code null}.
   */
  public AhoCorasickGlossaryMatcher(Collection<GlossaryEntry> glossary, boolean ignoreCase) {
    this(glossary, ignoreCase, null, false);
  }

  /**
   * Builds the automaton for a glossary, folding terms and text through an
   * {@link OffsetAwareNormalizer} before matching.
   *
   * <p>Each term is normalized before it is inserted into the automaton, and each
   * {@link #match(CharSequence)} call normalizes the text, scans the normalized form,
   * and maps hit spans back to the original text. A term that becomes blank after
   * normalization is rejected. When two entries normalize to the same pattern, the one
   * registered first wins. Stemming and lemmatization are out of scope here; use
   * {@link TermAnalyzingGlossaryMatcher} for token-level inflection.</p>
   *
   * @param glossary The entries to match. Must not be {@code null} or empty, and no
   *                 entry may be {@code null}.
   * @param ignoreCase Whether to match terms regardless of character case after the
   *                   normalizer fold.
   * @param normalizer The offset-aware fold to apply. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code glossary} is {@code null}, empty,
   *         or contains {@code null}, if {@code normalizer} is {@code null}, or if a term
   *         normalizes to blank.
   */
  public AhoCorasickGlossaryMatcher(Collection<GlossaryEntry> glossary, boolean ignoreCase,
      OffsetAwareNormalizer normalizer) {
    this(glossary, ignoreCase, normalizer, true);
  }

  /**
   * Shared constructor. When {@code requireNormalizer} is {@code true}, {@code normalizer}
   * must be non-null; otherwise {@code null} means the identity (two-argument) path.
   *
   * @param glossary The entries to match.
   * @param ignoreCase Whether to match terms regardless of character case.
   * @param normalizer The offset-aware fold, or {@code null} for identity matching.
   * @param requireNormalizer Whether to reject a {@code null} normalizer.
   * @throws IllegalArgumentException Thrown for an invalid glossary, a required but absent
   *         normalizer, or a term that normalizes to blank.
   */
  private AhoCorasickGlossaryMatcher(Collection<GlossaryEntry> glossary, boolean ignoreCase,
      OffsetAwareNormalizer normalizer, boolean requireNormalizer) {
    if (glossary == null || glossary.isEmpty()) {
      throw new IllegalArgumentException("glossary must not be null or empty");
    }
    if (requireNormalizer && normalizer == null) {
      throw new IllegalArgumentException("normalizer must not be null");
    }
    for (final GlossaryEntry entry : glossary) {
      if (entry == null) {
        throw new IllegalArgumentException("glossary must not contain null entries");
      }
    }
    this.entries = List.copyOf(glossary);
    this.ignoreCase = ignoreCase;
    this.normalizer = normalizer;
    this.termLengths = new int[entries.size()];

    final List<Map<Integer, Integer>> transitions = new ArrayList<>();
    final List<List<Integer>> nodeOutputs = new ArrayList<>();
    transitions.add(new HashMap<>());
    nodeOutputs.add(new ArrayList<>());

    for (int pattern = 0; pattern < entries.size(); pattern++) {
      final String term = normalizePattern(entries.get(pattern).term());
      termLengths[pattern] = term.length();
      int state = ROOT;
      for (int i = 0; i < term.length(); ) {
        final int codePoint = term.codePointAt(i);
        i += Character.charCount(codePoint);
        final int normalized = normalize(codePoint);
        Integer next = transitions.get(state).get(normalized);
        if (next == null) {
          next = transitions.size();
          transitions.add(new HashMap<>());
          nodeOutputs.add(new ArrayList<>());
          transitions.get(state).put(normalized, next);
        }
        state = next;
      }
      nodeOutputs.get(state).add(pattern);
    }

    this.edgeCodePoints = new int[transitions.size()][];
    this.edgeTargets = new int[transitions.size()][];
    for (int state = 0; state < transitions.size(); state++) {
      final Map<Integer, Integer> edges = transitions.get(state);
      final int[] codePoints = new int[edges.size()];
      int e = 0;
      for (final int codePoint : edges.keySet()) {
        codePoints[e++] = codePoint;
      }
      Arrays.sort(codePoints);
      final int[] targets = new int[codePoints.length];
      for (int k = 0; k < codePoints.length; k++) {
        targets[k] = edges.get(codePoints[k]);
      }
      edgeCodePoints[state] = codePoints;
      edgeTargets[state] = targets;
    }

    this.fail = new int[transitions.size()];
    final Deque<Integer> queue = new ArrayDeque<>();
    for (final int child : edgeTargets[ROOT]) {
      fail[child] = ROOT;
      queue.add(child);
    }
    while (!queue.isEmpty()) {
      final int state = queue.remove();
      nodeOutputs.get(state).addAll(nodeOutputs.get(fail[state]));
      for (int e = 0; e < edgeCodePoints[state].length; e++) {
        final int child = edgeTargets[state][e];
        fail[child] = step(fail[state], edgeCodePoints[state][e]);
        queue.add(child);
      }
    }

    this.outputs = new int[transitions.size()][];
    for (int state = 0; state < transitions.size(); state++) {
      final List<Integer> patterns = nodeOutputs.get(state);
      outputs[state] = new int[patterns.size()];
      for (int i = 0; i < patterns.size(); i++) {
        outputs[state][i] = patterns.get(i);
      }
    }
  }

  /**
   * {@inheritDoc} Word boundaries are checked in the original text after offset mapping.
   */
  @Override
  public List<GlossaryMatch> match(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    final CharSequence scanText;
    final AlignedText aligned;
    if (normalizer != null) {
      aligned = normalizer.normalizeAligned(text);
      scanText = aligned.normalized();
    } else {
      aligned = null;
      scanText = text;
    }
    final List<int[]> hits = new ArrayList<>();
    BitSet wordBoundaries = null;
    int state = ROOT;
    for (int i = 0; i < scanText.length(); ) {
      final int codePoint = Character.codePointAt(scanText, i);
      i += Character.charCount(codePoint);
      state = step(state, normalize(codePoint));
      for (final int pattern : outputs[state]) {
        final int normalizedStart = i - termLengths[pattern];
        final int normalizedEnd = i;
        final int start;
        final int end;
        if (aligned != null) {
          final Span original = aligned.toOriginalSpan(normalizedStart, normalizedEnd);
          start = original.getStart();
          end = original.getEnd();
        } else {
          start = normalizedStart;
          end = normalizedEnd;
        }
        final boolean spaceDelimited = isAsciiSpaceDelimited(text, start, end);
        final boolean obviousInterior = !spaceDelimited
            && (isObviousWordInterior(text, start) || isObviousWordInterior(text, end));
        final boolean obviousBoundaries = spaceDelimited || (!obviousInterior
            && isObviousWordBoundary(text, start) && isObviousWordBoundary(text, end));
        if (!obviousInterior && !obviousBoundaries && wordBoundaries == null) {
          wordBoundaries = wordBoundaries(text);
        }
        if (!obviousInterior
            && (obviousBoundaries || onWordBoundary(wordBoundaries, start, end))) {
          hits.add(new int[] {start, end, pattern});
        }
      }
    }
    return resolveOverlaps(hits);
  }

  /**
   * Folds one glossary term for automaton insertion.
   *
   * @param term The registered surface form.
   * @return The pattern stored in the automaton.
   * @throws IllegalArgumentException Thrown if the normalized pattern is blank.
   */
  private String normalizePattern(String term) {
    final String pattern = normalizer == null
        ? term
        : normalizer.normalize(term).toString();
    if (StringUtil.isBlank(pattern)) {
      throw new IllegalArgumentException(
          "glossary term must not normalize to blank: \"" + term + "\"");
    }
    return pattern;
  }

  /**
   * Advances the automaton by one code point, following failure links on a miss.
   *
   * @param state The current state.
   * @param codePoint The normalized input code point.
   * @return The next state.
   */
  private int step(int state, int codePoint) {
    int next = target(state, codePoint);
    while (next < 0 && state != ROOT) {
      state = fail[state];
      next = target(state, codePoint);
    }
    return next < 0 ? ROOT : next;
  }

  /**
   * Looks up one goto edge.
   *
   * @param state The state to leave.
   * @param codePoint The normalized code point to follow.
   * @return The target state, or {@code -1} when the state has no such edge.
   */
  private int target(int state, int codePoint) {
    final int index = Arrays.binarySearch(edgeCodePoints[state], codePoint);
    return index >= 0 ? edgeTargets[state][index] : -1;
  }

  /**
   * Normalizes one code point for comparison, with the per-code-point UnicodeData
   * lowercase mapping of {@link Character#toLowerCase(int)}.
   *
   * @param codePoint The code point.
   * @return The lowercased code point when case is ignored, otherwise the code point.
   */
  private int normalize(int codePoint) {
    return ignoreCase ? Character.toLowerCase(codePoint) : codePoint;
  }

  /**
   * Indexes UAX&#160;#29 boundaries when the ASCII checks cannot determine a match boundary.
   *
   * @param text The original text being scanned.
   * @return The boundary offsets, including zero and the text length.
   */
  private BitSet wordBoundaries(CharSequence text) {
    final BitSet boundaries = new BitSet(text.length() + 1);
    boundaries.set(0);
    WordSegmenter.forEachSegment(text, (start, end) -> boundaries.set(end));
    return boundaries;
  }

  /**
   * Checks for ASCII word characters bounded by spaces or the text limits.
   *
   * @param text The original text being scanned.
   * @param start The candidate start, inclusive.
   * @param end The candidate end, exclusive.
   * @return {@code true} if both edges are confirmed UAX&#160;#29 boundaries.
   */
  private boolean isAsciiSpaceDelimited(CharSequence text, int start, int end) {
    if (start == end) {
      return false;
    }
    final boolean startBoundary = start == 0
        || (text.charAt(start - 1) == ' ' && isAsciiWordCharacter(text.charAt(start)));
    final boolean endBoundary = end == text.length()
        || (text.charAt(end) == ' ' && isAsciiWordCharacter(text.charAt(end - 1)));
    return startBoundary && endBoundary;
  }

  /**
   * Checks text limits and ASCII word boundaries at spaces or terminal punctuation.
   * Unrecognized combinations require the complete segmenter.
   *
   * @param text The original text being scanned.
   * @param offset The candidate boundary offset.
   * @return {@code true} if the offset is a confirmed word boundary.
   */
  private boolean isObviousWordBoundary(CharSequence text, int offset) {
    if (offset == 0 || offset == text.length()) {
      return true;
    }
    final char left = text.charAt(offset - 1);
    final char right = text.charAt(offset);
    return (left == ' ' && isAsciiWordCharacter(right))
        || (right == ' ' && isAsciiWordCharacter(left))
        || (isAsciiWordCharacter(left) && isTerminalAsciiMidWord(text, offset))
        || (isAsciiWordCharacter(right) && isInitialAsciiMidWord(text, offset));
  }

  /**
   * Recognizes mid-word punctuation followed by a space or the end of text. Without a following
   * word character, UAX&#160;#29 does not join the punctuation to the word on its left.
   *
   * @param text The original text being scanned.
   * @param offset The offset immediately before the punctuation.
   * @return {@code true} if terminal punctuation makes {@code offset} a boundary.
   */
  private boolean isTerminalAsciiMidWord(CharSequence text, int offset) {
    final char punctuation = text.charAt(offset);
    return isAsciiMidWordPunctuation(punctuation)
        && (offset + 1 == text.length() || text.charAt(offset + 1) == ' ');
  }

  /**
   * Recognizes mid-word punctuation preceded by a space or the start of text. Without a preceding
   * word character, UAX&#160;#29 does not join the punctuation to the word on its right.
   *
   * @param text The original text being scanned.
   * @param offset The offset immediately after the punctuation.
   * @return {@code true} if initial punctuation makes {@code offset} a boundary.
   */
  private boolean isInitialAsciiMidWord(CharSequence text, int offset) {
    final char punctuation = text.charAt(offset - 1);
    return isAsciiMidWordPunctuation(punctuation)
        && (offset == 1 || text.charAt(offset - 2) == ' ');
  }

  /**
   * Checks the ASCII punctuation that can join letters or numbers only when a word character
   * occurs on both sides.
   *
   * @param value The character to classify.
   * @return {@code true} for full stop, comma, or colon.
   */
  private boolean isAsciiMidWordPunctuation(char value) {
    return value == '.' || value == ',' || value == ':';
  }

  /**
   * Checks for adjacent ASCII word characters joined under UAX&#160;#29.
   *
   * @param text The original text being scanned.
   * @param offset The candidate boundary offset.
   * @return {@code true} if the offset is inside an ASCII word.
   */
  private boolean isObviousWordInterior(CharSequence text, int offset) {
    return offset > 0 && offset < text.length()
        && isAsciiWordCharacter(text.charAt(offset - 1))
        && isAsciiWordCharacter(text.charAt(offset));
  }

  /**
   * Checks the ASCII classes that UAX&#160;#29 joins into words.
   *
   * @param value The character to classify.
   * @return {@code true} for an ASCII letter, digit, or connector underscore.
   */
  private boolean isAsciiWordCharacter(char value) {
    return value >= 'A' && value <= 'Z'
        || value >= 'a' && value <= 'z'
        || value >= '0' && value <= '9'
        || value == '_';
  }

  /**
   * Checks whether both edges of a candidate hit are UAX&#160;#29 word boundaries.
   *
   * @param boundaries The indexed boundary offsets for the original text.
   * @param start The hit start, inclusive.
   * @param end The hit end, exclusive.
   * @return {@code true} if both hit edges are word boundaries.
   */
  private boolean onWordBoundary(BitSet boundaries, int start, int end) {
    return boundaries.get(start) && boundaries.get(end);
  }

  /**
   * Resolves overlapping hits leftmost first, then longest, then by registration order.
   *
   * @param hits The raw hits as {@code {start, end, pattern}} triples.
   * @return The accepted matches in text order.
   */
  private List<GlossaryMatch> resolveOverlaps(List<int[]> hits) {
    hits.sort((a, b) -> {
      if (a[0] != b[0]) {
        return Integer.compare(a[0], b[0]);
      }
      if (a[1] != b[1]) {
        return Integer.compare(b[1], a[1]);
      }
      return Integer.compare(a[2], b[2]);
    });
    return GlossaryMatchSelection.select(hits, entries);
  }
}
