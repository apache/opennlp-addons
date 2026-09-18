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

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import opennlp.tools.util.StringUtil;

/**
 * The trained parameters of the bidirectional LSTM POS tagger: word and character
 * embeddings, a character-level BiLSTM that builds word representations, a word-level
 * BiLSTM that encodes the sentence, a linear tag scorer, and an optional frozen
 * pretrained-vector table concatenated into the word representation, through an
 * optional learned adapter projection when the model was trained with one.
 *
 * <p>Row zero of the word and character vocabularies is the unknown entry, so any
 * token can be encoded. The parameters never change once the model is built and
 * scoring keeps no per-call state, which makes inference thread-safe;
 * {@link #enableRepresentationCache()} may be called by consumers that only read the
 * model to skip recomputing word representations of repeated tokens.</p>
 *
 * <p>The serialized form is a self-describing versioned binary whose magic identifies
 * the layout, {@code ONLP-BLPT-1} through {@code ONLP-BLPT-5}, following the same
 * layout conventions as {@link FeedforwardPOSModel}.</p>
 *
 * @see BilstmPOSTrainer
 * @see BilstmPOSTagger
 * @since 3.0.0
 */
public class BilstmPOSModel {

  static final String MAGIC = "ONLP-BLPT-1";
  static final String MAGIC_CRF = "ONLP-BLPT-2";
  static final String MAGIC_TWO_LAYER = "ONLP-BLPT-3";
  static final String MAGIC_TWO_LAYER_CRF = "ONLP-BLPT-4";
  static final String MAGIC_ADAPTER = "ONLP-BLPT-5";

  /** Marks the built-in vocabulary symbols, which {@link #normalize} leaves alone. */
  static final String SYMBOL_PREFIX = "*";

  static final String UNKNOWN = "*UNK*";

  /** The most token representations {@link #enableRepresentationCache()} memoizes. */
  private static final int REPRESENTATION_CACHE_LIMIT = 100_000;

  private final LinkedHashMap<String, Integer> words;
  private final LinkedHashMap<String, Integer> chars;
  private final String[] tags;
  private final double[][] wordEmbeddings;
  private final double[][] charEmbeddings;
  private final LstmLayer charForward;
  private final LstmLayer charBackward;
  private final LstmLayer wordForward;
  private final LstmLayer wordBackward;
  private final LstmLayer wordForward2;
  private final LstmLayer wordBackward2;
  private final double[][] outputWeights;
  private final double[] outputBias;
  private final int maxWordLength;
  private final int pretrainedSize;
  private final LinkedHashMap<String, Integer> pretrainedIds;
  private final float[][] pretrainedVectors;
  private final double[][] adapterWeights;
  private final double[] adapterBias;
  private final double[][] transitionWeights;
  private final double[] startWeights;
  private final double[] endWeights;

  private volatile Map<String, double[]> representationCache;
  private final AtomicInteger representationCacheRemaining =
      new AtomicInteger(REPRESENTATION_CACHE_LIMIT);

  /**
   * Initializes a single-layer softmax model without a CRF output layer and without an
   * adapter over the pretrained block.
   *
   * @see #BilstmPOSModel(LinkedHashMap, LinkedHashMap, String[], double[][], double[][],
   *      LstmLayer, LstmLayer, LstmLayer, LstmLayer, LstmLayer, LstmLayer, double[][],
   *      double[], int, LinkedHashMap, float[][], double[][], double[], double[],
   *      double[][], double[])
   */
  BilstmPOSModel(LinkedHashMap<String, Integer> words, LinkedHashMap<String, Integer> chars,
      String[] tags, double[][] wordEmbeddings, double[][] charEmbeddings,
      LstmLayer charForward, LstmLayer charBackward, LstmLayer wordForward,
      LstmLayer wordBackward, double[][] outputWeights, double[] outputBias,
      int maxWordLength, LinkedHashMap<String, Integer> pretrainedIds,
      float[][] pretrainedVectors) {
    this(words, chars, tags, wordEmbeddings, charEmbeddings, charForward, charBackward,
        wordForward, wordBackward, outputWeights, outputBias, maxWordLength,
        pretrainedIds, pretrainedVectors, null, null, null);
  }

  /**
   * Initializes a single-layer model with an optional CRF output layer and without an
   * adapter over the pretrained block.
   *
   * @see #BilstmPOSModel(LinkedHashMap, LinkedHashMap, String[], double[][], double[][],
   *      LstmLayer, LstmLayer, LstmLayer, LstmLayer, LstmLayer, LstmLayer, double[][],
   *      double[], int, LinkedHashMap, float[][], double[][], double[], double[],
   *      double[][], double[])
   */
  BilstmPOSModel(LinkedHashMap<String, Integer> words, LinkedHashMap<String, Integer> chars,
      String[] tags, double[][] wordEmbeddings, double[][] charEmbeddings,
      LstmLayer charForward, LstmLayer charBackward, LstmLayer wordForward,
      LstmLayer wordBackward, double[][] outputWeights, double[] outputBias,
      int maxWordLength, LinkedHashMap<String, Integer> pretrainedIds,
      float[][] pretrainedVectors, double[][] transitionWeights, double[] startWeights,
      double[] endWeights) {
    this(words, chars, tags, wordEmbeddings, charEmbeddings, charForward, charBackward,
        wordForward, wordBackward, null, null, outputWeights, outputBias,
        maxWordLength, pretrainedIds, pretrainedVectors, transitionWeights,
        startWeights, endWeights);
  }

  /**
   * Initializes a model with an optional stacked encoder and an optional CRF output
   * layer, and without an adapter over the pretrained block.
   *
   * @see #BilstmPOSModel(LinkedHashMap, LinkedHashMap, String[], double[][], double[][],
   *      LstmLayer, LstmLayer, LstmLayer, LstmLayer, LstmLayer, LstmLayer, double[][],
   *      double[], int, LinkedHashMap, float[][], double[][], double[], double[],
   *      double[][], double[])
   */
  BilstmPOSModel(LinkedHashMap<String, Integer> words, LinkedHashMap<String, Integer> chars,
      String[] tags, double[][] wordEmbeddings, double[][] charEmbeddings,
      LstmLayer charForward, LstmLayer charBackward, LstmLayer wordForward,
      LstmLayer wordBackward, LstmLayer wordForward2, LstmLayer wordBackward2,
      double[][] outputWeights, double[] outputBias, int maxWordLength,
      LinkedHashMap<String, Integer> pretrainedIds, float[][] pretrainedVectors,
      double[][] transitionWeights, double[] startWeights, double[] endWeights) {
    this(words, chars, tags, wordEmbeddings, charEmbeddings, charForward, charBackward,
        wordForward, wordBackward, wordForward2, wordBackward2, outputWeights,
        outputBias, maxWordLength, pretrainedIds, pretrainedVectors, transitionWeights,
        startWeights, endWeights, null, null);
  }

  /**
   * Initializes a model from its vocabularies and parameter arrays. The trainer and the
   * loader are the only callers; the arrays are taken over without copying and must not
   * be mutated afterwards.
   *
   * @param words The word lookup form to embedding row mapping, row zero unknown.
   * @param chars The character to embedding row mapping, row zero unknown.
   * @param tags The tag inventory by output index.
   * @param wordEmbeddings The learned word embeddings, one row per word.
   * @param charEmbeddings The learned character embeddings, one row per character.
   * @param charForward The forward character LSTM.
   * @param charBackward The backward character LSTM.
   * @param wordForward The forward sentence LSTM.
   * @param wordBackward The backward sentence LSTM.
   * @param wordForward2 The second forward sentence LSTM, or {@code null} when the
   *                     encoder is single-layer.
   * @param wordBackward2 The second backward sentence LSTM, or {@code null} when the
   *                      encoder is single-layer.
   * @param outputWeights The tag scorer weights, {@code [tags][2 * hidden]}.
   * @param outputBias The tag scorer bias, one entry per tag.
   * @param maxWordLength The maximum characters of a token fed to the character BiLSTM.
   * @param pretrainedIds The word lookup form to pretrained vector row mapping, or
   *                      {@code null} when the model carries no vector table.
   * @param pretrainedVectors The pretrained vector table, or {@code null} when absent.
   * @param transitionWeights The CRF transition scores, or {@code null} on a softmax
   *                          model.
   * @param startWeights The CRF start scores, or {@code null} on a softmax model.
   * @param endWeights The CRF end scores, or {@code null} on a softmax model.
   * @param adapterWeights The square adapter projection over the pretrained block, or
   *                       {@code null} when the block enters unchanged.
   * @param adapterBias The adapter bias, or {@code null} when there is no adapter.
   */
  BilstmPOSModel(LinkedHashMap<String, Integer> words, LinkedHashMap<String, Integer> chars,
      String[] tags, double[][] wordEmbeddings, double[][] charEmbeddings,
      LstmLayer charForward, LstmLayer charBackward, LstmLayer wordForward,
      LstmLayer wordBackward, LstmLayer wordForward2, LstmLayer wordBackward2,
      double[][] outputWeights, double[] outputBias, int maxWordLength,
      LinkedHashMap<String, Integer> pretrainedIds, float[][] pretrainedVectors,
      double[][] transitionWeights, double[] startWeights, double[] endWeights,
      double[][] adapterWeights, double[] adapterBias) {
    this.words = words;
    this.chars = chars;
    this.tags = tags;
    this.wordEmbeddings = wordEmbeddings;
    this.charEmbeddings = charEmbeddings;
    this.charForward = charForward;
    this.charBackward = charBackward;
    this.wordForward = wordForward;
    this.wordBackward = wordBackward;
    this.wordForward2 = wordForward2;
    this.wordBackward2 = wordBackward2;
    this.outputWeights = outputWeights;
    this.outputBias = outputBias;
    this.maxWordLength = maxWordLength;
    this.pretrainedSize = pretrainedVectors != null ? pretrainedVectors[0].length : 0;
    this.pretrainedIds = pretrainedIds;
    this.pretrainedVectors = pretrainedVectors;
    this.adapterWeights = adapterWeights;
    this.adapterBias = adapterBias;
    this.transitionWeights = transitionWeights;
    this.startWeights = startWeights;
    this.endWeights = endWeights;
  }

  /**
   * @return The tag inventory, in model order. A defensive copy, never {@code null}.
   */
  public String[] tags() {
    return tags.clone();
  }

  /**
   * @return {@code true} when this model carries a linear-chain CRF output layer and
   *         decodes by Viterbi rather than per-position argmax.
   */
  public boolean isCrf() {
    return transitionWeights != null;
  }

  /**
   * @return The CRF transition scores, {@code [tags][tags]}, or {@code null} on a
   *         softmax model.
   */
  double[][] transitionWeights() {
    return transitionWeights;
  }

  /**
   * @return The CRF start scores, or {@code null} on a softmax model.
   */
  double[] startWeights() {
    return startWeights;
  }

  /**
   * @return The CRF end scores, or {@code null} on a softmax model.
   */
  double[] endWeights() {
    return endWeights;
  }

  /**
   * @return The number of hidden units per direction of the sentence BiLSTM.
   */
  int hiddenSize() {
    return wordForward.hiddenSize();
  }

  /**
   * @return The dimension of a word representation: learned word embedding plus both
   *         character BiLSTM directions plus the frozen pretrained vector when present.
   */
  int representationSize() {
    return wordEmbeddings[0].length + 2 * charForward.hiddenSize() + pretrainedSize;
  }

  /**
   * @return The maximum number of characters of a token fed to the character BiLSTM.
   */
  public int maxWordLength() {
    return maxWordLength;
  }

  /**
   * Enables a model-scoped cache of up to 100,000 token representations. Repeated
   * calls preserve existing entries, including calls from additional taggers.
   * After the cache is full, uncached tokens are computed without being stored.
   * The cache is not serialized and is released with the model; its memory use
   * depends on token lengths and representation width.
   */
  public void enableRepresentationCache() {
    synchronized (this) {
      if (representationCache == null) {
        representationCache = new ConcurrentHashMap<>();
      }
    }
  }

  /**
   * Normalizes a token for vocabulary lookup with code-point lowercase conversion; symbols
   * are returned unchanged. This is the lookup contract both training and tagging
   * use, so evaluators may rely on it.
   *
   * @param token The token. Must not be {@code null}.
   * @return The lookup form. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code token} is {@code null}.
   */
  public static String normalize(String token) {
    if (token == null) {
      throw new IllegalArgumentException("token must not be null");
    }
    return token.startsWith(SYMBOL_PREFIX) ? token : StringUtil.toLowerCase(token);
  }

  /**
   * Maps a token to its word-vocabulary row, unknown words to row zero.
   *
   * @param token The token. Must not be {@code null}.
   * @return The embedding row.
   */
  int wordId(String token) {
    return words.getOrDefault(normalize(token), 0);
  }

  /**
   * Maps a token to its character-vocabulary rows, capped at {@link #maxWordLength()}
   * leading characters, unknown characters to row zero.
   *
   * @param token The token. Must not be {@code null}.
   * @return One row per kept character, empty for an empty token. Never {@code null}.
   */
  int[] charIds(String token) {
    final int length = Math.min(token.length(), maxWordLength);
    final int[] ids = new int[length];
    for (int i = 0; i < length; i++) {
      ids[i] = chars.getOrDefault(String.valueOf(token.charAt(i)), 0);
    }
    return ids;
  }

  /**
   * Looks up the frozen pretrained vector for a token.
   *
   * @param token The token. Must not be {@code null}.
   * @return The stored vector, or {@code null} when the model has no table or the
   *         token is not in it.
   */
  float[] pretrainedVector(String token) {
    if (pretrainedIds == null) {
      return null;
    }
    final Integer row = pretrainedIds.get(normalize(token));
    return row != null ? pretrainedVectors[row] : null;
  }

  /**
   * Builds the word representation of one token: learned word embedding, both
   * character BiLSTM final states, and the frozen pretrained vector (zeros when the
   * token has none). Allocates fresh result arrays, so concurrent calls are safe on a
   * model that is not being mutated.
   *
   * @param token The token. Must not be {@code null}.
   * @return The representation, length {@link #representationSize()}. Never
   *         {@code null}.
   */
  double[] wordRepresentation(String token) {
    final Map<String, double[]> cache = representationCache;
    if (cache != null) {
      final double[] cached = cache.get(token);
      if (cached != null) {
        return cached.clone();
      }
    }
    final double[] representation = computeRepresentation(token);
    if (cache != null && reserveRepresentation()) {
      boolean stored = false;
      try {
        stored = cache.putIfAbsent(token, representation.clone()) == null;
      } finally {
        if (!stored) {
          representationCacheRemaining.incrementAndGet();
        }
      }
    }
    return representation;
  }

  /**
   * Reserves one cache entry before copying and publishing a representation.
   * Unsuccessful insertions return the reservation in {@link #wordRepresentation(String)}.
   *
   * @return Whether capacity was available and reserved.
   */
  private boolean reserveRepresentation() {
    int remaining = representationCacheRemaining.get();
    while (remaining > 0) {
      if (representationCacheRemaining.compareAndSet(remaining, remaining - 1)) {
        return true;
      }
      remaining = representationCacheRemaining.get();
    }
    return false;
  }

  /**
   * Builds the word representation of one token from scratch, bypassing the cache.
   *
   * @param token The token. Must not be {@code null}.
   * @return The representation, length {@link #representationSize()}. Never
   *         {@code null}.
   */
  private double[] computeRepresentation(String token) {
    final double[] wordEmbedding = wordEmbeddings[wordId(token)];
    final int charHidden = charForward.hiddenSize();
    final double[] representation =
        new double[wordEmbedding.length + 2 * charHidden + pretrainedSize];
    System.arraycopy(wordEmbedding, 0, representation, 0, wordEmbedding.length);
    final int[] ids = charIds(token);
    if (ids.length > 0) {
      final double[][] charXs = new double[ids.length][];
      for (int i = 0; i < ids.length; i++) {
        charXs[i] = charEmbeddings[ids[i]];
      }
      final LstmLayer.ForwardCache fwdCache =
          LstmLayer.ForwardCache.of(ids.length, charHidden);
      final double[][] hFwd = charForward.run(charXs, fwdCache);
      System.arraycopy(hFwd[ids.length - 1], 0, representation, wordEmbedding.length,
          charHidden);
      final double[][] reversed = new double[ids.length][];
      for (int i = 0; i < ids.length; i++) {
        reversed[i] = charXs[ids.length - 1 - i];
      }
      final LstmLayer.ForwardCache bwdCache =
          LstmLayer.ForwardCache.of(ids.length, charHidden);
      final double[][] hBwd = charBackward.run(reversed, bwdCache);
      System.arraycopy(hBwd[ids.length - 1], 0, representation,
          wordEmbedding.length + charHidden, charHidden);
    }
    final float[] pretrained = pretrainedVector(token);
    if (pretrained != null) {
      final int offset = wordEmbedding.length + 2 * charHidden;
      if (adapterWeights != null) {
        applyAdapter(pretrained, adapterWeights, adapterBias, representation, offset);
      }
      else {
        for (int i = 0; i < pretrainedSize; i++) {
          representation[offset + i] = pretrained[i];
        }
      }
    }
    return representation;
  }

  /**
   * Writes one pretrained vector into a token representation through the adapter
   * projection, {@code target[offset + i] = bias[i] + sum over r of weights[r][i] *
   * vector[r]}. Training and tagging share this so a model scores at tagging time
   * exactly as it was fit.
   *
   * @param vector The pretrained row. Must not be {@code null}.
   * @param weights The square adapter projection. Must not be {@code null}.
   * @param bias The adapter bias. Must not be {@code null}.
   * @param target The representation to write into. Must not be {@code null}.
   * @param offset The index of the pretrained block inside {@code target}.
   */
  static void applyAdapter(float[] vector, double[][] weights, double[] bias,
      double[] target, int offset) {
    for (int i = 0; i < bias.length; i++) {
      double sum = bias[i];
      for (int r = 0; r < weights.length; r++) {
        sum += weights[r][i] * vector[r];
      }
      target[offset + i] = sum;
    }
  }

  /**
   * Scores every tag at every position of a sentence: encodes each token, runs the
   * sentence BiLSTM (both layers when stacked), and applies the linear tagger to the
   * final states.
   *
   * @param tokens The sentence. Must not be {@code null}, empty or contain null elements.
   * @return The finite, unnormalized tag scores, {@code [tokens.length][tags.length]}.
   *         Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code tokens} is {@code null}, empty
   *         or contains a null element.
   * @throws IllegalStateException Thrown if a computed tag score is not finite.
   */
  public double[][] score(String[] tokens) {
    if (tokens == null || tokens.length == 0) {
      throw new IllegalArgumentException("tokens must not be null or empty");
    }
    for (int t = 0; t < tokens.length; t++) {
      if (tokens[t] == null) {
        throw new IllegalArgumentException("tokens[" + t + "] must not be null");
      }
    }
    final int steps = tokens.length;
    final double[][] xs = new double[steps][];
    for (int t = 0; t < steps; t++) {
      xs[t] = wordRepresentation(tokens[t]);
    }
    double[][] states = bilstmEncode(xs, wordForward, wordBackward);
    if (wordForward2 != null) {
      states = bilstmEncode(states, wordForward2, wordBackward2);
    }
    final double[][] scores = new double[steps][tags.length];
    for (int t = 0; t < steps; t++) {
      for (int o = 0; o < tags.length; o++) {
        final double[] row = outputWeights[o];
        double sum = outputBias[o];
        for (int j = 0; j < states[t].length; j++) {
          sum += row[j] * states[t][j];
        }
        if (!Double.isFinite(sum)) {
          throw new IllegalStateException(
              "tag score must be finite at token " + t + ", tag " + o);
        }
        scores[t][o] = sum;
      }
    }
    return scores;
  }

  /**
   * Runs one bidirectional LSTM layer over a sequence and returns the per-position
   * concatenation of both directions' states.
   *
   * @param xs The input sequence. Must not be {@code null} or empty.
   * @param forward The forward layer. Must not be {@code null}.
   * @param backward The backward layer. Must not be {@code null}.
   * @return The concatenated states, {@code [T][2 * hidden]}. Never {@code null}.
   */
  private static double[][] bilstmEncode(double[][] xs, LstmLayer forward,
      LstmLayer backward) {
    final int steps = xs.length;
    final int hidden = forward.hiddenSize();
    final LstmLayer.ForwardCache fwdCache = LstmLayer.ForwardCache.of(steps, hidden);
    final double[][] hFwd = forward.run(xs, fwdCache);
    final double[][] reversed = new double[steps][];
    for (int t = 0; t < steps; t++) {
      reversed[t] = xs[steps - 1 - t];
    }
    final LstmLayer.ForwardCache bwdCache = LstmLayer.ForwardCache.of(steps, hidden);
    final double[][] hBwdRev = backward.run(reversed, bwdCache);
    final double[][] concat = new double[steps][2 * hidden];
    for (int t = 0; t < steps; t++) {
      System.arraycopy(hFwd[t], 0, concat[t], 0, hidden);
      System.arraycopy(hBwdRev[steps - 1 - t], 0, concat[t], hidden, hidden);
    }
    return concat;
  }

  /**
   * Serializes this model in the versioned {@code ONLP-BLPT} binary format. A model
   * carrying a pretrained adapter is written as {@code ONLP-BLPT-5}, whose body opens
   * with two boolean shape flags (stacked encoder, CRF output layer); in
   * {@code ONLP-BLPT-1} through {@code ONLP-BLPT-4} those two flags are encoded in the
   * magic itself, and every one of those layouts stays readable.
   *
   * @param out The stream to write to; not closed. Must not be {@code null}.
   * @throws IllegalArgumentException If {@code out} is {@code null}.
   * @throws IOException Thrown if writing fails.
   */
  public void serialize(OutputStream out) throws IOException {
    if (out == null) {
      throw new IllegalArgumentException("out must not be null");
    }
    final DataOutputStream data =
        new DataOutputStream(new BufferedOutputStream(out));
    if (adapterWeights != null) {
      data.writeUTF(MAGIC_ADAPTER);
      data.writeBoolean(wordForward2 != null);
      data.writeBoolean(isCrf());
    }
    else if (wordForward2 != null) {
      data.writeUTF(isCrf() ? MAGIC_TWO_LAYER_CRF : MAGIC_TWO_LAYER);
    }
    else {
      data.writeUTF(isCrf() ? MAGIC_CRF : MAGIC);
    }
    writeVocabulary(data, words);
    writeVocabulary(data, chars);
    data.writeInt(tags.length);
    for (final String tag : tags) {
      data.writeUTF(tag);
    }
    writeMatrix(data, wordEmbeddings);
    writeMatrix(data, charEmbeddings);
    writeLstm(data, charForward);
    writeLstm(data, charBackward);
    writeLstm(data, wordForward);
    writeLstm(data, wordBackward);
    if (wordForward2 != null) {
      writeLstm(data, wordForward2);
      writeLstm(data, wordBackward2);
    }
    writeMatrix(data, outputWeights);
    writeVector(data, outputBias);
    if (isCrf()) {
      writeMatrix(data, transitionWeights);
      writeVector(data, startWeights);
      writeVector(data, endWeights);
    }
    data.writeInt(maxWordLength);
    data.writeBoolean(pretrainedIds != null);
    if (pretrainedIds != null) {
      writeVocabulary(data, pretrainedIds);
      data.writeInt(pretrainedSize);
      data.writeInt(pretrainedVectors.length);
      for (final float[] row : pretrainedVectors) {
        for (final float value : row) {
          data.writeFloat(value);
        }
      }
    }
    if (adapterWeights != null) {
      writeMatrix(data, adapterWeights);
      writeVector(data, adapterBias);
    }
    data.flush();
  }

  /**
   * Serializes this model to a file, replacing any existing content.
   *
   * @param file The target path. Must not be {@code null}.
   * @throws IllegalArgumentException If {@code file} is {@code null}.
   * @throws IOException Thrown if writing fails.
   */
  public void serialize(Path file) throws IOException {
    if (file == null) {
      throw new IllegalArgumentException("file must not be null");
    }
    try (OutputStream out = Files.newOutputStream(file)) {
      serialize(out);
    }
  }

  /**
   * Loads a model from the versioned binary format, accepting {@code ONLP-BLPT-1}
   * through {@code ONLP-BLPT-5}; versions before 5 carry no adapter block.
   * Vocabularies, layer dimensions and finite parameter values are checked before
   * the model is returned. Parameter storage grows as values are read.
   *
   * @param in The stream to read from; not closed. Must not be {@code null}.
   * @return The loaded model. Never {@code null}.
   * @throws IllegalArgumentException If {@code in} is {@code null}.
   * @throws IOException If reading fails or the model content is invalid.
   */
  public static BilstmPOSModel load(InputStream in) throws IOException {
    if (in == null) {
      throw new IllegalArgumentException("in must not be null");
    }
    return new BilstmPOSModelReader(in).read();
  }

  /**
   * Loads a model from a file.
   *
   * @param file The model file. Must not be {@code null}.
   * @return The loaded model. Never {@code null}.
   * @throws IllegalArgumentException If {@code file} is {@code null}.
   * @throws IOException If reading fails or the model content is invalid.
   */
  public static BilstmPOSModel load(Path file) throws IOException {
    if (file == null) {
      throw new IllegalArgumentException("file must not be null");
    }
    try (InputStream in = Files.newInputStream(file)) {
      return load(in);
    }
  }

  /**
   * Writes one LSTM layer: its two sizes, then the input weights, recurrence weights,
   * and biases.
   *
   * @param data The stream to write to.
   * @param layer The layer to write.
   * @throws IOException Thrown if writing fails.
   */
  private static void writeLstm(DataOutputStream data, LstmLayer layer)
      throws IOException {
    data.writeInt(layer.inputSize());
    data.writeInt(layer.hiddenSize());
    writeMatrix(data, layer.w());
    writeMatrix(data, layer.u());
    writeVector(data, layer.b());
  }

  /**
   * Writes a vocabulary as its size followed by symbol and row pairs, in iteration
   * order.
   *
   * @param data The stream to write to.
   * @param vocabulary The vocabulary to write.
   * @throws IOException Thrown if writing fails.
   */
  private static void writeVocabulary(DataOutputStream data,
      LinkedHashMap<String, Integer> vocabulary) throws IOException {
    data.writeInt(vocabulary.size());
    for (final Map.Entry<String, Integer> entry : vocabulary.entrySet()) {
      data.writeUTF(entry.getKey());
      data.writeInt(entry.getValue());
    }
  }

  /**
   * Writes a rectangular matrix as its two dimensions followed by the values, row major.
   *
   * @param data The stream to write to.
   * @param matrix The matrix to write; must have at least one row.
   * @throws IOException Thrown if writing fails.
   */
  private static void writeMatrix(DataOutputStream data, double[][] matrix)
      throws IOException {
    data.writeInt(matrix.length);
    data.writeInt(matrix[0].length);
    for (final double[] row : matrix) {
      for (final double value : row) {
        data.writeDouble(value);
      }
    }
  }

  /**
   * Writes a vector as its length followed by the values.
   *
   * @param data The stream to write to.
   * @param vector The vector to write.
   * @throws IOException Thrown if writing fails.
   */
  private static void writeVector(DataOutputStream data, double[] vector)
      throws IOException {
    data.writeInt(vector.length);
    for (final double value : vector) {
      data.writeDouble(value);
    }
  }

}
