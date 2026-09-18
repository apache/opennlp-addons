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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import opennlp.tools.util.ObjectStreamUtils;
import opennlp.tools.util.Sequence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates the complete user-facing workflow of the feedforward tagger in one
 * place: assemble {@link POSSample training samples}, train a
 * {@link FeedforwardPOSModel} with {@link FeedforwardPOSTrainer}, wrap the model in a
 * {@link FeedforwardPOSTagger}, and tag a sentence. The corpus lives entirely inside
 * the test and the seed is fixed, so the asserted tag sequences are the exact output
 * of a reproducible training run.
 */
public class FeedforwardPOSTaggerUsageTest {

  @TempDir
  Path temporary;

  /**
   * Runs the manual's file example with a trained model.
   *
   * @throws IOException If training, saving or loading fails.
   */
  @Test
  void testSaveAndLoadFile() throws IOException {
    final FeedforwardPOSTrainer.Settings settings = new FeedforwardPOSTrainer.Settings(
        16, 32, 80, 32, 0.05, 0.0, 0.0, 1, 1, 17L);
    final FeedforwardPOSModel model = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings);
    final Path modelPath = temporary.resolve("pos-feedforward.bin");
    try (OutputStream out = Files.newOutputStream(modelPath)) {
      model.serialize(out);
    }
    final FeedforwardPOSTagger reloaded = new FeedforwardPOSTagger(
        FeedforwardPOSModel.load(modelPath));
    assertArrayEquals(new String[] {"DT", "NN", "VBZ"},
        reloaded.tag(new String[] {"the", "cat", "barks"}));
  }

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
   * Trains a tagger on the in-test corpus and tags two sentences, asserting the exact
   * tag sequence for each. The first sentence recombines words from different training
   * sentences and the second is a training sentence, so together they show that the
   * tagger generalizes over its vocabulary rather than replaying stored sentences.
   *
   * @throws IOException Thrown if reading the in-memory sample stream fails, which
   *         does not happen in practice.
   */
  @Test
  void testTrainThenTag() throws IOException {
    // Dropout is disabled and the seed is fixed so that this tiny training run is
    // fully deterministic and the asserted tag sequences are stable.
    final FeedforwardPOSTrainer.Settings settings = new FeedforwardPOSTrainer.Settings(
        16, 32, 80, 32, 0.05, 0.0, 0.0, 1, 1, 17L);

    final FeedforwardPOSModel model = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings);
    final FeedforwardPOSTagger tagger = new FeedforwardPOSTagger(model);

    // A novel combination of known words: "cat" only occurred before "sleeps" and
    // "barks" only after "dog" in training, yet the tags come out right.
    assertArrayEquals(new String[] {"DT", "NN", "VBZ"},
        tagger.tag(new String[] {"the", "cat", "barks"}));

    // A sentence taken verbatim from the training corpus.
    assertArrayEquals(new String[] {"NNS", "VBP"},
        tagger.tag(new String[] {"dogs", "bark"}));
  }

  /**
   * Mirrors the confidence-reading listing of the user manual: the greedy tagging
   * comes back from {@code topKSequences} as a single scored {@link Sequence} whose
   * probabilities are one per token, each in {@code (0, 1]}, and whose score is
   * exactly the sum of the logs of those probabilities.
   *
   * @throws IOException Thrown if reading the in-memory sample stream fails, which
   *         does not happen in practice.
   */
  @Test
  void testReadTaggingConfidences() throws IOException {
    final FeedforwardPOSTrainer.Settings settings = new FeedforwardPOSTrainer.Settings(
        16, 32, 80, 32, 0.05, 0.0, 0.0, 1, 1, 17L);
    final FeedforwardPOSModel model = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings);
    final FeedforwardPOSTagger tagger = new FeedforwardPOSTagger(model);

    final Sequence[] sequences =
        tagger.topKSequences(new String[] {"the", "cat", "barks"});
    assertEquals(1, sequences.length,
        "the greedy decoder returns exactly one sequence");
    final Sequence best = sequences[0];
    assertEquals(List.of("DT", "NN", "VBZ"), best.getOutcomes());

    // One probability per token, each in (0, 1]:
    final double[] probs = best.getProbs();
    assertEquals(3, probs.length);
    double logSum = 0.0d;
    for (final double prob : probs) {
      assertTrue(prob > 0.0d && prob <= 1.0d,
          "a tag probability must lie in (0, 1] but was " + prob);
      logSum += StrictMath.log(prob);
    }
    assertEquals(logSum, best.getScore(), 1e-9d,
        "the sequence score must be the sum of the log probabilities");
  }
}
