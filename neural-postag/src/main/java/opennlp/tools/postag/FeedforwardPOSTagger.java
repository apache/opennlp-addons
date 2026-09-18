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

package opennlp.tools.postag;

import opennlp.tools.util.Sequence;

/**
 * A neural {@link POSTagger}: a greedy left-to-right decoder over the
 * {@link FeedforwardPOSModel}, feeding each position the two previously assigned tags.
 *
 * <p>{@link #topKSequences(String[])} returns only the greedy sequence. Parsers using
 * this tagger receive one tagging candidate per sentence, without alternatives.</p>
 *
 * <p>The {@code additionalContext} arguments are ignored.</p>
 *
 * <p>Per-call state is local. A trained model and tagger can be shared between threads.</p>
 *
 * @see FeedforwardPOSTrainer
 * @since 3.0.0
 */
public class FeedforwardPOSTagger implements POSTagger {

  private final FeedforwardPOSModel model;
  private final String[] tags;

  /**
   * Initializes a {@link FeedforwardPOSTagger}.
   *
   * @param model The model to tag with. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code model} is {@code null}.
   */
  public FeedforwardPOSTagger(FeedforwardPOSModel model) {
    if (model == null) {
      throw new IllegalArgumentException("model must not be null");
    }
    this.model = model;
    this.tags = model.tags();
    model.enableScoringCache();
  }

  /**
   * {@inheritDoc}
   *
   * @throws IllegalArgumentException Thrown if {@code sentence} is {@code null}.
   * @throws IllegalStateException If the model produces a non-finite score.
   */
  @Override
  public String[] tag(String[] sentence) {
    return decode(sentence, null);
  }

  /**
   * {@inheritDoc}
   * The {@code additionalContext} is ignored.
   *
   * @throws IllegalArgumentException Thrown if {@code sentence} is {@code null}.
   * @throws IllegalStateException If the model produces a non-finite score.
   */
  @Override
  public String[] tag(String[] sentence, Object[] additionalContext) {
    return decode(sentence, null);
  }

  /**
   * {@inheritDoc}
   * Returns the greedy sequence with per-token probabilities.
   *
   * @throws IllegalArgumentException Thrown if {@code sentence} is {@code null}.
   * @throws IllegalStateException If the model produces a non-finite score.
   */
  @Override
  public Sequence[] topKSequences(String[] sentence) {
    final Sequence sequence = new Sequence();
    decode(sentence, sequence);
    return new Sequence[] {sequence};
  }

  /**
   * {@inheritDoc}
   * The result is the single greedy tagging described on
   * {@link #topKSequences(String[])}; the {@code additionalContext} is ignored.
   *
   * @throws IllegalArgumentException Thrown if {@code sentence} is {@code null}.
   * @throws IllegalStateException If the model produces a non-finite score.
   */
  @Override
  public Sequence[] topKSequences(String[] sentence, Object[] additionalContext) {
    return topKSequences(sentence);
  }

  /**
   * Tags tokens from left to right and optionally records probabilities.
   *
   * @param sentence The sentence of tokens to be tagged. Must not be {@code null}.
   * @param collected The {@link Sequence} to record every assigned tag and its
   *                  probability in, or {@code null} to skip recording when only the
   *                  tags are wanted.
   * @return One pos tag per token of {@code sentence}. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code sentence} is {@code null}.
   * @throws IllegalStateException If the model produces a non-finite score.
   */
  private String[] decode(String[] sentence, Sequence collected) {
    if (sentence == null) {
      throw new IllegalArgumentException("sentence must not be null");
    }
    final String[] assigned = new String[sentence.length];
    for (int i = 0; i < sentence.length; i++) {
      final double[] scores = model.score(model.featureIds(
          FeedforwardPOSContext.extract(sentence, i,
              i > 0 ? assigned[i - 1] : null, i > 1 ? assigned[i - 2] : null)),
          model.pretrainedRows(sentence, i));
      int best = 0;
      for (int o = 0; o < scores.length; o++) {
        if (!Double.isFinite(scores[o])) {
          throw new IllegalStateException("the model produced a non-finite tag score");
        }
        if (scores[o] > scores[best]) {
          best = o;
        }
      }
      assigned[i] = tags[best];
      if (collected != null) {
        collected.add(tags[best], probability(scores, best));
      }
    }
    return assigned;
  }

  /**
   * Computes the selected tag's softmax probability, shifted by the highest score.
   *
   * @param scores One unnormalized score per tag, as returned by
   *               {@link FeedforwardPOSModel#score(int[], int[])}. Must be finite and non-empty.
   * @param best The index of the highest scoring tag.
   * @return The model's probability of the tag at {@code best}, in the range
   *         {@code (0, 1]}.
   */
  private double probability(double[] scores, int best) {
    double total = 0.0;
    for (final double score : scores) {
      total += StrictMath.exp(score - scores[best]);
    }
    return 1.0 / total;
  }
}
