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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import opennlp.tools.util.StringUtil;

/**
 * The weights of the feedforward tagger: embeddings for words, suffixes, word shapes,
 * and previous tags, one hidden layer with cube activation, and a tag output layer,
 * stored in a versioned binary format.
 *
 * <p>The network is executed with ordinary array arithmetic, so tagging needs no native
 * runtime. Unknown words and suffixes fall back to learned unknown symbols; words are
 * matched case-insensitively, with capitalization carried by the shape features instead.
 * {@link FeedforwardPOSTrainer} modifies the weight arrays during training.
 * Trained and loaded models can be shared between threads.</p>
 *
 * <p>A model trained with pretrained word vectors stores those vectors, so
 * inference needs no embedding component, and a word without a stored vector scores as
 * zeros. Models use format marker {@code ONLP-FFPT-2} with this block and
 * {@code ONLP-FFPT-1} without it.</p>
 *
 * @see FeedforwardPOSTagger
 * @see FeedforwardPOSTrainer
 * @since 3.0.0
 */
public class FeedforwardPOSModel {

  /** The format of models with only learned embeddings. */
  private static final String MAGIC = "ONLP-FFPT-1";

  /** The format of models with the optional pretrained word-vector block. */
  private static final String MAGIC_PRETRAINED = "ONLP-FFPT-2";

  /** Maximum entry or value count in a binary-format field. */
  private static final int MAX_LENGTH = 1 << 30;

  /** Initial allocation limit when reading vector values. */
  private static final int READ_BUFFER_SIZE = 4096;

  /** The row marker of a word without a stored pretrained vector; scores as zeros. */
  static final int NO_VECTOR = -1;

  /** The symbol every vocabulary maps a value outside it to. */
  static final String UNKNOWN = "*UNK*";

  /** The symbol every vocabulary maps a position outside the sentence to. */
  static final String ABSENT = "*NULL*";

  /** The lazy scoring cache; {@code null} until {@link #enableScoringCache()}. */
  private volatile ContributionCache cache;

  private final Map<String, Integer> wordIds;
  private final Map<String, Integer> suffixIds;
  private final Map<String, Integer> shapeIds;
  private final Map<String, Integer> tagIds;
  private final String[] tags;

  private final int embeddingSize;
  private final float[][] embeddings;
  private final float[][] hiddenWeights;
  private final float[] hiddenBias;
  private final float[][] outputWeights;
  private final float[] outputBias;

  /** The pretrained vector dimensionality; {@code 0} on models without the block. */
  private final int pretrainedSize;
  private final Map<String, Integer> pretrainedIds;
  private final float[][] pretrainedVectors;

  /**
   * Initializes a model from vocabularies and weight arrays. The arrays are
   * taken over without copying. Only the trainer may modify them, during training.
   *
   * @param wordIds The word symbol to embedding row mapping.
   * @param suffixIds The suffix symbol to embedding row mapping.
   * @param shapeIds The shape symbol to embedding row mapping.
   * @param tagIds The tag symbol to embedding row mapping.
   * @param tags The tag inventory by output index.
   * @param embeddingSize The embedding dimensionality.
   * @param embeddings The embedding matrix, one row per symbol.
   * @param hiddenWeights The hidden layer weight matrix.
   * @param hiddenBias The hidden layer bias vector.
   * @param outputWeights The output layer weight matrix.
   * @param outputBias The output layer bias vector.
   */
  FeedforwardPOSModel(Map<String, Integer> wordIds, Map<String, Integer> suffixIds,
      Map<String, Integer> shapeIds, Map<String, Integer> tagIds, String[] tags,
      int embeddingSize, float[][] embeddings, float[][] hiddenWeights,
      float[] hiddenBias, float[][] outputWeights, float[] outputBias) {
    this(wordIds, suffixIds, shapeIds, tagIds, tags, embeddingSize, embeddings,
        hiddenWeights, hiddenBias, outputWeights, outputBias, 0, Map.of(), new float[0][]);
  }

  /**
   * Initializes a model with the optional pretrained word-vector block. The trainer
   * and the loader are the only callers; the arrays are taken over without copying.
   *
   * @param wordIds The word symbol to embedding row mapping.
   * @param suffixIds The suffix symbol to embedding row mapping.
   * @param shapeIds The shape symbol to embedding row mapping.
   * @param tagIds The tag symbol to embedding row mapping.
   * @param tags The tag inventory by output index.
   * @param embeddingSize The embedding dimensionality.
   * @param embeddings The embedding matrix, one row per symbol.
   * @param hiddenWeights The hidden layer weight matrix.
   * @param hiddenBias The hidden layer bias vector.
   * @param outputWeights The output layer weight matrix.
   * @param outputBias The output layer bias vector.
   * @param pretrainedSize The pretrained vector dimensionality, {@code 0} when absent.
   * @param pretrainedIds The normalized word to vector row mapping; empty when absent.
   * @param pretrainedVectors The stored vector slice, one row per mapped word.
   */
  FeedforwardPOSModel(Map<String, Integer> wordIds, Map<String, Integer> suffixIds,
      Map<String, Integer> shapeIds, Map<String, Integer> tagIds, String[] tags,
      int embeddingSize, float[][] embeddings, float[][] hiddenWeights,
      float[] hiddenBias, float[][] outputWeights, float[] outputBias,
      int pretrainedSize, Map<String, Integer> pretrainedIds, float[][] pretrainedVectors) {
    this.wordIds = wordIds;
    this.suffixIds = suffixIds;
    this.shapeIds = shapeIds;
    this.tagIds = tagIds;
    this.tags = tags;
    this.embeddingSize = embeddingSize;
    this.embeddings = embeddings;
    this.hiddenWeights = hiddenWeights;
    this.hiddenBias = hiddenBias;
    this.outputWeights = outputWeights;
    this.outputBias = outputBias;
    this.pretrainedSize = pretrainedSize;
    this.pretrainedIds = pretrainedIds;
    this.pretrainedVectors = pretrainedVectors;
  }

  /**
   * Scores every tag for a position described by embedding row indices. Only valid on
   * models without the pretrained vector block; a model trained with word vectors
   * scores through {@link #score(int[], int[])}, and scoring it without its block
   * would silently treat every word as unknown to the vectors.
   *
   * @param features The embedding rows of the position, as produced by
   *                 {@link #featureIds(String[])}. Must not be {@code null}.
   * @return One unnormalized score per tag, indexed like {@link #tags()}. Never
   *         {@code null}.
   * @throws IllegalArgumentException Thrown if {@code features} is {@code null} or this
   *         model carries pretrained vectors.
   */
  double[] score(int[] features) {
    return score(features, null);
  }

  /**
   * Scores every tag for a position described by embedding row indices and, on models
   * trained with word vectors, the pretrained rows of the vector window.
   *
   * @param features The embedding rows of the position, as produced by
   *                 {@link #featureIds(String[])}. Must not be {@code null}.
   * @param pretrainedRows The vector rows of the window, as produced by
   *                       {@link #pretrainedRows(String[], int)}, where
   *                       {@link #NO_VECTOR} scores as zeros; {@code null} on models
   *                       without the block.
   * @return One unnormalized score per tag, indexed like {@link #tags()}. Never
   *         {@code null}.
   * @throws IllegalArgumentException Thrown if {@code features} is {@code null}, if
   *         this model carries pretrained vectors and {@code pretrainedRows} is
   *         {@code null} or the other way round, or if {@code pretrainedRows} does not
   *         hold {@link FeedforwardPOSContext#PRETRAINED_SLOTS} rows.
   */
  double[] score(int[] features, int[] pretrainedRows) {
    if (features == null) {
      throw new IllegalArgumentException("features must not be null");
    }
    if ((pretrainedSize > 0) != (pretrainedRows != null)) {
      throw new IllegalArgumentException(pretrainedSize > 0
          ? "this model was trained with word vectors and needs their rows to score"
          : "this model was trained without word vectors and cannot score vector rows");
    }
    if (pretrainedSize > 0 && pretrainedRows.length != FeedforwardPOSContext.PRETRAINED_SLOTS) {
      throw new IllegalArgumentException("the vector window has "
          + FeedforwardPOSContext.PRETRAINED_SLOTS + " slots, got: " + pretrainedRows.length);
    }
    final int hidden = hiddenBias.length;
    final double[] h = new double[hidden];
    for (int j = 0; j < hidden; j++) {
      h[j] = hiddenBias[j];
    }
    final ContributionCache cache = this.cache;
    for (int f = 0; f < features.length; f++) {
      final int row = features[f];
      final double[] contribution = cache == null ? null : cache.contribution(this, f, row);
      if (contribution != null) {
        for (int j = 0; j < hidden; j++) {
          h[j] += contribution[j];
        }
      } else {
        final float[] embedding = embeddings[row];
        final int offset = f * embeddingSize;
        for (int j = 0; j < hidden; j++) {
          final float[] weights = hiddenWeights[j];
          double sum = 0.0;
          for (int d = 0; d < embeddingSize; d++) {
            sum += (double) weights[offset + d] * embedding[d];
          }
          h[j] += sum;
        }
      }
    }
    if (pretrainedSize > 0) {
      final int base = FeedforwardPOSContext.SLOTS * embeddingSize;
      for (int p = 0; p < pretrainedRows.length; p++) {
        final int row = pretrainedRows[p];
        if (row == NO_VECTOR) {
          continue;
        }
        final float[] vector = pretrainedVectors[row];
        final int offset = base + p * pretrainedSize;
        for (int j = 0; j < hidden; j++) {
          final float[] weights = hiddenWeights[j];
          double sum = 0.0;
          for (int d = 0; d < pretrainedSize; d++) {
            sum += (double) weights[offset + d] * vector[d];
          }
          h[j] += sum;
        }
      }
    }
    for (int j = 0; j < hidden; j++) {
      h[j] = h[j] * h[j] * h[j];
    }
    final double[] scores = new double[tags.length];
    for (int o = 0; o < scores.length; o++) {
      final float[] row = outputWeights[o];
      double sum = outputBias[o];
      for (int j = 0; j < hidden; j++) {
        sum += row[j] * h[j];
      }
      scores[o] = sum;
    }
    return scores;
  }

  /**
   * Caches hidden-layer contributions by feature slot and embedding row, following
   * <a href="https://aclanthology.org/D14-1082/">Chen and Manning (2014)</a>.
   *
   * <p>Call this after training. Contributions retain double precision so cached and
   * direct scoring use the same values.</p>
   */
  synchronized void enableScoringCache() {
    if (cache == null) {
      cache = new ContributionCache(FeedforwardPOSContext.SLOTS, embeddings.length);
    }
  }

  /**
   * Caches complete contribution vectors on first use. Concurrent callers may compute
   * the same entry before one publishes it. Uncached pairs use direct scoring after
   * the entry budget is exhausted.
   */
  private static final class ContributionCache {

    /** The most (slot, row) pairs the cache will hold. */
    private static final int MAX_PAIRS = 32768;

    private final AtomicReferenceArray<double[]>[] bySlot;
    private final AtomicInteger remaining = new AtomicInteger(MAX_PAIRS);

    /**
     * Allocates one reference array per feature slot.
     *
     * @param slots The number of feature slots.
     * @param rows The number of embedding rows.
     */
    @SuppressWarnings("unchecked")
    private ContributionCache(int slots, int rows) {
      bySlot = new AtomicReferenceArray[slots];
      for (int f = 0; f < slots; f++) {
        bySlot[f] = new AtomicReferenceArray<>(rows);
      }
    }

    /**
     * Returns the cached hidden-layer contribution of one pair, computing and
     * publishing it on first sight while the budget lasts.
     *
     * @param model The model providing the weights.
     * @param slot The template slot.
     * @param row The embedding row at that slot.
     * @return The contribution vector, or {@code null} when the budget is spent and
     *         the pair is not cached.
     */
    private double[] contribution(FeedforwardPOSModel model, int slot, int row) {
      final AtomicReferenceArray<double[]> slots = bySlot[slot];
      double[] contribution = slots.get(row);
      if (contribution != null) {
        return contribution;
      }
      if (!reserve()) {
        return null;
      }
      boolean published = false;
      try {
        final int hidden = model.hiddenBias.length;
        final float[] embedding = model.embeddings[row];
        final int offset = slot * model.embeddingSize;
        contribution = new double[hidden];
        for (int j = 0; j < hidden; j++) {
          final float[] weights = model.hiddenWeights[j];
          double sum = 0.0;
          for (int d = 0; d < model.embeddingSize; d++) {
            sum += (double) weights[offset + d] * embedding[d];
          }
          contribution[j] = sum;
        }
        published = slots.compareAndSet(row, null, contribution);
        return published ? contribution : slots.get(row);
      } finally {
        if (!published) {
          remaining.incrementAndGet();
        }
      }
    }

    /**
     * Reserves capacity for a contribution before allocating its array.
     *
     * @return Whether one entry was reserved.
     */
    private boolean reserve() {
      int available = remaining.get();
      while (available > 0) {
        if (remaining.compareAndSet(available, available - 1)) {
          return true;
        }
        available = remaining.get();
      }
      return false;
    }
  }

  /**
   * Maps the symbolic features of the tagger template onto embedding rows.
   *
   * @param symbols The symbolic features, as produced by
   *                {@link FeedforwardPOSContext#extract(String[], int, String, String)}.
   *                Must not be {@code null}.
   * @return The embedding row per feature. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code symbols} is {@code null} or does
   *         not hold {@link FeedforwardPOSContext#SLOTS} symbols.
   */
  int[] featureIds(String[] symbols) {
    if (symbols == null) {
      throw new IllegalArgumentException("symbols must not be null");
    }
    if (symbols.length != FeedforwardPOSContext.SLOTS) {
      throw new IllegalArgumentException("the feature template has "
          + FeedforwardPOSContext.SLOTS + " slots, got: " + symbols.length);
    }
    final int[] ids = new int[symbols.length];
    int slot = 0;
    for (int i = 0; i < FeedforwardPOSContext.WORD_SLOTS; i++, slot++) {
      ids[slot] = lookup(wordIds, normalize(symbols[slot]));
    }
    for (int i = 0; i < FeedforwardPOSContext.SUFFIX_SLOTS; i++, slot++) {
      ids[slot] = lookup(suffixIds, symbols[slot]);
    }
    for (int i = 0; i < FeedforwardPOSContext.SHAPE_SLOTS; i++, slot++) {
      ids[slot] = lookup(shapeIds, symbols[slot]);
    }
    for (int i = 0; i < FeedforwardPOSContext.TAG_SLOTS; i++, slot++) {
      ids[slot] = lookup(tagIds, symbols[slot]);
    }
    return ids;
  }

  /**
   * @return The tag inventory by output index. Never {@code null}.
   */
  public String[] tags() {
    return tags.clone();
  }

  /**
   * {@return {@code true} if this model was trained with pretrained word vectors}
   * Such a model stores the vectors of its training words inside itself and scores a
   * word without a stored vector as zeros.
   */
  public boolean usesPretrainedVectors() {
    return pretrainedSize > 0;
  }

  /**
   * Maps the vector window of a position onto stored vector rows, spanning the same
   * words as the word window and normalizing each like the word vocabulary. A position
   * outside the sentence and a word without a stored vector map to {@link #NO_VECTOR},
   * which scores as zeros.
   *
   * @param sentence The sentence tokens. Must not be {@code null}.
   * @param index The position to tag.
   * @return One row per window slot, or {@code null} on a model without the block.
   * @throws IllegalArgumentException Thrown if {@code sentence} is {@code null}.
   */
  int[] pretrainedRows(String[] sentence, int index) {
    if (sentence == null) {
      throw new IllegalArgumentException("sentence must not be null");
    }
    if (pretrainedSize == 0) {
      return null;
    }
    final int[] rows = new int[FeedforwardPOSContext.PRETRAINED_SLOTS];
    for (int p = 0; p < rows.length; p++) {
      final int position = index + p - FeedforwardPOSContext.WINDOW_RADIUS;
      if (position < 0 || position >= sentence.length) {
        rows[p] = NO_VECTOR;
      } else {
        final Integer row = pretrainedIds.get(normalize(sentence[position]));
        rows[p] = row == null ? NO_VECTOR : row;
      }
    }
    return rows;
  }

  /**
   * Lowercases a word symbol for the case-insensitive vocabulary lookup. Special
   * symbols starting with {@code *} and absent positions pass through unchanged.
   *
   * @param word The word symbol, or {@code null} for an absent position.
   * @return The normalized symbol, or {@code null} if {@code word} was {@code null}.
   */
  static String normalize(String word) {
    if (word == null) {
      return null;
    }
    return word.startsWith("*") ? word : StringUtil.toLowerCase(word);
  }

  /**
   * Resolves a symbol to its embedding row. An absent position maps to the padding
   * symbol and a symbol outside the vocabulary maps to the unknown symbol, so the
   * lookup always succeeds.
   *
   * @param ids The vocabulary to look the symbol up in.
   * @param symbol The symbol, or {@code null} for an absent position.
   * @return The embedding row index.
   */
  private int lookup(Map<String, Integer> ids, String symbol) {
    Integer id = ids.get(symbol == null ? ABSENT : symbol);
    if (id == null) {
      id = ids.get(UNKNOWN);
    }
    return id;
  }

  /**
   * Writes the model in the versioned binary format.
   *
   * @param out The stream to write to. Must not be {@code null}. Not closed.
   * @throws IOException Thrown if writing fails.
   * @throws IllegalArgumentException Thrown if {@code out} is {@code null}.
   */
  public void serialize(OutputStream out) throws IOException {
    if (out == null) {
      throw new IllegalArgumentException("out must not be null");
    }
    final DataOutputStream data = new DataOutputStream(new BufferedOutputStream(out));
    data.writeUTF(pretrainedSize > 0 ? MAGIC_PRETRAINED : MAGIC);
    writeVocabulary(data, wordIds);
    writeVocabulary(data, suffixIds);
    writeVocabulary(data, shapeIds);
    writeVocabulary(data, tagIds);
    data.writeInt(tags.length);
    for (final String tag : tags) {
      data.writeUTF(tag);
    }
    data.writeInt(embeddingSize);
    writeMatrix(data, embeddings);
    writeMatrix(data, hiddenWeights);
    writeVector(data, hiddenBias);
    writeMatrix(data, outputWeights);
    writeVector(data, outputBias);
    if (pretrainedSize > 0) {
      data.writeInt(pretrainedSize);
      writeVocabulary(data, pretrainedIds);
      writeMatrix(data, pretrainedVectors);
    }
    data.flush();
  }

  /**
   * Loads a model from the versioned binary format.
   *
   * @param in The stream to read from. Must not be {@code null}. Not closed.
   * @return The loaded model. Never {@code null}.
   * @throws IOException If reading fails, the format or dimensions are invalid,
   *         vocabulary entries are invalid, or a weight is non-finite.
   * @throws IllegalArgumentException Thrown if {@code in} is {@code null}.
   */
  public static FeedforwardPOSModel load(InputStream in) throws IOException {
    if (in == null) {
      throw new IllegalArgumentException("in must not be null");
    }
    final DataInputStream data = new DataInputStream(new BufferedInputStream(in));
    final String magic = data.readUTF();
    final boolean pretrained = MAGIC_PRETRAINED.equals(magic);
    if (!pretrained && !MAGIC.equals(magic)) {
      throw new IOException("not a feedforward tagger model: " + magic);
    }
    final Map<String, Integer> wordIds = readVocabulary(data, "word vocabulary size");
    final Map<String, Integer> suffixIds = readVocabulary(data, "suffix vocabulary size");
    final Map<String, Integer> shapeIds = readVocabulary(data, "shape vocabulary size");
    final Map<String, Integer> tagIds = readVocabulary(data, "tag vocabulary size");
    final int tagCount = readLength(data, "tag count");
    final List<String> tagList = new ArrayList<>();
    final Set<String> distinctTags = new HashSet<>();
    for (int i = 0; i < tagCount; i++) {
      final String tag = data.readUTF();
      if (!distinctTags.add(tag)) {
        throw new IOException("duplicate output tag: " + tag);
      }
      tagList.add(tag);
    }
    final String[] tags = tagList.toArray(String[]::new);
    final int embeddingSize = readLength(data, "embedding size");
    final float[][] embeddings = readMatrix(data, "embedding matrix");
    final float[][] hiddenWeights = readMatrix(data, "hidden weight matrix");
    final float[] hiddenBias = readVector(data, "hidden bias");
    final float[][] outputWeights = readMatrix(data, "output weight matrix");
    final float[] outputBias = readVector(data, "output bias");
    final int pretrainedSize = pretrained ? readLength(data, "pretrained vector size") : 0;
    final Map<String, Integer> pretrainedIds = pretrained
        ? readVocabulary(data, "pretrained vocabulary size") : Map.of();
    final float[][] pretrainedVectors = pretrained
        ? readMatrix(data, "pretrained vector matrix") : new float[0][];
    final FeedforwardPOSModel model = new FeedforwardPOSModel(
        wordIds, suffixIds, shapeIds, tagIds, tags,
        embeddingSize, embeddings, hiddenWeights, hiddenBias, outputWeights, outputBias,
        pretrainedSize, pretrainedIds, pretrainedVectors);
    model.validate(pretrained);
    return model;
  }

  /**
   * Checks the relationships between decoded fields before the model can be scored.
   *
   * @param pretrained Whether the format declares a pretrained vector block.
   * @throws IOException If the model cannot be indexed with the tagger's feature template.
   */
  private void validate(boolean pretrained) throws IOException {
    if (tags.length == 0) {
      throw new IOException("tag count must be positive");
    }
    if (embeddingSize == 0 || embeddings.length == 0) {
      throw new IOException("embedding size and row count must be positive");
    }
    if (hiddenBias.length == 0) {
      throw new IOException("hidden layer size must be positive");
    }
    if (pretrained && pretrainedSize == 0) {
      throw new IOException("pretrained vector size must be positive");
    }
    final long inputSize = (long) FeedforwardPOSContext.SLOTS * embeddingSize
        + (long) FeedforwardPOSContext.PRETRAINED_SLOTS * pretrainedSize;
    checkMatrix(embeddings, embeddings.length, embeddingSize, "embedding matrix");
    checkMatrix(hiddenWeights, hiddenBias.length, inputSize, "hidden weight matrix");
    checkMatrix(outputWeights, tags.length, hiddenBias.length, "output weight matrix");
    if (outputBias.length != tags.length) {
      throw new IOException("output bias length must match the tag count");
    }
    checkVocabulary(wordIds, embeddings.length, "word vocabulary", true);
    checkVocabulary(suffixIds, embeddings.length, "suffix vocabulary", true);
    checkVocabulary(shapeIds, embeddings.length, "shape vocabulary", true);
    checkVocabulary(tagIds, embeddings.length, "tag vocabulary", true);
    checkMatrix(pretrainedVectors, pretrainedVectors.length, pretrainedSize,
        "pretrained vector matrix");
    checkVocabulary(pretrainedIds, pretrainedVectors.length, "pretrained vocabulary", false);
  }

  /**
   * Checks a matrix against the dimensions required by the scoring code.
   *
   * @param matrix The decoded matrix.
   * @param rows The required row count.
   * @param columns The required column count.
   * @param field The matrix name for error messages.
   * @throws IOException If the dimensions do not match.
   */
  private void checkMatrix(float[][] matrix, int rows, long columns, String field)
      throws IOException {
    if (matrix.length != rows || (matrix.length > 0 && matrix[0].length != columns)) {
      throw new IOException(field + " must have " + rows + " rows and " + columns + " columns");
    }
  }

  /**
   * Checks fallback symbols and row indices without requiring distinct row assignments.
   *
   * @param ids The decoded vocabulary.
   * @param rows The number of available embedding rows.
   * @param field The vocabulary name for error messages.
   * @param requireUnknown Whether lookups use an unknown-symbol fallback.
   * @throws IOException If a required symbol is absent or a row is outside the matrix.
   */
  private void checkVocabulary(Map<String, Integer> ids, int rows, String field,
      boolean requireUnknown) throws IOException {
    if (requireUnknown && !ids.containsKey(UNKNOWN)) {
      throw new IOException(field + " must contain " + UNKNOWN);
    }
    for (final Map.Entry<String, Integer> entry : ids.entrySet()) {
      if (entry.getValue() < 0 || entry.getValue() >= rows) {
        throw new IOException(field + " has an invalid row for " + entry.getKey()
            + ": " + entry.getValue());
      }
    }
  }

  /**
   * Loads a model from a file.
   *
   * @param path The file to read. Must not be {@code null}.
   * @return The loaded model. Never {@code null}.
   * @throws IOException If reading fails or the model data is invalid.
   * @throws IllegalArgumentException Thrown if {@code path} is {@code null}.
   */
  public static FeedforwardPOSModel load(Path path) throws IOException {
    if (path == null) {
      throw new IllegalArgumentException("path must not be null");
    }
    try (InputStream in = Files.newInputStream(path)) {
      return load(in);
    }
  }

  /** @return The embedding matrix, exposed to the trainer for in-place updates. */
  float[][] embeddings() {
    return embeddings;
  }

  /** @return The hidden layer weights, exposed to the trainer for in-place updates. */
  float[][] hiddenWeights() {
    return hiddenWeights;
  }

  /** @return The hidden layer bias, exposed to the trainer for in-place updates. */
  float[] hiddenBias() {
    return hiddenBias;
  }

  /** @return The output layer weights, exposed to the trainer for in-place updates. */
  float[][] outputWeights() {
    return outputWeights;
  }

  /** @return The output layer bias, exposed to the trainer for in-place updates. */
  float[] outputBias() {
    return outputBias;
  }

  /** @return The word symbol to embedding row mapping. */
  Map<String, Integer> wordIds() {
    return wordIds;
  }

  /** @return The pretrained vector dimensionality; {@code 0} without the block. */
  int pretrainedSize() {
    return pretrainedSize;
  }

  /** @return The stored pretrained vectors, exposed to the trainer's forward pass. */
  float[][] pretrainedVectors() {
    return pretrainedVectors;
  }

  /**
   * Writes a vocabulary as its size followed by every symbol and row index pair.
   *
   * @param data The stream to write to.
   * @param ids The vocabulary to write.
   * @throws IOException Thrown if writing fails.
   */
  private void writeVocabulary(DataOutputStream data, Map<String, Integer> ids)
      throws IOException {
    data.writeInt(ids.size());
    for (final Map.Entry<String, Integer> entry : ids.entrySet()) {
      data.writeUTF(entry.getKey());
      data.writeInt(entry.getValue());
    }
  }

  /**
   * Reads a length field within the binary format's supported range.
   *
   * @param data The stream to read from.
   * @param field The name of the field, used in the error message.
   * @return The validated length, in {@code [0, MAX_LENGTH]}.
   * @throws IOException Thrown if reading fails or the length is negative or larger
   *         than {@link #MAX_LENGTH}.
   */
  private static int readLength(DataInputStream data, String field) throws IOException {
    final int length = data.readInt();
    if (length < 0 || length > MAX_LENGTH) {
      throw new IOException("implausible " + field + ": " + length);
    }
    return length;
  }

  /**
   * Reads a vocabulary written by {@link #writeVocabulary}, preserving entry order.
   *
   * @param data The stream to read from.
   * @param field The name of the size field, used in the error message.
   * @return The restored vocabulary. Never {@code null}.
   * @throws IOException If reading fails, the size field is invalid or a symbol is repeated.
   */
  private static Map<String, Integer> readVocabulary(DataInputStream data, String field)
      throws IOException {
    final int size = readLength(data, field);
    final Map<String, Integer> ids = new LinkedHashMap<>();
    for (int i = 0; i < size; i++) {
      final String key = data.readUTF();
      if (ids.putIfAbsent(key, data.readInt()) != null) {
        throw new IOException("duplicate symbol in " + field + ": " + key);
      }
    }
    return ids;
  }

  /**
   * Writes a matrix as its row and column counts followed by the values in row order.
   *
   * @param data The stream to write to.
   * @param matrix The matrix to write.
   * @throws IOException Thrown if writing fails.
   */
  private void writeMatrix(DataOutputStream data, float[][] matrix)
      throws IOException {
    data.writeInt(matrix.length);
    data.writeInt(matrix.length == 0 ? 0 : matrix[0].length);
    for (final float[] row : matrix) {
      for (final float value : row) {
        data.writeFloat(value);
      }
    }
  }

  /**
   * Reads a matrix written by {@link #writeMatrix}.
   *
   * @param data The stream to read from.
   * @param field The name of the matrix, used in the error message.
   * @return The restored matrix. Never {@code null}.
   * @throws IOException If reading fails, a dimension is invalid, the element count
   *         exceeds the format limit, or a value is non-finite.
   */
  private static float[][] readMatrix(DataInputStream data, String field)
      throws IOException {
    final int rows = readLength(data, field + " rows");
    final int columns = readLength(data, field + " columns");
    if ((long) rows * columns > MAX_LENGTH) {
      throw new IOException(
          "implausible " + field + " element count: " + rows + " x " + columns);
    }
    if ((rows == 0) != (columns == 0)) {
      throw new IOException(field + " must have both dimensions zero or both positive");
    }
    final List<float[]> matrix = new ArrayList<>();
    for (int r = 0; r < rows; r++) {
      matrix.add(readValues(data, columns, field));
    }
    return matrix.toArray(float[][]::new);
  }

  /**
   * Writes a vector as its length followed by the values.
   *
   * @param data The stream to write to.
   * @param vector The vector to write.
   * @throws IOException Thrown if writing fails.
   */
  private void writeVector(DataOutputStream data, float[] vector)
      throws IOException {
    data.writeInt(vector.length);
    for (final float value : vector) {
      data.writeFloat(value);
    }
  }

  /**
   * Reads a vector written by {@link #writeVector}.
   *
   * @param data The stream to read from.
   * @param field The name of the vector, used in the error message.
   * @return The restored vector. Never {@code null}.
   * @throws IOException If reading fails, the length is invalid or a value is non-finite.
   */
  private static float[] readVector(DataInputStream data, String field)
      throws IOException {
    return readValues(data, readLength(data, field + " length"), field);
  }

  /**
   * Reads finite values, growing storage only after the next value has been read.
   *
   * @param data The stream to read from.
   * @param length The declared value count.
   * @param field The field name for error messages.
   * @return The decoded values.
   * @throws IOException If reading fails or a value is non-finite.
   */
  private static float[] readValues(DataInputStream data, int length, String field)
      throws IOException {
    float[] vector = new float[Math.min(length, READ_BUFFER_SIZE)];
    for (int i = 0; i < length; i++) {
      final float value = data.readFloat();
      if (!Float.isFinite(value)) {
        throw new IOException("non-finite value in " + field);
      }
      if (i == vector.length) {
        vector = Arrays.copyOf(vector, Math.min(length, vector.length * 2));
      }
      vector[i] = value;
    }
    return vector;
  }
}
