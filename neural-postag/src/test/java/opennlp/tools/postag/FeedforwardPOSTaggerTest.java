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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import opennlp.tools.parser.AbstractBottomUpParser;
import opennlp.tools.parser.HeadRules;
import opennlp.tools.parser.Parse;
import opennlp.tools.util.ObjectStreamUtils;
import opennlp.tools.util.Sequence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure-Java neural tagging tier end to end: training on a tiny corpus must
 * let the greedy feedforward tagger reproduce the training sentences, and a model must
 * survive the serialization round trip with identical behavior.
 */
public class FeedforwardPOSTaggerTest {

  private static FeedforwardPOSModel model;
  private static FeedforwardPOSTagger tagger;

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
        new POSSample(new String[] {"dogs", "bark"}, new String[] {"NNS", "VBP"}),
        new POSSample(new String[] {"she", "eats", "fish"},
            new String[] {"PRP", "VBZ", "NN"}));
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
    final FeedforwardPOSTrainer.Settings settings = new FeedforwardPOSTrainer.Settings(
        16, 32, 80, 32, 0.05, 0.0, 0.0, 1, 1, 17L);
    model = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings);
    tagger = new FeedforwardPOSTagger(model);
  }

  /** Sentences from the training corpus must come back with their gold tags. */
  @Test
  void testMemorizesTrainingSentences() {
    assertArrayEquals(new String[] {"DT", "NN", "VBZ"},
        tagger.tag(new String[] {"the", "dog", "barks"}));
    assertArrayEquals(new String[] {"PRP", "VBZ", "NN"},
        tagger.tag(new String[] {"she", "eats", "fish"}));
  }

  /** A sentence with an unknown word must still get tags from the model's inventory. */
  @Test
  void testUnknownWordsStillGetTagsFromTheInventory() {
    final String[] assigned = tagger.tag(new String[] {"the", "cat", "sleeps"});
    assertEquals(3, assigned.length);
    final List<String> inventory = List.of(model.tags());
    for (final String tag : assigned) {
      assertTrue(inventory.contains(tag), "tag outside the inventory: " + tag);
    }
  }

  /**
   * A serialize/load round trip must preserve tagging behavior.
   *
   * @throws IOException Thrown if the in-memory round trip fails.
   */
  @Test
  void testModelRoundTripThroughSerialization() throws IOException {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    model.serialize(out);
    final FeedforwardPOSModel reloaded =
        FeedforwardPOSModel.load(new ByteArrayInputStream(out.toByteArray()));
    assertArrayEquals(tagger.tag(new String[] {"the", "dog", "barks"}),
        new FeedforwardPOSTagger(reloaded).tag(new String[] {"the", "dog", "barks"}));
  }

  /** Content that is not a model must be rejected with an {@link IOException}. */
  @Test
  void testCorruptModelFailsLoud() {
    assertThrows(IOException.class, () -> FeedforwardPOSModel.load(
        new ByteArrayInputStream("not a model".getBytes(StandardCharsets.UTF_8))));
  }

  /** Pins every shape class {@link FeedforwardPOSContext#shape(String)} can return. */
  @ParameterizedTest
  @CsvSource({
      "Paris, *cap*",
      "USA, *allcaps*",
      "2020, *digit*",
      "B2B, *alnum*",
      "--, *other*",
      "dog, *lower*"})
  void testShapes(String word, String expected) {
    assertEquals(expected, FeedforwardPOSContext.shape(word));
  }

  /** Pins the suffix extraction on words longer than, equal to, and shorter than it. */
  @ParameterizedTest
  @CsvSource({
      "dog, 2, og",
      "dog, 3, dog",
      "a, 2, a",
      "running, 3, ing"})
  void testSuffixes(String word, int length, String expected) {
    assertEquals(expected, FeedforwardPOSContext.suffix(word, length));
  }

  /**
   * The returned array must hold exactly the greedy tagging, with per-token
   * probabilities in {@code (0, 1]} whose logs sum to the sequence score.
   */
  @Test
  void testTopKSequencesReturnsTheGreedyTaggingWithRealProbabilities() {
    final String[] sentence = {"the", "dog", "barks"};
    final Sequence[] sequences = tagger.topKSequences(sentence);
    assertEquals(1, sequences.length);
    assertEquals(List.of(tagger.tag(sentence)), sequences[0].getOutcomes());
    final double[] probs = sequences[0].getProbs();
    assertEquals(sentence.length, probs.length);
    double expectedScore = 0.0;
    for (final double prob : probs) {
      assertTrue(prob > 0.0 && prob <= 1.0, "probability out of range: " + prob);
      expectedScore += StrictMath.log(prob);
    }
    assertEquals(expectedScore, sequences[0].getScore(), 1.0e-9);
  }

  /**
   * Cached and direct scoring produce identical values for a trained model.
   *
   * @throws IOException Thrown if the round trip fails.
   */
  @Test
  void testScoringCacheMatchesTheDirectPath() throws IOException {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    model.serialize(out);
    final FeedforwardPOSModel direct =
        FeedforwardPOSModel.load(new ByteArrayInputStream(out.toByteArray()));
    final String[] sentence = {"the", "dog", "barks"};
    final int[] features = model.featureIds(
        FeedforwardPOSContext.extract(sentence, 1, "DT", null));

    for (int round = 0; round < 3; round++) {
      final double[] cached = model.score(features);
      final double[] plain = direct.score(features);
      assertArrayEquals(plain, cached);
    }
  }

  /**
   * Pins the probabilities against the model's actual behavior rather than the range
   * alone: the tiny network memorizes its training corpus, so every per-token
   * probability of a memorized sentence must be near certainty. A decoder that
   * reported any fixed placeholder constant in the unit interval would fail here.
   */
  @Test
  void testMemorizedSentenceProbabilitiesAreConfident() {
    final Sequence[] sequences = tagger.topKSequences(new String[] {"the", "dog", "barks"});
    for (final double prob : sequences[0].getProbs()) {
      assertTrue(prob > 0.9, "memorized token should be tagged near certainty: " + prob);
    }
  }

  /**
   * Pins the javadoc-promised boundary: every sentence yields a length-one array, so
   * an empty sentence yields one empty sequence with no outcomes, no probabilities,
   * and a score of zero, the empty sum of log probabilities.
   */
  @Test
  void testTopKSequencesOnEmptySentenceYieldsOneEmptySequence() {
    final Sequence[] sequences = tagger.topKSequences(new String[0]);
    assertEquals(1, sequences.length);
    assertEquals(List.of(), sequences[0].getOutcomes());
    assertEquals(0, sequences[0].getProbs().length);
    assertEquals(0.0, sequences[0].getScore());
  }

  /**
   * Pins the null rejection across all four tagging overloads, which share one
   * decoder: each throws the documented exception rather than a raw
   * {@link NullPointerException}.
   */
  @Test
  void testNullSentenceIsRejectedByEveryOverload() {
    assertThrows(IllegalArgumentException.class, () -> tagger.tag(null));
    assertThrows(IllegalArgumentException.class,
        () -> tagger.tag(null, new Object[0]));
    assertThrows(IllegalArgumentException.class, () -> tagger.topKSequences(null));
    assertThrows(IllegalArgumentException.class,
        () -> tagger.topKSequences(null, new Object[0]));
  }

  /** The {@code additionalContext} overload must decide exactly like the plain one. */
  @Test
  void testTopKSequencesIgnoresAdditionalContext() {
    final String[] sentence = {"she", "eats", "fish"};
    assertArrayEquals(tagger.topKSequences(sentence),
        tagger.topKSequences(sentence, new Object[] {"ignored"}));
  }

  /**
   * Pins the contract {@link opennlp.tools.parser.AbstractBottomUpParser#advanceTags}
   * depends on: it calls {@code topKSequences} unconditionally and turns every returned
   * probability into a log probability, so the tagger must return at least one sequence
   * whose probabilities are strictly positive.
   */
  @Test
  void testTopKSequencesFeedsTheBottomUpParser() {
    final Parse tokens = Parse.createFromTokens(new String[] {"the", "dog", "barks"});
    final Parse[] tagged = new TagOnlyParser(tagger).advanceTagsOf(tokens);
    assertEquals(1, tagged.length);
    assertTrue(Double.isFinite(tagged[0].getProb()),
        "the parser derived a non-finite probability: " + tagged[0].getProb());
  }

  /**
   * The smallest possible {@link AbstractBottomUpParser} that exercises the real
   * {@code advanceTags} implementation without needing a chunker or a parser model.
   */
  private static final class TagOnlyParser extends AbstractBottomUpParser {

    /**
     * Initializes the parser around the tagger under test; no chunker is needed.
     *
     * @param tagger The tagger driving the tagging stage.
     */
    TagOnlyParser(POSTagger tagger) {
      super(tagger, null, new NoPunctuationHeadRules(), defaultBeamSize,
          defaultAdvancePercentage);
    }

    /**
     * Opens the protected {@code advanceTags} to the test.
     *
     * @param p The tokens to tag.
     * @return The tagged parses.
     */
    Parse[] advanceTagsOf(Parse p) {
      return advanceTags(p);
    }

    /**
     * {@inheritDoc} Never advances a parse; tagging is the only stage under test.
     */
    @Override
    protected Parse[] advanceParses(Parse p, double probMass) {
      return new Parse[0];
    }

    /**
     * {@inheritDoc} Does nothing; tagging is the only stage under test.
     */
    @Override
    protected void advanceTop(Parse p) {
      // There is no top stage to advance.
    }
  }

  /** Head rules that model no punctuation, which is all the tagging stage consults. */
  private static final class NoPunctuationHeadRules implements HeadRules {

    /**
     * {@inheritDoc} Always {@code null}; the tagging stage never asks for a head.
     */
    @Override
    public Parse getHead(Parse[] constituents, String type) {
      return null;
    }

    /**
     * {@inheritDoc} Always empty, modeling no punctuation.
     */
    @Override
    public Set<String> getPunctuationTags() {
      return Set.of();
    }
  }

  /**
   * The nonsense hyperparameters are covered exhaustively by
   * {@link FeedforwardPOSTaggerEdgeCaseTest#testSettingsRejectNonsenseValues} and the
   * null sentence by {@link #testNullSentenceIsRejectedByEveryOverload()}, so only the
   * two entry points neither of them reaches are asserted here.
   */
  @Test
  void testArgumentValidation() {
    assertThrows(IllegalArgumentException.class, () -> new FeedforwardPOSTagger(null));
    assertThrows(IllegalArgumentException.class,
        () -> FeedforwardPOSTrainer.train(null, FeedforwardPOSTrainer.Settings.defaults()));
  }
}
