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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import opennlp.tools.util.ObjectStreamUtils;
import opennlp.tools.util.Sequence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the boundaries of the BiLSTM tagging tier: degenerate inputs such as the
 * empty sentence and the single-token sentence, fail-loud {@code null} rejection on
 * every public overload, tokens outside the trained character vocabulary including
 * surrogate-pair emoji, truncation of overlong tokens, a long-sentence smoke run, the
 * scored sequences of the CRF decoder, and the fail-loud validation of the trainer
 * settings. The training corpus lives entirely inside the test and the seed is fixed,
 * so every asserted tag sequence is the exact output of a reproducible run.
 */
public class BilstmPOSTaggerEdgeCaseTest {

  private static BilstmPOSModel model;
  private static BilstmPOSTagger tagger;

  /**
   * Builds the tiny in-memory training corpus. Each distinct sentence is repeated so
   * that the network sees every word often enough to learn stable tag decisions, and
   * every word clears the frequency cutoff for its own embedding.
   *
   * @return The training samples. Never {@code null} and never empty.
   */
  private static List<POSSample> corpus() {
    final List<POSSample> distinct = List.of(
        new POSSample(new String[] {"the", "dog", "barks"},
            new String[] {"DT", "NN", "VBZ"}),
        new POSSample(new String[] {"the", "cat", "sleeps"},
            new String[] {"DT", "NN", "VBZ"}),
        new POSSample(new String[] {"a", "bird", "sings"},
            new String[] {"DT", "NN", "VBZ"}),
        new POSSample(new String[] {"dogs", "bark"},
            new String[] {"NNS", "VBP"}),
        new POSSample(new String[] {"cats", "sleep"},
            new String[] {"NNS", "VBP"}));
    final List<POSSample> corpus = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      corpus.addAll(distinct);
    }
    return corpus;
  }

  /**
   * Trains the shared model once for all tests in this class. Dropout is disabled and
   * the seed is fixed so that the tiny training run is fully deterministic.
   *
   * @throws IOException Thrown if reading the in-memory sample stream fails, which
   *         does not happen in practice.
   */
  @BeforeAll
  static void trainTagger() throws IOException {
    final BilstmPOSTrainer.Settings settings = new BilstmPOSTrainer.Settings(
        16, 8, 8, 16, 60, 8, 0.05d, 5.0d, 0.0d, 1, 16, 17L, 1, 0.0d, 0, false, 1,
        0.0d, 0.0d, 1.0d, 0.0d, false);
    model = BilstmPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings);
    tagger = new BilstmPOSTagger(model);
  }

  /**
   * Tagging the empty sentence must return the empty tag array rather than failing;
   * the decoder returns before ever scoring, so no LSTM runs over zero tokens.
   */
  @Test
  void testEmptySentenceYieldsEmptyTags() {
    assertArrayEquals(new String[0], tagger.tag(new String[0]));
    final Sequence[] sequences = tagger.topKSequences(new String[0]);
    assertEquals(1, sequences.length);
    assertEquals(0, sequences[0].getSize());
  }

  /**
   * A single-token sentence gives the sentence encoder exactly one time step in each
   * direction. No training sentence has length one, and this model weighs sentence
   * position heavily: the lone token lands on the sentence-final verb tag, not the
   * noun tag "dog" carries mid-sentence. The assertion pins that exact deterministic
   * output, not a linguistic ground truth.
   */
  @Test
  void testSingleTokenSentence() {
    assertArrayEquals(new String[] {"VBZ"}, tagger.tag(new String[] {"dog"}));
  }

  /**
   * Every public overload must reject a {@code null} sentence with an
   * {@link IllegalArgumentException} before any scoring starts, and the constructor
   * must reject a {@code null} model the same way.
   */
  @Test
  void testNullsRejectedAcrossPublicOverloads() {
    assertThrows(IllegalArgumentException.class, () -> new BilstmPOSTagger(null));
    assertThrows(IllegalArgumentException.class, () -> tagger.tag(null));
    assertThrows(IllegalArgumentException.class, () -> tagger.tag(null, new Object[0]));
    assertThrows(IllegalArgumentException.class, () -> tagger.topKSequences(null));
    assertThrows(IllegalArgumentException.class,
        () -> tagger.topKSequences(null, new Object[0]));
  }

  /**
   * Tokens far outside the trained vocabulary must still tag: an accented word falls
   * back to unknown character rows, and an emoji token exercises the char-level path
   * with surrogate pairs, since the character vocabulary is built over {@code char}
   * units and a surrogate half simply maps to the unknown row. The asserted tags pin
   * the exact output of the deterministic model, not a linguistic ground truth.
   */
  @Test
  void testUnicodeAndEmojiTokensStillTag() {
    final String[] sentence = {"İstanbul", "🐕"};
    final String[] tags = tagger.tag(sentence);
    assertEquals(sentence.length, tags.length);
    for (final String tag : tags) {
      assertNotNull(tag);
    }
    assertArrayEquals(tags, tagger.tag(sentence),
        "repeated inference on the frozen model must be stable");
  }

  /**
   * The character BiLSTM reads at most {@code maxWordLength} leading characters, so
   * two out-of-vocabulary tokens that agree on that prefix are indistinguishable to
   * the model and must tag identically, however long the overflow is.
   */
  @Test
  void testOverlongTokenIsTruncatedToMaxWordLength() {
    assertTrue(model.maxWordLength() < 40, "the fixture must overflow the cap");
    final String overlong = "z".repeat(40);
    final String cappedPrefix = overlong.substring(0, model.maxWordLength());
    assertArrayEquals(tagger.tag(new String[] {cappedPrefix}),
        tagger.tag(new String[] {overlong}),
        "tokens equal up to maxWordLength must be indistinguishable");
  }

  /**
   * A 500-token sentence must come back with exactly one tag per token; this smokes
   * the whole-sentence encoder well beyond every training length without asserting
   * any particular tagging.
   */
  @Test
  void testLongSentenceSmoke() {
    final String[] words = {"the", "dog", "barks", "cats", "sleep"};
    final String[] sentence = new String[500];
    for (int i = 0; i < sentence.length; i++) {
      sentence[i] = words[i % words.length];
    }
    final String[] tags = tagger.tag(sentence);
    assertEquals(sentence.length, tags.length);
    for (final String tag : tags) {
      assertNotNull(tag);
    }
  }

  /**
   * The CRF decoder must return its single Viterbi tagging as a scored sequence: the
   * outcomes are exactly what {@link BilstmPOSTagger#tag(String[])} answers, each
   * probability is a marginal in {@code (0, 1]}, and the array is ordered best first,
   * trivially so at length one.
   *
   * @throws IOException Thrown if reading the in-memory sample stream fails, which
   *         does not happen in practice.
   */
  @Test
  void testTopKSequencesThroughCrfPath() throws IOException {
    final List<POSSample> crfCorpus = List.of(
        new POSSample(new String[] {"The", "cat", "sat"}, new String[] {"D", "N", "V"}),
        new POSSample(new String[] {"A", "dog", "ran"}, new String[] {"D", "N", "V"}),
        new POSSample(new String[] {"The", "bird", "flew"}, new String[] {"D", "N", "V"}),
        new POSSample(new String[] {"A", "fish", "swam"}, new String[] {"D", "N", "V"}));
    final BilstmPOSModel crfModel = BilstmPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(crfCorpus),
        new BilstmPOSTrainer.Settings(8, 4, 4, 8, 40, 2, 5e-3d, 5.0d, 0.1d, 1, 12, 7L,
            1, 0.0d, 0, true, 1, 0.0d, 0.0d, 1.0d, 0.0d, false));
    assertTrue(crfModel.isCrf(), "the fixture must decode through the CRF");
    final BilstmPOSTagger crfTagger = new BilstmPOSTagger(crfModel);

    final String[] sentence = {"The", "cat", "sat"};
    final Sequence[] sequences = crfTagger.topKSequences(sentence);
    assertEquals(1, sequences.length);
    final Sequence top = sequences[0];
    assertArrayEquals(crfTagger.tag(sentence),
        top.getOutcomes().toArray(new String[0]),
        "the top sequence must be the tagging tag() answers");
    final double[] probs = top.getProbs();
    assertEquals(sentence.length, probs.length);
    for (final double prob : probs) {
      assertTrue(prob > 0.0d && prob <= 1.0d,
          "a CRF marginal must lie in (0, 1] but was " + prob);
    }
    for (int i = 1; i < sequences.length; i++) {
      assertTrue(sequences[i - 1].getScore() >= sequences[i].getScore(),
          "sequences must be ordered best first");
    }
  }

  /**
   * Every out-of-range optional-tier hyperparameter must be rejected by the settings
   * record itself, on both sides of each bound: the two dropout probabilities outside
   * {@code [0, 1)}, a negative auxiliary loss weight, a negative fine-tuning rate,
   * and an encoder depth outside {@code 1..2}. One case per row, so a regression
   * names the offending parameter.
   *
   * @param pretrainedDropout The pretrained-block dropout to try.
   * @param encoderDropout The encoder dropout to try.
   * @param auxLossWeight The auxiliary loss weight to try.
   * @param pretrainedTuning The pretrained fine-tuning rate to try.
   * @param encoderLayers The encoder depth to try.
   */
  @ParameterizedTest
  @CsvSource({
      "-0.1,  0.0,  1.0,  0.0, 1",
      " 1.0,  0.0,  1.0,  0.0, 1",
      " 0.0, -0.1,  1.0,  0.0, 1",
      " 0.0,  1.0,  1.0,  0.0, 1",
      " 0.0,  0.0, -0.1,  0.0, 1",
      " 0.0,  0.0,  1.0, -0.1, 1",
      " 0.0,  0.0,  1.0,  0.0, 0",
      " 0.0,  0.0,  1.0,  0.0, 3"})
  void testSettingsRejectOutOfRangeOptionalTiers(double pretrainedDropout,
      double encoderDropout, double auxLossWeight, double pretrainedTuning,
      int encoderLayers) {
    assertThrows(IllegalArgumentException.class,
        () -> new BilstmPOSTrainer.Settings(16, 8, 8, 16, 60, 8, 0.05d, 5.0d, 0.0d,
            1, 16, 17L, 1, 0.0d, 0, false, encoderLayers, pretrainedDropout,
            encoderDropout, auxLossWeight, pretrainedTuning, false));
  }

  /**
   * The extreme in-range values of the same bounds must construct: dropout
   * probabilities just below one, a zero auxiliary loss weight, and the two-layer
   * encoder. This pins the closed side of each interval.
   */
  @Test
  void testSettingsAcceptBoundaryValues() {
    assertDoesNotThrow(() -> new BilstmPOSTrainer.Settings(16, 8, 8, 16, 60, 8,
        0.05d, 5.0d, 0.0d, 1, 16, 17L, 1, 0.0d, 0, false, 2, 0.99d, 0.99d, 0.0d,
        0.0d, false));
  }
}
