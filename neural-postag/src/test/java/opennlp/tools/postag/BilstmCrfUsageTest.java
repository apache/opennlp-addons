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
import java.util.LinkedHashMap;
import java.util.Random;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Sequence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Demonstrates CRF tagging and per-token probabilities from a saved model. */
class BilstmCrfUsageTest {

  /**
   * Common emission offsets do not change tags or their probabilities after loading.
   *
   * @param offset The common output bias.
   * @throws IOException If model serialization fails.
   */
  @ParameterizedTest
  @ValueSource(doubles = {0, 1e20, 1e300, -1e300, Double.MAX_VALUE})
  void testSavedCrfProbabilities(double offset) throws IOException {
    final LinkedHashMap<String, Integer> words = new LinkedHashMap<>();
    words.put(BilstmPOSModel.UNKNOWN, 0);
    final LinkedHashMap<String, Integer> chars = new LinkedHashMap<>(words);
    final Random random = new Random(7);
    final BilstmPOSModel original = new BilstmPOSModel(words, chars,
        new String[] {"N", "V"}, new double[1][1], new double[1][1],
        new LstmLayer(1, 1, random), new LstmLayer(1, 1, random),
        new LstmLayer(3, 1, random), new LstmLayer(3, 1, random),
        new double[2][2], new double[] {offset, offset}, 12, null, null,
        new double[2][2], new double[2], new double[] {0, 1});
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    original.serialize(output);
    final BilstmPOSModel loaded = BilstmPOSModel.load(new ByteArrayInputStream(output.toByteArray()));
    final BilstmPOSTagger tagger = new BilstmPOSTagger(loaded);
    final String[] tokens = {"the", "cat", "runs"};
    final String[] expected = {"N", "N", "V"};
    final Sequence[] sequences = tagger.topKSequences(tokens);
    assertEquals(1, sequences.length);
    final Sequence best = sequences[0];
    assertArrayEquals(expected, best.getOutcomes().toArray(String[]::new));
    assertArrayEquals(expected, tagger.tag(tokens));
    assertArrayEquals(expected, tagger.tag(tokens, new Object[0]));
    final double lastProbability = 1 / (1 + Math.exp(-1));
    assertArrayEquals(new double[] {0.5, 0.5, lastProbability}, best.getProbs(), 1e-15);
    assertEquals(2 * Math.log(0.5) + Math.log(lastProbability), best.getScore(), 1e-15);
    assertArrayEquals(best.getProbs(), tagger.topKSequences(tokens, new Object[0])[0].getProbs());
  }
}
