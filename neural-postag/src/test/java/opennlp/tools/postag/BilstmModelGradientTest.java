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

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Finite-difference gradient checks for the full training wiring of
 * {@link BilstmPOSTrainer}: the cross-entropy loss of one sentence, backpropagated
 * through the tag scorer, both sentence BiLSTM directions, both character BiLSTM
 * directions, and the embedding tables, must match central-difference numerical
 * gradients. This is the hard gate that extends {@link LstmLayerGradientTest} from
 * the bare cell to everything training actually runs; dropout is pinned to zero so
 * the loss is deterministic.
 */
class BilstmModelGradientTest {

  private static final double EPSILON = GradientChecks.EPSILON;
  private static final double TOLERANCE = 1e-5d;

  private BilstmPOSTrainer.TrainingContext context;
  private BilstmPOSTrainer.TrainingContext.Worker worker;
  private BilstmPOSTrainer.TrainingContext crfContext;
  private BilstmPOSTrainer.TrainingContext.Worker crfWorker;
  private BilstmPOSTrainer.TrainingContext twoLayerContext;
  private BilstmPOSTrainer.TrainingContext.Worker twoLayerWorker;
  private BilstmPOSTrainer.TrainingContext auxContext;
  private BilstmPOSTrainer.TrainingContext.Worker auxWorker;
  private BilstmPOSTrainer.TrainingContext dropoutContext;
  private BilstmPOSTrainer.TrainingContext.Worker dropoutWorker;
  private BilstmPOSTrainer.MultiTaskSample sample;
  private BilstmPOSTrainer.MultiTaskSample auxSample;
  private List<BilstmPOSTrainer.MultiTaskSample> corpus;

  @BeforeEach
  void setUp() {
    corpus = List.of(
        new BilstmPOSTrainer.MultiTaskSample(new String[] {"The", "cat", "sat"},
            new String[] {"DET", "NOUN", "VERB"}, null, null),
        new BilstmPOSTrainer.MultiTaskSample(new String[] {"A", "dog", "ran"},
            new String[] {"DET", "NOUN", "VERB"}, null, null),
        new BilstmPOSTrainer.MultiTaskSample(new String[] {"Cats", "sleep"},
            new String[] {"NOUN", "VERB"}, null, null));
    final BilstmPOSTrainer.Settings settings = new BilstmPOSTrainer.Settings(
        4, 3, 3, 4, 1, 2, 1e-3d, 5.0d, 0.0d, 1, 10, 7L, 1, 0.0d, 0, false, 1,
        0.0d, 0.0d, 1.0d, 0.0d, false);
    context = BilstmPOSTrainer.TrainingContext.build(corpus, settings, null, null);
    worker = context.newWorker();
    final BilstmPOSTrainer.Settings crfSettings = new BilstmPOSTrainer.Settings(
        4, 3, 3, 4, 1, 2, 1e-3d, 5.0d, 0.0d, 1, 10, 7L, 1, 0.0d, 0, true, 1,
        0.0d, 0.0d, 1.0d, 0.0d, false);
    crfContext = BilstmPOSTrainer.TrainingContext.build(corpus, crfSettings, null, null);
    crfWorker = crfContext.newWorker();
    final BilstmPOSTrainer.Settings twoLayerSettings = new BilstmPOSTrainer.Settings(
        4, 3, 3, 4, 1, 2, 1e-3d, 5.0d, 0.0d, 1, 10, 7L, 1, 0.0d, 0, false, 2,
        0.0d, 0.0d, 1.0d, 0.0d, false);
    twoLayerContext =
        BilstmPOSTrainer.TrainingContext.build(corpus, twoLayerSettings, null, null);
    twoLayerWorker = twoLayerContext.newWorker();

    final List<BilstmPOSTrainer.MultiTaskSample> auxCorpus = List.of(
        new BilstmPOSTrainer.MultiTaskSample(new String[] {"The", "cat", "sat"},
            new String[] {"DET", "NOUN", "VERB"}, new String[] {"DT", "NN", "VBD"},
            new String[] {"_", "Number=Sing", "Tense=Past"}),
        new BilstmPOSTrainer.MultiTaskSample(new String[] {"A", "dog", "ran"},
            new String[] {"DET", "NOUN", "VERB"}, new String[] {"DT", "NN", "VBD"},
            new String[] {"_", "Number=Sing", "Tense=Past"}),
        new BilstmPOSTrainer.MultiTaskSample(new String[] {"Cats", "sleep"},
            new String[] {"NOUN", "VERB"}, new String[] {"NNS", "VBP"},
            new String[] {"Number=Plur", "Tense=Pres"}));
    final BilstmPOSTrainer.Settings auxSettings = new BilstmPOSTrainer.Settings(
        4, 3, 3, 4, 1, 2, 1e-3d, 5.0d, 0.0d, 1, 10, 7L, 1, 0.0d, 0, false, 1,
        0.0d, 0.0d, 0.7d, 0.0d, false);
    auxContext = BilstmPOSTrainer.TrainingContext.build(auxCorpus, auxSettings, null,
        null);
    auxWorker = auxContext.newWorker();
    auxSample = auxCorpus.get(0);

    final BilstmPOSTrainer.Settings dropoutSettings = new BilstmPOSTrainer.Settings(
        4, 3, 3, 4, 1, 2, 1e-3d, 5.0d, 0.0d, 1, 10, 7L, 1, 0.0d, 0, false, 2,
        0.0d, 0.5d, 1.0d, 0.0d, false);
    dropoutContext =
        BilstmPOSTrainer.TrainingContext.build(corpus, dropoutSettings, null, null);
    dropoutWorker = dropoutContext.newWorker();

    sample = corpus.get(0);
  }

  @Test
  void testOutputWeightGradients() {
    final double[][] analytic = analyticGradients(14);
    final double[][] weights = context.testingOutputWeights();
    for (int o = 0; o < weights.length; o++) {
      for (int j = 0; j < weights[o].length; j++) {
        assertClose(analytic[o][j], numerical(weights[o], j),
            "outputWeights[" + o + "][" + j + "]");
      }
    }
  }

  @Test
  void testOutputBiasGradients() {
    final double[][] analytic = analyticGradients(15);
    final double[] bias = context.testingOutputBias();
    for (int o = 0; o < bias.length; o++) {
      assertClose(analytic[0][o], numerical(bias, o), "outputBias[" + o + "]");
    }
  }

  @Test
  void testWordEmbeddingGradients() {
    final double[][] analytic = analyticGradients(0);
    final double[][] embeddings = context.testingWordEmbeddings();
    // every training word of the sample is in the vocabulary (cutoff 1)
    for (int row = 0; row < embeddings.length; row++) {
      for (int i = 0; i < embeddings[row].length; i++) {
        assertClose(analytic[row][i], numerical(embeddings[row], i),
            "wordEmbeddings[" + row + "][" + i + "]");
      }
    }
  }

  @Test
  void testCharEmbeddingGradients() {
    final double[][] analytic = analyticGradients(1);
    final double[][] embeddings = context.testingCharEmbeddings();
    for (int row = 0; row < embeddings.length; row++) {
      for (int i = 0; i < embeddings[row].length; i++) {
        assertClose(analytic[row][i], numerical(embeddings[row], i),
            "charEmbeddings[" + row + "][" + i + "]");
      }
    }
  }

  @Test
  void testWordLstmGradients() {
    final LstmLayer layer = context.testingWordForward();
    final double[][] analyticW = analyticGradients(8);
    final double[][] analyticU = analyticGradients(9);
    final double[][] analyticB = analyticGradients(10);
    for (int r = 0; r < 4 * layer.hiddenSize(); r++) {
      for (int k = 0; k < layer.inputSize(); k++) {
        assertClose(analyticW[r][k], numerical(layer.w()[r], k),
            "wordForward.w[" + r + "][" + k + "]");
      }
      for (int k = 0; k < layer.hiddenSize(); k++) {
        assertClose(analyticU[r][k], numerical(layer.u()[r], k),
            "wordForward.u[" + r + "][" + k + "]");
      }
      assertClose(analyticB[0][r], numerical(layer.b(), r), "wordForward.b[" + r + "]");
    }
  }

  @Test
  void testWordBackwardLstmGradients() {
    final LstmLayer layer = context.testingWordBackward();
    final double[][] analyticW = analyticGradients(11);
    final double[][] analyticU = analyticGradients(12);
    final double[][] analyticB = analyticGradients(13);
    for (int r = 0; r < 4 * layer.hiddenSize(); r++) {
      for (int k = 0; k < layer.inputSize(); k++) {
        assertClose(analyticW[r][k], numerical(layer.w()[r], k),
            "wordBackward.w[" + r + "][" + k + "]");
      }
      for (int k = 0; k < layer.hiddenSize(); k++) {
        assertClose(analyticU[r][k], numerical(layer.u()[r], k),
            "wordBackward.u[" + r + "][" + k + "]");
      }
      assertClose(analyticB[0][r], numerical(layer.b(), r), "wordBackward.b[" + r + "]");
    }
  }

  @Test
  void testCharLstmGradients() {
    final LstmLayer forward = context.testingCharForward();
    final double[][] analyticFwdW = analyticGradients(2);
    final double[][] analyticFwdU = analyticGradients(3);
    final double[][] analyticFwdB = analyticGradients(4);
    final LstmLayer backward = context.testingCharBackward();
    final double[][] analyticBwdW = analyticGradients(5);
    final double[][] analyticBwdU = analyticGradients(6);
    final double[][] analyticBwdB = analyticGradients(7);
    for (int r = 0; r < 4 * forward.hiddenSize(); r++) {
      for (int k = 0; k < forward.inputSize(); k++) {
        assertClose(analyticFwdW[r][k], numerical(forward.w()[r], k),
            "charForward.w[" + r + "][" + k + "]");
        assertClose(analyticBwdW[r][k], numerical(backward.w()[r], k),
            "charBackward.w[" + r + "][" + k + "]");
      }
      for (int k = 0; k < forward.hiddenSize(); k++) {
        assertClose(analyticFwdU[r][k], numerical(forward.u()[r], k),
            "charForward.u[" + r + "][" + k + "]");
        assertClose(analyticBwdU[r][k], numerical(backward.u()[r], k),
            "charBackward.u[" + r + "][" + k + "]");
      }
      assertClose(analyticFwdB[0][r], numerical(forward.b(), r),
          "charForward.b[" + r + "]");
      assertClose(analyticBwdB[0][r], numerical(backward.b(), r),
          "charBackward.b[" + r + "]");
    }
  }

  @Test
  void testSecondLayerGradients() {
    // the stacked encoder's own parameters must agree with finite differences
    final double[][] analyticW = analyticGradients(twoLayerContext, twoLayerWorker, 16);
    final double[][] analyticU = analyticGradients(twoLayerContext, twoLayerWorker, 17);
    final double[][] analyticB = analyticGradients(twoLayerContext, twoLayerWorker, 18);
    final LstmLayer layer = twoLayerContext.testingWordForward2();
    for (int r = 0; r < 4 * layer.hiddenSize(); r++) {
      for (int k = 0; k < layer.inputSize(); k++) {
        assertClose(analyticW[r][k],
            numerical(twoLayerContext, twoLayerWorker, layer.w()[r], k),
            "wordForward2.w[" + r + "][" + k + "]");
      }
      for (int k = 0; k < layer.hiddenSize(); k++) {
        assertClose(analyticU[r][k],
            numerical(twoLayerContext, twoLayerWorker, layer.u()[r], k),
            "wordForward2.u[" + r + "][" + k + "]");
      }
      assertClose(analyticB[0][r], numerical(twoLayerContext, twoLayerWorker,
          layer.b(), r), "wordForward2.b[" + r + "]");
    }
  }

  @Test
  void testFirstLayerGradientsThroughSecondLayer() {
    // gradients flowing back through the stacked encoder into the first layer
    final double[][] analytic = analyticGradients(twoLayerContext, twoLayerWorker, 8);
    final LstmLayer layer = twoLayerContext.testingWordForward();
    for (int r = 0; r < 4 * layer.hiddenSize(); r++) {
      for (int k = 0; k < layer.inputSize(); k++) {
        assertClose(analytic[r][k],
            numerical(twoLayerContext, twoLayerWorker, layer.w()[r], k),
            "stacked wordForward.w[" + r + "][" + k + "]");
      }
    }
  }

  @Test
  void testAuxiliaryHeadGradients() {
    // both auxiliary heads' own parameters must agree with finite differences under
    // the weighted multi-task loss (no crf, one layer: xpos at 16/17, feats at 18/19)
    final double[][] analyticXposW = analyticGradients(auxContext, auxWorker,
        auxSample, 16);
    final double[][] analyticXposB = analyticGradients(auxContext, auxWorker,
        auxSample, 17);
    final double[][] analyticFeatsW = analyticGradients(auxContext, auxWorker,
        auxSample, 18);
    final double[][] xposWeights = auxContext.testingXposWeights();
    final double[] xposBias = auxContext.testingXposBias();
    final double[][] featsWeights = auxContext.testingFeatsWeights();
    for (int o = 0; o < xposWeights.length; o++) {
      for (int j = 0; j < xposWeights[o].length; j++) {
        assertClose(analyticXposW[o][j],
            numerical(auxContext, auxWorker, auxSample, xposWeights[o], j),
            "xposWeights[" + o + "][" + j + "]");
      }
      assertClose(analyticXposB[0][o],
          numerical(auxContext, auxWorker, auxSample, xposBias, o),
          "xposBias[" + o + "]");
    }
    for (int o = 0; o < featsWeights.length; o++) {
      for (int j = 0; j < featsWeights[o].length; j++) {
        assertClose(analyticFeatsW[o][j],
            numerical(auxContext, auxWorker, auxSample, featsWeights[o], j),
            "featsWeights[" + o + "][" + j + "]");
      }
    }
  }

  @Test
  void testEncoderGradientsUnderAuxiliaryLoss() {
    // the shared encoder must receive the summed gradient of all three losses
    final double[][] analytic = analyticGradients(auxContext, auxWorker, auxSample, 8);
    final LstmLayer layer = auxContext.testingWordForward();
    for (int r = 0; r < 4 * layer.hiddenSize(); r++) {
      for (int k = 0; k < layer.inputSize(); k++) {
        assertClose(analytic[r][k],
            numerical(auxContext, auxWorker, auxSample, layer.w()[r], k),
            "aux wordForward.w[" + r + "][" + k + "]");
      }
    }
  }

  @Test
  void testGradientsUnderEncoderDropout() {
    // with encoder dropout active (masks reproduced by the seeded stream), the whole
    // path through both masked boundaries must still agree with finite differences
    final double[][] analyticOut = analyticGradients(dropoutContext, dropoutWorker, 14);
    final double[][] outputWeights = dropoutContext.testingOutputWeights();
    for (int o = 0; o < outputWeights.length; o++) {
      for (int j = 0; j < outputWeights[o].length; j++) {
        assertClose(analyticOut[o][j],
            numerical(dropoutContext, dropoutWorker, outputWeights[o], j),
            "dropout outputWeights[" + o + "][" + j + "]");
      }
    }
    final double[][] analyticL2 = analyticGradients(dropoutContext, dropoutWorker, 16);
    final LstmLayer layer2 = dropoutContext.testingWordForward2();
    for (int r = 0; r < 4 * layer2.hiddenSize(); r++) {
      for (int k = 0; k < layer2.inputSize(); k++) {
        assertClose(analyticL2[r][k],
            numerical(dropoutContext, dropoutWorker, layer2.w()[r], k),
            "dropout wordForward2.w[" + r + "][" + k + "]");
      }
    }
    final double[][] analyticL1 = analyticGradients(dropoutContext, dropoutWorker, 8);
    final LstmLayer layer1 = dropoutContext.testingWordForward();
    for (int r = 0; r < 4 * layer1.hiddenSize(); r++) {
      for (int k = 0; k < layer1.inputSize(); k++) {
        assertClose(analyticL1[r][k],
            numerical(dropoutContext, dropoutWorker, layer1.w()[r], k),
            "dropout wordForward.w[" + r + "][" + k + "]");
      }
    }
  }

  @Test
  void testPretrainedTuningGradients() {
    // the fine-tuned table rows must agree with finite differences, through the
    // same sentence that flows into the shared encoder
    final java.util.function.Function<CharSequence, float[]> vectors =
        w -> new float[] {0.5f, -0.25f};
    final BilstmPOSTrainer.Settings tuningSettings = new BilstmPOSTrainer.Settings(
        4, 3, 3, 4, 1, 2, 1e-3d, 5.0d, 0.0d, 1, 10, 7L, 1, 0.0d, 0, false, 1,
        0.0d, 0.0d, 1.0d, 0.1d, false);
    final BilstmPOSTrainer.TrainingContext tuningContext =
        BilstmPOSTrainer.TrainingContext.build(corpus, tuningSettings, vectors, null);
    final BilstmPOSTrainer.TrainingContext.Worker tuningWorker = tuningContext.newWorker();
    final double[][] table = tuningContext.testingPretrainedTrainable();
    tuningContext.resetWorker(tuningWorker);
    tuningContext.sentenceGradients(sample, new Random(0L), tuningWorker);
    final double[][] analytic = tuningContext.denseTuningGradient(tuningWorker);
    for (int r = 0; r < table.length; r++) {
      for (int i = 0; i < table[r].length; i++) {
        assertClose(analytic[r][i], numerical(tuningContext, tuningWorker, table[r], i),
            "pretrainedTrainable[" + r + "][" + i + "]");
      }
    }
  }

  @Test
  void testAdapterGradients() {
    // the adapter's own parameters must agree with finite differences through the
    // same sentence; with no other optional block registered, adapterWeights sits
    // at registration 16 and adapterBias at 17
    final java.util.function.Function<CharSequence, float[]> vectors =
        w -> new float[] {0.5f, -0.25f};
    final BilstmPOSTrainer.Settings adapterSettings = new BilstmPOSTrainer.Settings(
        4, 3, 3, 4, 1, 2, 1e-3d, 5.0d, 0.0d, 1, 10, 7L, 1, 0.0d, 0, false, 1,
        0.0d, 0.0d, 1.0d, 0.0d, true);
    final BilstmPOSTrainer.TrainingContext adapterContext =
        BilstmPOSTrainer.TrainingContext.build(corpus, adapterSettings, vectors, null);
    final BilstmPOSTrainer.TrainingContext.Worker adapterWorker =
        adapterContext.newWorker();
    final double[][] analyticW = analyticGradients(adapterContext, adapterWorker, 16);
    final double[][] analyticB = analyticGradients(adapterContext, adapterWorker, 17);
    final double[][] weights = adapterContext.testingAdapterWeights();
    final double[] bias = adapterContext.testingAdapterBias();
    for (int r = 0; r < weights.length; r++) {
      for (int i = 0; i < weights[r].length; i++) {
        assertClose(analyticW[r][i], numerical(adapterContext, adapterWorker,
            weights[r], i), "adapterWeights[" + r + "][" + i + "]");
      }
    }
    for (int i = 0; i < bias.length; i++) {
      assertClose(analyticB[0][i], numerical(adapterContext, adapterWorker, bias, i),
          "adapterBias[" + i + "]");
    }
  }

  @Test
  void testIdentityAdapterMatchesFrozenPassThrough() {
    // an identity-initialized adapter must reproduce the frozen-copy path bit for
    // bit: identical loss and identical gradients in every shared buffer, which
    // also pins that creating the adapter consumes no initialization randomness
    final java.util.function.Function<CharSequence, float[]> vectors =
        w -> new float[] {0.5f, -0.25f};
    final BilstmPOSTrainer.Settings offSettings = new BilstmPOSTrainer.Settings(
        4, 3, 3, 4, 1, 2, 1e-3d, 5.0d, 0.0d, 1, 10, 7L, 1, 0.0d, 0, false, 1,
        0.0d, 0.0d, 1.0d, 0.0d, false);
    final BilstmPOSTrainer.Settings onSettings = new BilstmPOSTrainer.Settings(
        4, 3, 3, 4, 1, 2, 1e-3d, 5.0d, 0.0d, 1, 10, 7L, 1, 0.0d, 0, false, 1,
        0.0d, 0.0d, 1.0d, 0.0d, true);
    final BilstmPOSTrainer.TrainingContext offContext =
        BilstmPOSTrainer.TrainingContext.build(corpus, offSettings, vectors, null);
    final BilstmPOSTrainer.TrainingContext onContext =
        BilstmPOSTrainer.TrainingContext.build(corpus, onSettings, vectors, null);
    final BilstmPOSTrainer.TrainingContext.Worker offWorker = offContext.newWorker();
    final BilstmPOSTrainer.TrainingContext.Worker onWorker = onContext.newWorker();
    final double offLoss =
        offContext.sentenceGradients(sample, new Random(0L), offWorker);
    final double onLoss = onContext.sentenceGradients(sample, new Random(0L), onWorker);
    assertEquals(offLoss, onLoss, 0.0d);
    // the adapter context registers two extra buffers (16, 17); the shared ones
    // must match exactly
    for (int b = 0; b < 16; b++) {
      final double[][] off = offWorker.buffers().get(b);
      final double[][] on = onWorker.buffers().get(b);
      for (int r = 0; r < off.length; r++) {
        assertArrayEquals(off[r], on[r], 0.0d, "buffer " + b + " row " + r);
      }
    }
  }

  @Test
  void testCrfLossGradients() {
    // every parameter group must agree with finite differences under the CRF loss
    final double[][] analyticTransitions = analyticGradients(crfContext, crfWorker, 16);
    final double[][] analyticStart = analyticGradients(crfContext, crfWorker, 17);
    final double[][] analyticEnd = analyticGradients(crfContext, crfWorker, 18);
    final double[][] transitions = crfContext.testingTransitionWeights();
    final double[] start = crfContext.testingStartWeights();
    final double[] end = crfContext.testingEndWeights();
    for (int j = 0; j < transitions.length; j++) {
      for (int k = 0; k < transitions[j].length; k++) {
        assertClose(analyticTransitions[j][k],
            numerical(crfContext, crfWorker, transitions[j], k),
            "transitions[" + j + "][" + k + "]");
      }
      assertClose(analyticStart[0][j], numerical(crfContext, crfWorker, start, j),
          "start[" + j + "]");
      assertClose(analyticEnd[0][j], numerical(crfContext, crfWorker, end, j),
          "end[" + j + "]");
    }
  }

  @Test
  void testCrfLossThroughEncoder() {
    // the emission path must also agree under the CRF loss: spot-check the output
    // weights and one sentence-LSTM row
    final double[][] analytic = analyticGradients(crfContext, crfWorker, 14);
    final double[][] outputWeights = crfContext.testingOutputWeights();
    for (int o = 0; o < outputWeights.length; o++) {
      for (int j = 0; j < outputWeights[o].length; j++) {
        assertClose(analytic[o][j], numerical(crfContext, crfWorker, outputWeights[o], j),
            "crf outputWeights[" + o + "][" + j + "]");
      }
    }
    final LstmLayer layer = crfContext.testingWordForward();
    final double[][] analyticW = analyticGradients(crfContext, crfWorker, 8);
    for (int r = 0; r < 4 * layer.hiddenSize(); r++) {
      for (int k = 0; k < layer.inputSize(); k++) {
        assertClose(analyticW[r][k], numerical(crfContext, crfWorker, layer.w()[r], k),
            "crf wordForward.w[" + r + "][" + k + "]");
      }
    }
  }

  private double[][] analyticGradients(int index) {
    return analyticGradients(context, worker, index);
  }

  private double[][] analyticGradients(BilstmPOSTrainer.TrainingContext ctx,
      BilstmPOSTrainer.TrainingContext.Worker wk, int index) {
    return analyticGradients(ctx, wk, sample, index);
  }

  private double[][] analyticGradients(BilstmPOSTrainer.TrainingContext ctx,
      BilstmPOSTrainer.TrainingContext.Worker wk,
      BilstmPOSTrainer.MultiTaskSample of, int index) {
    AdamOptimizer.zero(wk.buffers());
    ctx.sentenceGradients(of, new Random(0L), wk);
    final double[][] source = wk.buffers().get(index);
    final double[][] copy = new double[source.length][];
    for (int r = 0; r < source.length; r++) {
      copy[r] = source[r].clone();
    }
    return copy;
  }

  private double numerical(double[] row, int i) {
    return numerical(context, worker, row, i);
  }

  private double numerical(BilstmPOSTrainer.TrainingContext ctx,
      BilstmPOSTrainer.TrainingContext.Worker wk, double[] row, int i) {
    return numerical(ctx, wk, sample, row, i);
  }

  private double numerical(BilstmPOSTrainer.TrainingContext ctx,
      BilstmPOSTrainer.TrainingContext.Worker wk,
      BilstmPOSTrainer.MultiTaskSample of, double[] row, int i) {
    final double original = row[i];
    row[i] = original + EPSILON;
    final double plus = ctx.sentenceGradients(of, new Random(0L), wk);
    row[i] = original - EPSILON;
    final double minus = ctx.sentenceGradients(of, new Random(0L), wk);
    row[i] = original;
    return (plus - minus) / (2.0d * EPSILON);
  }

  private static void assertClose(double analytic, double numerical, String what) {
    GradientChecks.assertClose(analytic, numerical, TOLERANCE, what);
  }
}
