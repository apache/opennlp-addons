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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.StringUtil;

/**
 * Trains the {@link FeedforwardPOSModel} with array arithmetic inside the JVM: one
 * example per token with gold tag history, minibatch AdaGrad over a softmax
 * cross-entropy loss, cube activation, and inverted dropout on the hidden layer.
 *
 * <p>Words and suffixes below their frequency cutoffs share learned unknown embeddings;
 * positions outside the sentence share a learned padding embedding. Training is
 * deterministic for a fixed {@link Settings#seed()} on a given platform and JVM.
 * {@link Math#exp(double)}, {@link Math#sqrt(double)} and {@link Math#log(double)}
 * can produce small numerical differences between platforms.</p>
 *
 * <p>Pretrained word vectors are an opt-in through
 * {@link #train(ObjectStream, Settings, Function)}: the vectors of the words seen in
 * training extend the input layer and are stored in the model without being updated.
 * Tagging requires no external vector source.</p>
 *
 * @since 3.0.0
 */
public final class FeedforwardPOSTrainer {

  private static final Logger logger = LoggerFactory.getLogger(FeedforwardPOSTrainer.class);

  private static final double ADAGRAD_EPSILON = 1e-6;

  /** The special symbols heading every vocabulary: the unknown and the padding symbol. */
  private static final List<String> SPECIAL_SYMBOLS =
      List.of(FeedforwardPOSModel.UNKNOWN, FeedforwardPOSModel.ABSENT);

  /** Prevents construction of the training utility. */
  private FeedforwardPOSTrainer() {
  }

  /**
   * The training hyperparameters.
   *
   * @param embeddingSize The embedding dimensionality. Must be positive.
   * @param hiddenSize The hidden layer width. Must be positive.
   * @param epochs The number of passes over the examples. Must be positive.
   * @param batchSize The minibatch size. Must be positive.
   * @param learningRate The AdaGrad step size. Must be finite and positive.
   * @param l2 The L2 penalty applied to the dense weights. Must be finite and non-negative.
   * @param dropout The hidden dropout probability. Must be in {@code [0, 1)}.
   * @param wordCutoff The minimum frequency for a word to get its own embedding. Must
   *                   not be negative.
   * @param suffixCutoff The minimum frequency for a suffix to get its own embedding.
   *                     Must not be negative.
   * @param seed The random seed making a run reproducible on a given platform and JVM.
   */
  public record Settings(int embeddingSize, int hiddenSize, int epochs, int batchSize,
      double learningRate, double l2, double dropout, int wordCutoff, int suffixCutoff,
      long seed) {

    /**
     * Validates the hyperparameters.
     *
     * @throws IllegalArgumentException Thrown if a value is outside its documented
     *         range.
     */
    public Settings {
      if (embeddingSize <= 0) {
        throw new IllegalArgumentException("embeddingSize must be positive: " + embeddingSize);
      }
      if (hiddenSize <= 0) {
        throw new IllegalArgumentException("hiddenSize must be positive: " + hiddenSize);
      }
      if (epochs <= 0) {
        throw new IllegalArgumentException("epochs must be positive: " + epochs);
      }
      if (batchSize <= 0) {
        throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
      }
      if (!Double.isFinite(learningRate) || learningRate <= 0.0) {
        throw new IllegalArgumentException("learningRate must be finite and positive: " + learningRate);
      }
      if (!Double.isFinite(l2) || l2 < 0.0) {
        throw new IllegalArgumentException("l2 must be finite and non-negative: " + l2);
      }
      if (!(dropout >= 0.0 && dropout < 1.0)) {
        throw new IllegalArgumentException("dropout must be in [0, 1): " + dropout);
      }
      if (wordCutoff < 0) {
        throw new IllegalArgumentException("wordCutoff must not be negative: " + wordCutoff);
      }
      if (suffixCutoff < 0) {
        throw new IllegalArgumentException("suffixCutoff must not be negative: " + suffixCutoff);
      }
    }

    /**
     * @return The default hyperparameters. Never {@code null}.
     */
    public static Settings defaults() {
      return new Settings(50, 200, 10, 256, 0.02, 1e-8, 0.5, 2, 2, 17L);
    }
  }

  /**
   * Trains a model from POS samples.
   *
   * @param samples The training samples. Must not be {@code null}.
   * @param settings The hyperparameters. Must not be {@code null}.
   * @return A trained {@link FeedforwardPOSModel}. Never {@code null}.
   * @throws IOException Thrown if reading the samples fails.
   * @throws IllegalArgumentException Thrown if a parameter is {@code null} or the
   *         samples contain no token.
   * @throws IllegalStateException If training produces non-finite values.
   */
  public static FeedforwardPOSModel train(ObjectStream<POSSample> samples,
      Settings settings) throws IOException {
    return trainWith(samples, settings, null, null);
  }

  /**
   * Trains with pretrained vectors for normalized training words. The source is called
   * once per distinct normalized word; {@code null} means no vector. Training copies
   * the vectors without updating them and stores them in the model.
   *
   * @param samples The training samples. Must not be {@code null}.
   * @param settings The hyperparameters. Must not be {@code null}.
   * @param wordVectors The word vector source consulted at training time. Must not be
   *                    {@code null}; must return vectors of one consistent positive
   *                    length, only finite components, and a vector for at least one training word.
   * @return A trained model with pretrained vectors.
   * @throws IOException Thrown if reading the samples fails.
   * @throws IllegalArgumentException Thrown if a parameter is {@code null}, the
   *         samples contain no token, or {@code wordVectors} violates its contract.
   * @throws IllegalStateException If training produces non-finite values.
   */
  public static FeedforwardPOSModel train(ObjectStream<POSSample> samples,
      Settings settings, Function<CharSequence, float[]> wordVectors) throws IOException {
    if (wordVectors == null) {
      throw new IllegalArgumentException("wordVectors must not be null");
    }
    return trainWith(samples, settings, wordVectors, null);
  }

  /**
   * Trains with pretrained vectors and stores vectors for additional words. The
   * lexicon extends vector lookup at inference but does not supply training examples.
   *
   * @param samples The training samples. Must not be {@code null}.
   * @param settings The hyperparameters. Must not be {@code null}.
   * @param wordVectors The word vector source consulted at training time. Must not be
   *                    {@code null}; must return vectors of one consistent positive
   *                    length, only finite components, and a vector for at least one training word.
   * @param lexicon Additional words to store vectors for, normalized like the training
   *                words. Must not be {@code null} or contain {@code null}; words the
   *                source has no vector for are skipped.
   * @return A trained model with pretrained vectors.
   * @throws IOException Thrown if reading the samples fails.
   * @throws IllegalArgumentException Thrown if a parameter is {@code null}, the
   *         samples contain no token, or {@code wordVectors} violates its contract.
   * @throws IllegalStateException If training produces non-finite values.
   */
  public static FeedforwardPOSModel train(ObjectStream<POSSample> samples,
      Settings settings, Function<CharSequence, float[]> wordVectors,
      Iterable<? extends CharSequence> lexicon) throws IOException {
    if (wordVectors == null) {
      throw new IllegalArgumentException("wordVectors must not be null");
    }
    if (lexicon == null) {
      throw new IllegalArgumentException("lexicon must not be null");
    }
    return trainWith(samples, settings, wordVectors, lexicon);
  }

  /**
   * Trains from samples, with optional vectors and additional words.
   *
   * @param samples The training samples. Must not be {@code null}.
   * @param settings The hyperparameters. Must not be {@code null}.
   * @param wordVectors The word vector source, or {@code null} to train without the
   *                    vector block.
   * @param lexicon Additional words to store vectors for, or {@code null} for none.
   * @return A trained {@link FeedforwardPOSModel}. Never {@code null}.
   * @throws IOException Thrown if reading the samples fails.
   * @throws IllegalArgumentException Thrown if {@code samples} or {@code settings} is
   *         {@code null}, the samples contain no token, or {@code wordVectors} violates
   *         its contract.
   * @throws IllegalStateException If training produces non-finite values.
   */
  private static FeedforwardPOSModel trainWith(ObjectStream<POSSample> samples,
      Settings settings, Function<CharSequence, float[]> wordVectors,
      Iterable<? extends CharSequence> lexicon) throws IOException {
    if (samples == null || settings == null) {
      throw new IllegalArgumentException("samples and settings must not be null");
    }
    final List<POSSample> corpus = new ArrayList<>();
    POSSample sample;
    while ((sample = samples.read()) != null) {
      if (sample.getSentence().length > 0) {
        corpus.add(sample);
      }
    }
    if (corpus.isEmpty()) {
      throw new IllegalArgumentException("no trainable examples in the samples");
    }
    final FeedforwardPOSModel model = initialize(corpus, settings, wordVectors, lexicon);

    final Map<String, Integer> outputIds = new HashMap<>();
    final String[] tags = model.tags();
    for (int i = 0; i < tags.length; i++) {
      outputIds.put(tags[i], i);
    }
    final List<int[]> featureList = new ArrayList<>();
    final List<int[]> pretrainedList =
        model.usesPretrainedVectors() ? new ArrayList<>() : null;
    final List<Integer> goldList = new ArrayList<>();
    for (final POSSample s : corpus) {
      final String[] sentence = s.getSentence();
      final String[] gold = s.getTags();
      for (int i = 0; i < sentence.length; i++) {
        featureList.add(model.featureIds(FeedforwardPOSContext.extract(sentence, i,
            i > 0 ? gold[i - 1] : null, i > 1 ? gold[i - 2] : null)));
        if (pretrainedList != null) {
          pretrainedList.add(model.pretrainedRows(sentence, i));
        }
        goldList.add(outputIds.get(gold[i]));
      }
    }
    optimize(model, featureList, pretrainedList, goldList, settings);
    return model;
  }

  /**
   * Builds the word, suffix, shape, and tag vocabularies from the corpus, assigns each
   * retained symbol an embedding row, and creates a model with randomly initialized
   * weights that {@link #optimize} then trains in place.
   *
   * @param corpus The non-empty training samples.
   * @param settings The hyperparameters controlling the cutoffs and layer sizes.
   * @param wordVectors The word vector source, or {@code null} to build no vector block.
   * @param lexicon Additional words to store vectors for, or {@code null} for none.
   * @return An untrained model with all vocabularies in place. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code wordVectors} returns an empty
   *         vector, a non-finite component, vectors of differing lengths,
   *         or no vector for any training word,
   *         or if {@code lexicon} contains {@code null}.
   */
  private static FeedforwardPOSModel initialize(List<POSSample> corpus,
      Settings settings, Function<CharSequence, float[]> wordVectors,
      Iterable<? extends CharSequence> lexicon) {
    final Map<String, Integer> wordCounts = new LinkedHashMap<>();
    final Map<String, Integer> suffixCounts = new LinkedHashMap<>();
    final Map<String, Integer> tagSet = new LinkedHashMap<>();
    for (final POSSample s : corpus) {
      for (final String token : s.getSentence()) {
        final String word = FeedforwardPOSModel.normalize(token);
        wordCounts.merge(word, 1, Integer::sum);
        final String lowered = StringUtil.toLowerCase(token);
        suffixCounts.merge(FeedforwardPOSContext.suffix(lowered,
            FeedforwardPOSContext.SHORT_SUFFIX_LENGTH), 1, Integer::sum);
        suffixCounts.merge(FeedforwardPOSContext.suffix(lowered,
            FeedforwardPOSContext.LONG_SUFFIX_LENGTH), 1, Integer::sum);
      }
      for (final String tag : s.getTags()) {
        tagSet.putIfAbsent(tag, 0);
      }
    }

    int row = 0;
    final Map<String, Integer> wordIds = new LinkedHashMap<>();
    for (final String special : SPECIAL_SYMBOLS) {
      wordIds.put(special, row++);
    }
    for (final Map.Entry<String, Integer> entry : wordCounts.entrySet()) {
      if (entry.getValue() >= settings.wordCutoff() && !wordIds.containsKey(entry.getKey())) {
        wordIds.put(entry.getKey(), row++);
      }
    }
    final Map<String, Integer> suffixIds = new LinkedHashMap<>();
    for (final String special : SPECIAL_SYMBOLS) {
      suffixIds.put(special, row++);
    }
    for (final Map.Entry<String, Integer> entry : suffixCounts.entrySet()) {
      if (entry.getValue() >= settings.suffixCutoff()
          && !suffixIds.containsKey(entry.getKey())) {
        suffixIds.put(entry.getKey(), row++);
      }
    }
    final Map<String, Integer> shapeIds = new LinkedHashMap<>();
    for (final String special : SPECIAL_SYMBOLS) {
      shapeIds.put(special, row++);
    }
    for (final String shape : FeedforwardPOSContext.SHAPES) {
      shapeIds.put(shape, row++);
    }
    final Map<String, Integer> tagIds = new LinkedHashMap<>();
    for (final String special : SPECIAL_SYMBOLS) {
      tagIds.put(special, row++);
    }
    final String[] tags = new String[tagSet.size()];
    int tagIndex = 0;
    for (final String tag : tagSet.keySet()) {
      tags[tagIndex++] = tag;
      tagIds.put(tag, row++);
    }

    int pretrainedSize = 0;
    final Map<String, Integer> pretrainedIds = new LinkedHashMap<>();
    final List<float[]> pretrainedVectors = new ArrayList<>();
    if (wordVectors != null) {
      for (final String word : wordCounts.keySet()) {
        final float[] vector = wordVectors.apply(word);
        if (vector == null) {
          continue;
        }
        pretrainedSize = validateVector(vector, pretrainedSize, word);
        pretrainedIds.put(word, pretrainedVectors.size());
        pretrainedVectors.add(vector.clone());
      }
      if (pretrainedVectors.isEmpty()) {
        throw new IllegalArgumentException(
            "wordVectors supplied no vector for any training word");
      }
      if (lexicon != null) {
        for (final CharSequence entry : lexicon) {
          if (entry == null) {
            throw new IllegalArgumentException("lexicon must not contain null");
          }
          final String word = FeedforwardPOSModel.normalize(entry.toString());
          if (word.isEmpty() || pretrainedIds.containsKey(word)) {
            continue;
          }
          final float[] vector = wordVectors.apply(word);
          if (vector == null) {
            continue;
          }
          validateVector(vector, pretrainedSize, word);
          pretrainedIds.put(word, pretrainedVectors.size());
          pretrainedVectors.add(vector.clone());
        }
      }
    }

    final Random random = new Random(settings.seed());
    final int inputSize = FeedforwardPOSContext.SLOTS * settings.embeddingSize()
        + FeedforwardPOSContext.PRETRAINED_SLOTS * pretrainedSize;
    final float[][] embeddings = uniform(random, row, settings.embeddingSize(), 0.01);
    final float[][] hiddenWeights = uniform(random, settings.hiddenSize(), inputSize,
        Math.sqrt(6.0 / (inputSize + settings.hiddenSize())));
    final float[][] outputWeights = uniform(random, tags.length, settings.hiddenSize(),
        Math.sqrt(6.0 / (settings.hiddenSize() + tags.length)));
    return new FeedforwardPOSModel(wordIds, suffixIds, shapeIds, tagIds, tags,
        settings.embeddingSize(), embeddings, hiddenWeights,
        new float[settings.hiddenSize()], outputWeights, new float[tags.length],
        pretrainedSize, pretrainedIds, pretrainedVectors.toArray(new float[0][]));
  }

  /**
   * Checks the dimensions and finite components of a supplied vector.
   *
   * @param vector The non-null vector from the source.
   * @param expectedSize The established dimension, or zero for the first vector.
   * @param word The normalized word for error messages.
   * @return The vector dimension.
   * @throws IllegalArgumentException If the vector is empty, has a different length,
   *         or contains a non-finite component.
   */
  private static int validateVector(float[] vector, int expectedSize, String word) {
    if (vector.length == 0) {
      throw new IllegalArgumentException("wordVectors returned an empty vector for: " + word);
    }
    if (expectedSize != 0 && vector.length != expectedSize) {
      throw new IllegalArgumentException("wordVectors returned a vector of length "
          + vector.length + " after length " + expectedSize + " for: " + word);
    }
    for (int i = 0; i < vector.length; i++) {
      if (!Float.isFinite(vector[i])) {
        throw new IllegalArgumentException("wordVectors returned a non-finite value at index "
            + i + " for: " + word);
      }
    }
    return vector.length;
  }

  /**
   * Trains the model weights in place with minibatch AdaGrad over a softmax
   * cross-entropy loss, using the cube activation on the hidden layer and inverted
   * dropout during training. Each epoch shuffles the example order with the seeded
   * random generator, so the whole optimization is reproducible for a fixed seed on a
   * given platform and JVM.
   *
   * @param model The initialized model to train in place.
   * @param featureList The embedding row indices of every training example.
   * @param pretrainedList The pretrained vector rows of every training example, or
   *                       {@code null} without pretrained vectors. The vectors remain
   *                       fixed while the hidden weights over them are trained.
   * @param goldList The gold output index of every training example, aligned with
   *                 {@code featureList}.
   * @param settings The hyperparameters controlling the optimization.
   * @throws IllegalStateException If a gradient, accumulator or updated weight is non-finite.
   */
  private static void optimize(FeedforwardPOSModel model, List<int[]> featureList,
      List<int[]> pretrainedList, List<Integer> goldList, Settings settings) {
    final int exampleCount = featureList.size();
    final int[][] features = featureList.toArray(new int[0][]);
    final int[][] pretrained =
        pretrainedList == null ? null : pretrainedList.toArray(new int[0][]);
    final int[] gold = new int[exampleCount];
    for (int i = 0; i < exampleCount; i++) {
      gold[i] = goldList.get(i);
    }

    final float[][] embeddings = model.embeddings();
    final float[][] hiddenWeights = model.hiddenWeights();
    final float[] hiddenBias = model.hiddenBias();
    final float[][] outputWeights = model.outputWeights();
    final float[] outputBias = model.outputBias();
    final int embeddingSize = settings.embeddingSize();
    final int hiddenSize = settings.hiddenSize();
    final int outputSize = outputBias.length;
    final int discreteInput = features[0].length * embeddingSize;
    final int inputSize = hiddenWeights[0].length;
    final int pretrainedSize = model.pretrainedSize();
    final float[][] pretrainedVectors = model.pretrainedVectors();

    final double[][] embeddingAccumulator = new double[embeddings.length][embeddingSize];
    final double[][] hiddenAccumulator = new double[hiddenSize][inputSize];
    final double[] hiddenBiasAccumulator = new double[hiddenSize];
    final double[][] outputAccumulator = new double[outputSize][hiddenSize];
    final double[] outputBiasAccumulator = new double[outputSize];

    final double[][] hiddenGradient = new double[hiddenSize][inputSize];
    final double[] hiddenBiasGradient = new double[hiddenSize];
    final double[][] outputGradient = new double[outputSize][hiddenSize];
    final double[] outputBiasGradient = new double[outputSize];
    final Map<Integer, double[]> embeddingGradients = new HashMap<>();

    final Random random = new Random(settings.seed());
    final int[] order = new int[exampleCount];
    for (int i = 0; i < exampleCount; i++) {
      order[i] = i;
    }

    final double keep = 1.0 - settings.dropout();
    final double[] x = new double[inputSize];
    final double[] pre = new double[hiddenSize];
    final double[] hidden = new double[hiddenSize];
    final boolean[] mask = new boolean[hiddenSize];
    final double[] probabilities = new double[outputSize];
    final double[] hiddenDelta = new double[hiddenSize];
    final double[] inputDelta = new double[inputSize];

    for (int epoch = 1; epoch <= settings.epochs(); epoch++) {
      final long epochStart = System.currentTimeMillis();
      shuffle(order, random);
      double loss = 0.0;
      for (int batchStart = 0; batchStart < exampleCount;
          batchStart += settings.batchSize()) {
        final int batchEnd = Math.min(batchStart + settings.batchSize(), exampleCount);
        final int batch = batchEnd - batchStart;
        zero(hiddenGradient);
        Arrays.fill(hiddenBiasGradient, 0.0);
        zero(outputGradient);
        Arrays.fill(outputBiasGradient, 0.0);
        embeddingGradients.clear();

        for (int b = batchStart; b < batchEnd; b++) {
          final int[] feats = features[order[b]];
          final int goldTag = gold[order[b]];
          for (int f = 0; f < feats.length; f++) {
            final float[] embedding = embeddings[feats[f]];
            final int offset = f * embeddingSize;
            for (int d = 0; d < embeddingSize; d++) {
              x[offset + d] = embedding[d];
            }
          }
          if (pretrained != null) {
            final int[] rows = pretrained[order[b]];
            for (int po = 0; po < rows.length; po++) {
              final int offset = discreteInput + po * pretrainedSize;
              if (rows[po] == FeedforwardPOSModel.NO_VECTOR) {
                Arrays.fill(x, offset, offset + pretrainedSize, 0.0);
              } else {
                final float[] vector = pretrainedVectors[rows[po]];
                for (int d = 0; d < pretrainedSize; d++) {
                  x[offset + d] = vector[d];
                }
              }
            }
          }
          for (int j = 0; j < hiddenSize; j++) {
            mask[j] = random.nextDouble() < keep;
            if (!mask[j]) {
              pre[j] = 0.0;
              hidden[j] = 0.0;
              continue;
            }
            final float[] weightRow = hiddenWeights[j];
            double sum = hiddenBias[j];
            for (int k = 0; k < inputSize; k++) {
              sum += weightRow[k] * x[k];
            }
            pre[j] = sum;
            hidden[j] = sum * sum * sum / keep;
          }
          double max = Double.NEGATIVE_INFINITY;
          for (int o = 0; o < outputSize; o++) {
            final float[] weightRow = outputWeights[o];
            double sum = outputBias[o];
            for (int j = 0; j < hiddenSize; j++) {
              sum += weightRow[j] * hidden[j];
            }
            probabilities[o] = sum;
            max = Math.max(max, sum);
          }
          double normalizer = 0.0;
          for (int o = 0; o < outputSize; o++) {
            probabilities[o] = Math.exp(probabilities[o] - max);
            normalizer += probabilities[o];
          }
          for (int o = 0; o < outputSize; o++) {
            probabilities[o] /= normalizer;
          }
          loss -= Math.log(Math.max(probabilities[goldTag], 1e-12));

          Arrays.fill(hiddenDelta, 0.0);
          Arrays.fill(inputDelta, 0.0);
          for (int o = 0; o < outputSize; o++) {
            final double delta = probabilities[o] - (o == goldTag ? 1.0 : 0.0);
            outputBiasGradient[o] += delta;
            final double[] gradientRow = outputGradient[o];
            final float[] weightRow = outputWeights[o];
            for (int j = 0; j < hiddenSize; j++) {
              gradientRow[j] += delta * hidden[j];
              hiddenDelta[j] += delta * weightRow[j];
            }
          }
          for (int j = 0; j < hiddenSize; j++) {
            if (!mask[j]) {
              continue;
            }
            final double preDelta = hiddenDelta[j] * 3.0 * pre[j] * pre[j] / keep;
            hiddenBiasGradient[j] += preDelta;
            final double[] gradientRow = hiddenGradient[j];
            final float[] weightRow = hiddenWeights[j];
            for (int k = 0; k < inputSize; k++) {
              gradientRow[k] += preDelta * x[k];
              inputDelta[k] += preDelta * weightRow[k];
            }
          }
          for (int f = 0; f < feats.length; f++) {
            final double[] embeddingGradient = embeddingGradients
                .computeIfAbsent(feats[f], key -> new double[embeddingSize]);
            final int offset = f * embeddingSize;
            for (int d = 0; d < embeddingSize; d++) {
              embeddingGradient[d] += inputDelta[offset + d];
            }
          }
        }

        update(hiddenWeights, hiddenGradient, hiddenAccumulator, batch, settings);
        updateVector(hiddenBias, hiddenBiasGradient, hiddenBiasAccumulator, batch, settings);
        update(outputWeights, outputGradient, outputAccumulator, batch, settings);
        updateVector(outputBias, outputBiasGradient, outputBiasAccumulator, batch, settings);
        for (final Map.Entry<Integer, double[]> entry : embeddingGradients.entrySet()) {
          final float[] embeddingRow = embeddings[entry.getKey()];
          final double[] accumulatorRow = embeddingAccumulator[entry.getKey()];
          final double[] gradientRow = entry.getValue();
          for (int d = 0; d < embeddingSize; d++) {
            final double gradient = gradientRow[d] / batch;
            accumulatorRow[d] += gradient * gradient;
            embeddingRow[d] = updateWeight(embeddingRow[d], gradient,
                accumulatorRow[d], settings.learningRate());
          }
        }
      }
      logger.info("tagger epoch {}: loss {} in {} ms", epoch, loss / exampleCount,
          System.currentTimeMillis() - epochStart);
    }
  }

  /**
   * Applies one AdaGrad step to a weight matrix, averaging the accumulated batch
   * gradient, adding the L2 penalty, and scaling by the per-weight adaptive rate.
   *
   * @param weights The weight matrix to update in place.
   * @param gradients The summed gradients of the current minibatch.
   * @param accumulators The running sums of squared gradients per weight.
   * @param batch The number of examples in the current minibatch.
   * @param settings The hyperparameters providing the learning rate and L2 penalty.
   * @throws IllegalStateException If a gradient, accumulator or updated weight is non-finite.
   */
  private static void update(float[][] weights, double[][] gradients,
      double[][] accumulators, int batch, Settings settings) {
    for (int r = 0; r < weights.length; r++) {
      final float[] weightRow = weights[r];
      final double[] gradientRow = gradients[r];
      final double[] accumulatorRow = accumulators[r];
      for (int c = 0; c < weightRow.length; c++) {
        final double gradient = gradientRow[c] / batch + settings.l2() * weightRow[c];
        accumulatorRow[c] += gradient * gradient;
        weightRow[c] = updateWeight(weightRow[c], gradient,
            accumulatorRow[c], settings.learningRate());
      }
    }
  }

  /**
   * Applies one AdaGrad step to a bias vector, averaging the accumulated batch
   * gradient and scaling by the per-weight adaptive rate. Biases have no L2 penalty.
   *
   * @param weights The bias vector to update in place.
   * @param gradients The summed gradients of the current minibatch.
   * @param accumulators The running sums of squared gradients per weight.
   * @param batch The number of examples in the current minibatch.
   * @param settings The hyperparameters providing the learning rate.
   * @throws IllegalStateException If a gradient, accumulator or updated weight is non-finite.
   */
  private static void updateVector(float[] weights, double[] gradients,
      double[] accumulators, int batch, Settings settings) {
    for (int i = 0; i < weights.length; i++) {
      final double gradient = gradients[i] / batch;
      accumulators[i] += gradient * gradient;
      weights[i] = updateWeight(weights[i], gradient, accumulators[i], settings.learningRate());
    }
  }

  /**
   * Checks an AdaGrad update for finite inputs and output.
   *
   * @param weight The current weight.
   * @param gradient The gradient for this update.
   * @param accumulator The sum of squared gradients, including this update.
   * @param learningRate The configured step size.
   * @return The updated weight.
   * @throws IllegalStateException If the gradient, accumulator or updated weight is non-finite.
   */
  private static float updateWeight(float weight, double gradient, double accumulator,
      double learningRate) {
    final float updated = (float) (weight - learningRate * gradient
        / (Math.sqrt(accumulator) + ADAGRAD_EPSILON));
    if (!Double.isFinite(gradient) || !Double.isFinite(accumulator) || !Float.isFinite(updated)) {
      throw new IllegalStateException(
          "training produced non-finite values; check learningRate, l2 and word vectors");
    }
    return updated;
  }

  /**
   * Draws a matrix with entries sampled uniformly from {@code [-scale, scale)}.
   *
   * @param random The seeded random generator, so initialization is reproducible.
   * @param rows The number of rows.
   * @param columns The number of columns.
   * @param scale The half-width of the sampling interval.
   * @return The freshly sampled matrix. Never {@code null}.
   */
  private static float[][] uniform(Random random, int rows, int columns, double scale) {
    final float[][] matrix = new float[rows][columns];
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < columns; c++) {
        matrix[r][c] = (float) ((random.nextDouble() * 2.0 - 1.0) * scale);
      }
    }
    return matrix;
  }

  /**
   * Resets every entry of a gradient matrix to zero so it can accumulate the next
   * minibatch.
   *
   * @param matrix The matrix to clear in place.
   */
  private static void zero(double[][] matrix) {
    for (final double[] row : matrix) {
      Arrays.fill(row, 0.0);
    }
  }

  /**
   * Shuffles the example visiting order in place with the Fisher-Yates algorithm,
   * driven by the seeded random generator so epochs remain reproducible.
   *
   * @param order The example indices to permute in place.
   * @param random The seeded random generator.
   */
  private static void shuffle(int[] order, Random random) {
    for (int i = order.length - 1; i > 0; i--) {
      final int j = random.nextInt(i + 1);
      final int swap = order[i];
      order[i] = order[j];
      order[j] = swap;
    }
  }
}
