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
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import opennlp.tools.util.ObjectStreamUtils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises the boundaries of the feedforward tagging tier: degenerate inputs such as
 * the empty sentence and the single-token sentence, words that never occurred in
 * training, the serialization round trip, and the fail-loud validation of trainer and
 * model parameters. The training corpus lives entirely inside the test and the seed is
 * fixed, so every asserted tag sequence is the exact output of a reproducible run.
 */
public class FeedforwardPOSTaggerEdgeCaseTest {

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
        new POSSample(new String[] {"the", "cat", "sleeps"},
            new String[] {"DT", "NN", "VBZ"}),
        new POSSample(new String[] {"dogs", "bark"},
            new String[] {"NNS", "VBP"}),
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

  /**
   * Tagging the empty sentence must return the empty tag array rather than failing.
   */
  @Test
  void testEmptySentenceYieldsEmptyTags() {
    assertArrayEquals(new String[0], tagger.tag(new String[0]));
  }

  /**
   * A single-token sentence has no context words and no tag history, so every context
   * slot around the token is the padding symbol; the token itself still decides.
   */
  @Test
  void testSingleTokenSentence() {
    assertArrayEquals(new String[] {"NN"}, tagger.tag(new String[] {"dog"}));
  }

  /**
   * A word never seen in training falls back to the learned unknown embedding, and
   * the surrounding known words steer the decision to the tag its position calls for.
   */
  @Test
  void testUnknownWordTakesTagFromContext() {
    assertArrayEquals(new String[] {"DT", "NN", "VBZ"},
        tagger.tag(new String[] {"the", "wug", "barks"}));
  }

  /**
   * A sentence of entirely unknown words must still come back fully tagged; with no
   * lexical evidence at all the decision rests on suffixes, shapes, and tag history.
   * The asserted tags are the exact output of the deterministic model for this input,
   * not a linguistic ground truth.
   */
  @Test
  void testAllUnknownWordsStillTagged() {
    assertArrayEquals(new String[] {"NNS", "VBZ"},
        tagger.tag(new String[] {"blorp", "quexination"}));
  }

  /**
   * Tokens outside the training script must still tag, one tag per token: the
   * Turkish dotted capital I lowercases to a combining-dot form on its way to the
   * unknown embedding, and an emoji token reaches inference as a surrogate pair.
   * Both are pinned only as not throwing and producing a tag per token; repeating
   * the call pins that inference on the frozen model is stable.
   */
  @Test
  void testUnicodeTokensStillTag() {
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
   * Serializing and reloading the model must preserve tagging behavior exactly, both
   * on a training sentence and on a sentence containing an unknown word.
   *
   * @throws IOException Thrown if the in-memory serialization round trip fails.
   */
  @Test
  void testSaveLoadRoundTripKeepsTagsIdentical() throws IOException {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    model.serialize(out);
    final FeedforwardPOSTagger reloaded = new FeedforwardPOSTagger(
        FeedforwardPOSModel.load(new ByteArrayInputStream(out.toByteArray())));

    final String[] known = {"she", "eats", "fish"};
    final String[] withUnknown = {"the", "wug", "barks"};
    assertArrayEquals(tagger.tag(known), reloaded.tag(known));
    assertArrayEquals(tagger.tag(withUnknown), reloaded.tag(withUnknown));
    assertArrayEquals(new String[] {"PRP", "VBZ", "NN"}, reloaded.tag(known));
  }

  /**
   * Training must reject a {@code null} settings object before touching the samples.
   */
  @Test
  void testTrainRejectsNullSettings() {
    assertThrows(IllegalArgumentException.class, () -> FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), null));
  }

  /**
   * Training must fail loud when the sample stream is empty instead of silently
   * producing a model with nothing learned.
   */
  @Test
  void testTrainRejectsEmptySampleStream() {
    assertThrows(IllegalArgumentException.class, () -> FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(List.<POSSample>of()),
        FeedforwardPOSTrainer.Settings.defaults()));
  }

  /**
   * Samples whose sentences carry no token contribute nothing, so a stream made only
   * of them must be rejected exactly like the empty stream.
   */
  @Test
  void testTrainRejectsOnlyEmptySentences() {
    final POSSample empty = new POSSample(new String[0], new String[0]);
    assertThrows(IllegalArgumentException.class, () -> FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(List.of(empty, empty)),
        FeedforwardPOSTrainer.Settings.defaults()));
  }

  /**
   * Every nonsensical hyperparameter must be rejected by the settings record itself:
   * non-positive sizes and counts, a non-positive learning rate, a negative L2
   * penalty, a dropout outside {@code [0, 1)}, and negative frequency cutoffs. One
   * case per row, so a regression names the offending parameter.
   */
  @ParameterizedTest
  @CsvSource({
      "-1, 32, 10, 32, 0.05,  0.0,  0.0,  1,  1",
      " 0, 32, 10, 32, 0.05,  0.0,  0.0,  1,  1",
      "16,  0, 10, 32, 0.05,  0.0,  0.0,  1,  1",
      "16, 32,  0, 32, 0.05,  0.0,  0.0,  1,  1",
      "16, 32, 10,  0, 0.05,  0.0,  0.0,  1,  1",
      "16, 32, 10, 32, 0.0,   0.0,  0.0,  1,  1",
      "16, 32, 10, 32, 0.05, -0.1,  0.0,  1,  1",
      "16, 32, 10, 32, 0.05,  0.0, -0.1,  1,  1",
      "16, 32, 10, 32, 0.05,  0.0,  1.0,  1,  1",
      "16, 32, 10, 32, 0.05,  0.0,  0.0, -1,  1",
      "16, 32, 10, 32, 0.05,  0.0,  0.0,  1, -1"})
  void testSettingsRejectNonsenseValues(int embeddingSize, int hiddenSize, int epochs,
      int batchSize, double learningRate, double l2, double dropout, int wordCutoff,
      int suffixCutoff) {
    assertThrows(IllegalArgumentException.class,
        () -> new FeedforwardPOSTrainer.Settings(embeddingSize, hiddenSize, epochs,
            batchSize, learningRate, l2, dropout, wordCutoff, suffixCutoff, 17L));
  }

  /**
   * The model must reject {@code null} streams and paths on both sides of the
   * serialization boundary instead of failing later with a confusing error.
   */
  @Test
  void testSerializationRejectsNullArguments() {
    assertThrows(IllegalArgumentException.class, () -> model.serialize(null));
    assertThrows(IllegalArgumentException.class,
        () -> FeedforwardPOSModel.load((InputStream) null));
    assertThrows(IllegalArgumentException.class,
        () -> FeedforwardPOSModel.load((Path) null));
  }
}
