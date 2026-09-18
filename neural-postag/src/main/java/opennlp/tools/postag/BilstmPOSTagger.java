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
 * A bidirectional LSTM {@link POSTagger} that scores tokens from sentence
 * context. Softmax models select the highest scoring tag at each position; CRF
 * models decode the best sequence with Viterbi.
 *
 * <p>Inference uses Java arrays without a native runtime. {@link #topKSequences(String[])}
 * returns the same single tagging as {@link #tag(String[])}, with softmax or CRF
 * posterior probabilities for the assigned tags.</p>
 *
 * <p>The {@code additionalContext} of the interface carries no information this model
 * was trained on, so both overloads that take it ignore it.</p>
 *
 * <p>The tagger holds an immutable model and no per-call state, so one instance can
 * be shared between threads.</p>
 *
 * @see BilstmPOSTrainer
 * @see BilstmPOSModel
 * @since 3.0.0
 */
public class BilstmPOSTagger implements POSTagger {

  private final BilstmPOSModel model;
  private final String[] tags;

  /**
   * Initializes a {@link BilstmPOSTagger}.
   * Taggers using the same model share its token-representation cache.
   *
   * @param model The model to tag with. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code model} is {@code null}.
   */
  public BilstmPOSTagger(BilstmPOSModel model) {
    if (model == null) {
      throw new IllegalArgumentException("model must not be null");
    }
    this.model = model;
    this.tags = model.tags();
    model.enableRepresentationCache();
  }

  /**
   * {@inheritDoc}
   *
   * @throws IllegalArgumentException Thrown if {@code sentence} is {@code null}
   *         or contains a null element.
   * @throws IllegalStateException Thrown if a computed tag score or CRF weight is not finite.
   */
  @Override
  public String[] tag(String[] sentence) {
    return decode(sentence, null);
  }

  /**
   * {@inheritDoc}
   * Ignores {@code additionalContext}.
   *
   * @throws IllegalArgumentException Thrown if {@code sentence} is {@code null}
   *         or contains a null element.
   * @throws IllegalStateException Thrown if a computed tag score or CRF weight is not finite.
   */
  @Override
  public String[] tag(String[] sentence, Object[] additionalContext) {
    return decode(sentence, null);
  }

  /**
   * {@inheritDoc}
   * Returns one tagging with per-token probabilities; the score is the sum of their
   * logarithms, not the CRF joint log probability.
   *
   * @throws IllegalArgumentException Thrown if {@code sentence} is {@code null}
   *         or contains a null element.
   * @throws IllegalStateException Thrown if a computed tag score or CRF weight is not finite.
   */
  @Override
  public Sequence[] topKSequences(String[] sentence) {
    final Sequence sequence = new Sequence();
    decode(sentence, sequence);
    return new Sequence[] {sequence};
  }

  /**
   * {@inheritDoc}
   * Ignores {@code additionalContext} and returns {@link #topKSequences(String[])}.
   *
   * @throws IllegalArgumentException Thrown if {@code sentence} is {@code null}
   *         or contains a null element.
   * @throws IllegalStateException Thrown if a computed tag score or CRF weight is not finite.
   */
  @Override
  public Sequence[] topKSequences(String[] sentence, Object[] additionalContext) {
    return topKSequences(sentence);
  }

  /**
   * Assigns tags and optionally collects their probabilities.
   *
   * @param sentence The input tokens.
   * @param collected The output sequence, or null when probabilities are not needed.
   * @return The assigned tags.
   * @throws IllegalArgumentException Thrown if the tokens array is null or contains
   *         a null element.
   * @throws IllegalStateException Thrown if a computed tag score or CRF weight is not finite.
   */
  private String[] decode(String[] sentence, Sequence collected) {
    if (sentence == null) {
      throw new IllegalArgumentException("sentence must not be null");
    }
    final String[] assigned = new String[sentence.length];
    if (sentence.length == 0) {
      return assigned;
    }
    final double[][] scores = model.score(sentence);
    if (model.isCrf()) {
      final CrfScorer scorer = new CrfScorer(tags.length);
      final int[] path = scorer.viterbi(scores, model.transitionWeights(),
          model.startWeights(), model.endWeights());
      final double[][] marginals = collected != null
          ? scorer.marginals(scores, model.transitionWeights(), model.startWeights(),
              model.endWeights())
          : null;
      for (int i = 0; i < sentence.length; i++) {
        assigned[i] = tags[path[i]];
        if (collected != null) {
          collected.add(assigned[i], marginals[i][path[i]]);
        }
      }
      return assigned;
    }
    for (int i = 0; i < sentence.length; i++) {
      int best = 0;
      for (int o = 1; o < tags.length; o++) {
        if (scores[i][o] > scores[i][best]) {
          best = o;
        }
      }
      assigned[i] = tags[best];
      if (collected != null) {
        collected.add(tags[best], probability(scores[i], best));
      }
    }
    return assigned;
  }

  /**
   * Turns one position's unnormalized tag scores into the probability of the assigned
   * tag, applying the softmax shifted by the highest score so that no term of the sum
   * can overflow.
   *
   * @param scores One finite, unnormalized score per tag. Must not be empty.
   * @param best The index of the highest scoring tag.
   * @return The model's probability of the tag at {@code best}, in the range
   *         {@code (0, 1]}.
   */
  private double probability(double[] scores, int best) {
    double total = 0.0d;
    for (final double score : scores) {
      total += Math.exp(score - scores[best]);
    }
    return 1.0d / total;
  }
}
