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

import org.junit.jupiter.api.Test;

import opennlp.tools.util.ObjectStreamUtils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Demonstrates the complete user-facing workflow of the BiLSTM tagger in one place:
 * assemble {@link POSSample training samples}, train a {@link BilstmPOSModel} with
 * {@link BilstmPOSTrainer}, wrap the model in a {@link BilstmPOSTagger}, and tag a
 * sentence. The corpus lives entirely inside the test and the seed is fixed, so the
 * asserted tag sequences are the exact output of a reproducible training run.
 */
public class BilstmPOSTaggerUsageTest {

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
    final BilstmPOSTrainer.Settings settings = new BilstmPOSTrainer.Settings(
        16, 8, 8, 16, 60, 8, 0.05d, 5.0d, 0.0d, 1, 16, 17L, 1, 0.0d, 0, false, 1,
        0.0d, 0.0d, 1.0d, 0.0d, false);

    final BilstmPOSModel model = BilstmPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings);
    final BilstmPOSTagger tagger = new BilstmPOSTagger(model);

    // A novel combination of known words: "cat" only occurred before "sleeps" and
    // "barks" only after "dog" in training, yet the tags come out right.
    assertArrayEquals(new String[] {"DT", "NN", "VBZ"},
        tagger.tag(new String[] {"the", "cat", "barks"}));

    // A sentence taken verbatim from the training corpus.
    assertArrayEquals(new String[] {"NNS", "VBP"},
        tagger.tag(new String[] {"dogs", "bark"}));
  }

  /**
   * Mirrors the "Closing the accuracy gap" listing of the user manual: the same tiny
   * corpus trained with the CRF output layer and the two-layer encoder switched on
   * must tag the same sentences correctly. The seed is fixed and dropout disabled, so
   * the asserted tag sequences are the exact output of a reproducible training run.
   *
   * @throws IOException Thrown if reading the in-memory sample stream fails, which
   *         does not happen in practice.
   */
  @Test
  void testTrainThenTagWithCrfAndSecondEncoderLayer() throws IOException {
    // Identical to the settings above except for CRF decoding and encoderLayers=2.
    final BilstmPOSTrainer.Settings accurate = new BilstmPOSTrainer.Settings(
        16, 8, 8, 16, 60, 8, 0.05d, 5.0d, 0.0d, 1, 16, 17L, 1, 0.0d, 0, true, 2,
        0.0d, 0.0d, 1.0d, 0.0d, false);

    final BilstmPOSTagger tagger = new BilstmPOSTagger(BilstmPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), accurate));

    assertArrayEquals(new String[] {"DT", "NN", "VBZ"},
        tagger.tag(new String[] {"the", "cat", "barks"}));
    assertArrayEquals(new String[] {"NNS", "VBP"},
        tagger.tag(new String[] {"dogs", "bark"}));
  }
}
