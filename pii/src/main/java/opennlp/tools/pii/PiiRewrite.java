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
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import opennlp.tools.document.Annotation;
import opennlp.tools.util.Span;

/**
 * Replacement text, label spans and a mapping from original to output offsets.
 *
 * <p>Replacement labels can change text length. Use {@link #mapOffset(int)},
 * {@link #mapSpan(Span)} or {@link #remap(List)} to map original annotations to the
 * output text. {@link Masker} instead preserves text length.</p>
 *
 * <p>Produced by {@link Pseudonymizer} and {@link HmacTokenizer}. Instances are immutable
 * and safe to share between threads.</p>
 *
 * @since 3.0.0
 */
public final class PiiRewrite {

  private final String text;
  private final List<PiiMention> mentions;
  private final int[] originalStarts;
  private final int[] originalEnds;
  private final int[] rewrittenStarts;
  private final int[] rewrittenEnds;
  private final int[] cumulativeShifts;
  private final int originalLength;

  /**
   * Initializes an immutable rewrite result from output text and offset tables.
   *
   * @param text The output text.
   * @param mentions The output mentions.
   * @param originalStarts Original start offsets.
   * @param originalEnds Original exclusive end offsets.
   * @param rewrittenStarts Output start offsets.
   * @param rewrittenEnds Output exclusive end offsets.
   * @param cumulativeShifts Offset shift after each mention.
   * @param originalLength The original text length.
   */
  private PiiRewrite(String text, List<PiiMention> mentions, int[] originalStarts,
      int[] originalEnds, int[] rewrittenStarts, int[] rewrittenEnds,
      int[] cumulativeShifts, int originalLength) {
    this.text = text;
    this.mentions = mentions;
    this.originalStarts = originalStarts;
    this.originalEnds = originalEnds;
    this.rewrittenStarts = rewrittenStarts;
    this.rewrittenEnds = rewrittenEnds;
    this.cumulativeShifts = cumulativeShifts;
    this.originalLength = originalLength;
  }

  /**
   * Replaces each mention with the label assigned by the labeler.
   *
   * @param text The original text. Must not be {@code null}.
   * @param mentions The mentions to replace. Must not be {@code null} or contain
   *                 {@code null}. All spans must be within {@code text} and must not
   *                 overlap. Order does not matter.
   * @param labeler Assigns the replacement for a mention. Must not be {@code null} and
   *                must not return {@code null} or an empty label.
   * @return The non-null rewrite result.
   * @throws IllegalArgumentException Thrown if an argument is {@code null}, a mention is
   *         {@code null}, a span lies outside the text, spans overlap, or the labeler
   *         returns {@code null} or an empty label.
   */
  static PiiRewrite replace(CharSequence text, List<PiiMention> mentions,
      Function<PiiMention, String> labeler) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    if (mentions == null) {
      throw new IllegalArgumentException("mentions must not be null");
    }
    if (labeler == null) {
      throw new IllegalArgumentException("labeler must not be null");
    }
    final List<PiiMention> ordered = ordered(text, mentions);
    final int count = ordered.size();
    final int[] originalStarts = new int[count];
    final int[] originalEnds = new int[count];
    final int[] rewrittenStarts = new int[count];
    final int[] rewrittenEnds = new int[count];
    final int[] cumulativeShifts = new int[count];
    final List<PiiMention> labelled = new ArrayList<>(count);
    final StringBuilder rewritten = new StringBuilder(text.length());
    int copied = 0;
    for (int i = 0; i < count; i++) {
      final PiiMention mention = ordered.get(i);
      final int start = mention.span().getStart();
      final int end = mention.span().getEnd();
      final String label = labeler.apply(mention);
      if (label == null || label.isEmpty()) {
        throw new IllegalArgumentException("labeler must not return a null or empty label");
      }
      rewritten.append(text, copied, start);
      originalStarts[i] = start;
      originalEnds[i] = end;
      rewrittenStarts[i] = rewritten.length();
      rewritten.append(label);
      rewrittenEnds[i] = rewritten.length();
      final int previousShift = i == 0 ? 0 : cumulativeShifts[i - 1];
      cumulativeShifts[i] = previousShift
          + (rewrittenEnds[i] - rewrittenStarts[i]) - (end - start);
      labelled.add(new PiiMention(new Span(rewrittenStarts[i], rewrittenEnds[i]),
          mention.type(), label));
      copied = end;
    }
    rewritten.append(text, copied, text.length());
    return new PiiRewrite(rewritten.toString(), Collections.unmodifiableList(labelled),
        originalStarts, originalEnds, rewrittenStarts, rewrittenEnds, cumulativeShifts,
        text.length());
  }

  /**
   * Validates, copies, and orders mentions for a left-to-right rewrite.
   *
   * @param text The source text.
   * @param mentions The mentions to validate and order.
   * @return A mutable copy sorted by start offset.
   * @throws IllegalArgumentException If a mention is null, outside the text or overlaps
   *         another mention.
   */
  private static List<PiiMention> ordered(CharSequence text, List<PiiMention> mentions) {
    final List<PiiMention> ordered = new ArrayList<>(mentions.size());
    for (final PiiMention mention : mentions) {
      if (mention == null) {
        throw new IllegalArgumentException("mentions must not contain null");
      }
      if (mention.span().getStart() < 0 || mention.span().getEnd() > text.length()) {
        throw new IllegalArgumentException("span lies outside the text: " + mention.span());
      }
      ordered.add(mention);
    }
    ordered.sort(Comparator.comparingInt(mention -> mention.span().getStart()));
    for (int i = 1; i < ordered.size(); i++) {
      if (ordered.get(i).span().getStart() < ordered.get(i - 1).span().getEnd()) {
        throw new IllegalArgumentException("mentions must not overlap: "
            + ordered.get(i - 1).span() + " and " + ordered.get(i).span());
      }
    }
    return ordered;
  }

  /**
   * Returns the output text.
   *
   * @return The non-null text with mentions replaced by labels.
   */
  public String text() {
    return text;
  }

  /**
   * Returns replacement labels with output spans, in text order. The
   * {@link PiiMention#normalized() normalized value} is the replacement label.
   * Use this list to build a {@link PiiAnnotator#PII} layer on the output text.
   *
   * @return The non-null, immutable label list.
   */
  public List<PiiMention> mentions() {
    return mentions;
  }

  /**
   * Maps an original-text offset to an output offset.
   *
   * <p>Offsets outside replacements include cumulative length shifts. An offset inside
   * a replacement maps to the label start.</p>
   *
   * @param offset The offset in the original text. Must be between {@code 0} and the
   *               length of the original text.
   * @return The offset in {@link #text()}.
   * @throws IndexOutOfBoundsException Thrown if {@code offset} is outside the original
   *         text.
   */
  public int mapOffset(int offset) {
    return map(offset, true);
  }

  /**
   * Maps an original-text span to an output span.
   *
   * <p>A non-empty span that intersects a replaced value includes the complete label.
   * Offsets outside replacements include cumulative length shifts. An empty span remains
   * empty at the position returned by {@link #mapOffset(int)}.</p>
   *
   * @param span The span in the original text. Must not be {@code null} and must lie
   *             within the original text.
   * @return The non-null matching span of {@link #text()}.
   * @throws IllegalArgumentException Thrown if {@code span} is {@code null}.
   * @throws IndexOutOfBoundsException Thrown if {@code span} lies outside the original
   *         text.
   */
  public Span mapSpan(Span span) {
    if (span == null) {
      throw new IllegalArgumentException("span must not be null");
    }
    final int start = map(span.getStart(), true);
    final int end = span.length() == 0 ? start : Math.max(start, map(span.getEnd(), false));
    return new Span(start, end, span.getType(), span.getProb());
  }

  /**
   * Maps annotation spans to the output text without changing annotation values.
   *
   * <p>A non-empty annotation that intersects a replacement includes the complete label.
   * Empty annotations are omitted. Values are unchanged, including any text or offsets
   * stored in them. Use {@link #mentions()} to build a PII layer of replacement labels.</p>
   *
   * @param annotations Positional annotations of the original text. The list, annotations
   *                    and their spans must be non-null.
   * @param <T> The annotation value type.
   * @return The non-null list of annotations with mapped spans, in the given order.
   * @throws IllegalArgumentException Thrown if {@code annotations}, an annotation, or an
   *         annotation's span is null.
   * @throws IndexOutOfBoundsException Thrown if an annotation lies outside the original
   *         text.
   */
  public <T> List<Annotation<T>> remap(List<Annotation<T>> annotations) {
    if (annotations == null) {
      throw new IllegalArgumentException("annotations must not be null");
    }
    final List<Annotation<T>> mapped = new ArrayList<>(annotations.size());
    for (final Annotation<T> annotation : annotations) {
      if (annotation == null) {
        throw new IllegalArgumentException("annotations must not contain null");
      }
      final Span span = mapSpan(annotation.span());
      if (span.length() > 0) {
        mapped.add(new Annotation<>(span, annotation.value()));
      }
    }
    return mapped;
  }

  /**
   * Maps one source offset, choosing a replacement edge when it falls inside a replaced
   * span.
   *
   * @param offset The source offset.
   * @param towardsStart Whether an interior offset maps to the label start; false selects
   *                     the exclusive end.
   * @return The output offset.
   * @throws IndexOutOfBoundsException If the offset is outside the original text.
   */
  private int map(int offset, boolean towardsStart) {
    if (offset < 0 || offset > originalLength) {
      throw new IndexOutOfBoundsException("offset out of range: " + offset);
    }
    int low = 0;
    int high = originalStarts.length;
    while (low < high) {
      final int middle = (low + high) >>> 1;
      if (originalStarts[middle] < offset) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    final int preceding = low - 1;
    if (preceding < 0) {
      return offset;
    }
    if (offset < originalEnds[preceding]) {
      return towardsStart ? rewrittenStarts[preceding] : rewrittenEnds[preceding];
    }
    return offset + cumulativeShifts[preceding];
  }
}
