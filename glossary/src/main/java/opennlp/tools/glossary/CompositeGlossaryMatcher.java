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

import java.util.ArrayList;
import java.util.List;

import opennlp.tools.util.Span;

/**
 * Combines glossary matchers into a non-overlapping result for a single
 * {@link GlossaryAnnotator}.
 *
 * <p>List order determines priority. A match is accepted only if it does not
 * overlap an accepted match. For example, place {@link AhoCorasickGlossaryMatcher}
 * before {@link TermAnalyzingGlossaryMatcher} to prefer exact matches to inflected
 * matches. Overlap uses {@link Span#intersects(Span)}: adjacent non-empty spans
 * do not overlap, but a zero-length span intersects a span containing that offset,
 * including its end offset. Results are returned in text order.</p>
 *
 * <p>Delegate results satisfy the {@link GlossaryMatcher} contract: they are sorted
 * and non-overlapping. The composite filters and merges each result in a linear pass.
 * Calls share no mutable matching state. Concurrent use requires thread-safe
 * delegates.</p>
 *
 * @since 3.0.0
 */
public final class CompositeGlossaryMatcher implements GlossaryMatcher {

  /** The delegates in priority order; earlier matchers win on overlap. */
  private final List<GlossaryMatcher> matchers;

  /**
   * Builds a composite over delegate matchers.
   *
   * @param matchers The delegates in priority order, highest first. Must not be
   *                 {@code null} or empty, and no element may be {@code null}.
   *                 The list is copied.
   * @throws IllegalArgumentException Thrown if {@code matchers} is {@code null},
   *         empty, or contains {@code null}.
   */
  public CompositeGlossaryMatcher(List<GlossaryMatcher> matchers) {
    if (matchers == null || matchers.isEmpty()) {
      throw new IllegalArgumentException("matchers must not be null or empty");
    }
    for (final GlossaryMatcher matcher : matchers) {
      if (matcher == null) {
        throw new IllegalArgumentException("matchers must not contain null elements");
      }
    }
    this.matchers = List.copyOf(matchers);
  }

  /**
   * {@inheritDoc} Delegate list order determines priority for overlapping matches.
   */
  @Override
  public List<GlossaryMatch> match(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    List<GlossaryMatch> accepted = new ArrayList<>();
    for (final GlossaryMatcher matcher : matchers) {
      final List<GlossaryMatch> candidates = matcher.match(text);
      if (!candidates.isEmpty()) {
        final List<GlossaryMatch> additions = filterOverlaps(accepted, candidates);
        if (!additions.isEmpty()) {
          accepted = accepted.isEmpty() ? additions : mergeByStart(accepted, additions);
        }
      }
    }
    return accepted;
  }

  /**
   * Removes candidates that intersect a higher-priority hit or an earlier candidate.
   * Both inputs are in text order, so only the next accepted hit and the last retained
   * candidate can intersect the current candidate.
   *
   * @param accepted The higher-priority hits already retained.
   * @param candidates The next delegate's hits in text order.
   * @return The candidates that do not intersect an accepted or earlier candidate hit.
   */
  private List<GlossaryMatch> filterOverlaps(List<GlossaryMatch> accepted,
      List<GlossaryMatch> candidates) {
    final List<GlossaryMatch> additions = new ArrayList<>(candidates.size());
    int acceptedIndex = 0;
    for (final GlossaryMatch candidate : candidates) {
      while (acceptedIndex < accepted.size()
          && accepted.get(acceptedIndex).span().getEnd() <= candidate.span().getStart()
          && !accepted.get(acceptedIndex).span().intersects(candidate.span())) {
        acceptedIndex++;
      }
      final boolean intersectsAccepted = acceptedIndex < accepted.size()
          && accepted.get(acceptedIndex).span().intersects(candidate.span());
      final boolean intersectsEarlierCandidate = !additions.isEmpty()
          && additions.get(additions.size() - 1).span().intersects(candidate.span());
      if (!intersectsAccepted && !intersectsEarlierCandidate) {
        additions.add(candidate);
      }
    }
    return additions;
  }

  /**
   * Merges two text-ordered, non-overlapping hit lists. On equal starts, an accepted
   * higher-priority hit remains first.
   *
   * @param accepted The higher-priority hits.
   * @param additions The disjoint hits accepted from the next delegate.
   * @return All hits in text order.
   */
  private List<GlossaryMatch> mergeByStart(List<GlossaryMatch> accepted,
      List<GlossaryMatch> additions) {
    final List<GlossaryMatch> merged = new ArrayList<>(accepted.size() + additions.size());
    int acceptedIndex = 0;
    int additionIndex = 0;
    while (acceptedIndex < accepted.size() && additionIndex < additions.size()) {
      final GlossaryMatch acceptedHit = accepted.get(acceptedIndex);
      final GlossaryMatch addition = additions.get(additionIndex);
      if (acceptedHit.span().getStart() <= addition.span().getStart()) {
        merged.add(acceptedHit);
        acceptedIndex++;
      } else {
        merged.add(addition);
        additionIndex++;
      }
    }
    while (acceptedIndex < accepted.size()) {
      merged.add(accepted.get(acceptedIndex++));
    }
    while (additionIndex < additions.size()) {
      merged.add(additions.get(additionIndex++));
    }
    return merged;
  }
}
