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

package opennlp.dl.vectors;

import java.io.File;
import java.io.IOException;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import opennlp.dl.AbstractDL;
import opennlp.dl.InferenceOptions;
import opennlp.tools.coref.TokenVectors;

/**
 * Produces one contextual vector per word of a sentence from a BERT-style encoder
 * exported to ONNX, such as a sentence-transformers model whose first output is the
 * last hidden state. Each word is wordpiece-tokenized on its own, so its pieces are
 * known, and the word's vector is the mean of its pieces' hidden states; a word that
 * yields no piece gets a zero vector. Words are packed into windows of at most
 * {@value #MAX_PIECES} pieces including the classification and separator tokens, each
 * window encoded on its own, so long sentences never exceed the encoder's input length.
 *
 * <p>Instances are thread-safe once constructed and until {@link #close()} is called.</p>
 *
 * @since 3.0.0
 */
public class TokenVectorsDL extends AbstractDL implements TokenVectors {

  /** The most pieces one encoder input holds, the BERT position limit. */
  public static final int MAX_PIECES = 512;

  /** The pieces a window spends on the classification and separator tokens. */
  private static final int SPECIAL_PIECES = 2;

  /** The classification and separator token ids, in that order. */
  private final long[] specials;

  /** Whether to supply an attention mask to the model. */
  private final boolean includeAttentionMask;

  /** Whether to supply token type ids to the model. */
  private final boolean includeTokenTypeIds;

  /** The hidden-state dimension declared by the ONNX model. */
  private final int dimension;

  /**
   * Initializes the encoder for an uncased model.
   *
   * @param model The ONNX model file. Must not be {@code null}.
   * @param vocabulary The vocabulary file. Must not be {@code null}.
   * @throws OrtException Thrown if the model cannot be loaded.
   * @throws IOException Thrown if the model or vocabulary cannot be read.
   * @throws IllegalArgumentException Thrown if an argument is {@code null}.
   */
  public TokenVectorsDL(final File model, final File vocabulary)
      throws OrtException, IOException {
    this(model, vocabulary, true);
  }

  /**
   * Initializes the encoder.
   *
   * @param model The ONNX model file. Must not be {@code null}.
   * @param vocabulary The vocabulary file. Must not be {@code null}.
   * @param lowerCase {@code true} for an uncased model, {@code false} for a cased one.
   * @throws OrtException Thrown if the model cannot be loaded.
   * @throws IOException Thrown if the model or vocabulary cannot be read.
   * @throws IllegalArgumentException Thrown if a file argument is {@code null}.
   */
  public TokenVectorsDL(final File model, final File vocabulary, final boolean lowerCase)
      throws OrtException, IOException {
    this(model, vocabulary, lowerCase, new InferenceOptions());
  }

  /**
   * Initializes the encoder with inference options for execution providers and optional
   * model inputs.
   *
   * @param model The ONNX model file. Must not be {@code null}.
   * @param vocabulary The vocabulary file. Must not be {@code null}.
   * @param lowerCase {@code true} for an uncased model, {@code false} for a cased one.
   * @param options The inference options. Must not be {@code null}.
   * @throws OrtException Thrown if the model cannot be loaded.
   * @throws IOException Thrown if the model or vocabulary cannot be read.
   * @throws IllegalArgumentException Thrown if a reference argument is {@code null} or
   *         the model does not declare a positive hidden-state dimension.
   */
  public TokenVectorsDL(final File model, final File vocabulary, final boolean lowerCase,
      final InferenceOptions options) throws OrtException, IOException {
    super(model, vocabulary, sessionOptions(options), lowerCase);
    this.specials = specials();
    this.includeAttentionMask = options.isIncludeAttentionMask();
    this.includeTokenTypeIds = options.isIncludeTokenTypeIds();
    this.dimension = outputDimension();
  }

  /**
   * Initializes an encoder over shared state, for tests that exercise the tokenization
   * and windowing without a model.
   *
   * @param env The ONNX environment, or {@code null}.
   * @param session The ONNX session, or {@code null}.
   * @param vocab The vocabulary map.
   * @param lowerCase {@code true} for an uncased model.
   * @throws IllegalArgumentException Thrown if {@code vocab} is {@code null}.
   */
  protected TokenVectorsDL(final OrtEnvironment env, final OrtSession session,
      final Map<String, Integer> vocab, final boolean lowerCase) {
    this(env, session, vocab, lowerCase, new InferenceOptions());
  }

  /**
   * Initializes an encoder over shared state with configurable model inputs.
   *
   * @param env The ONNX environment, or {@code null}.
   * @param session The ONNX session, or {@code null}.
   * @param vocab The vocabulary map.
   * @param lowerCase {@code true} for an uncased model.
   * @param options The inference options. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code vocab} or {@code options} is
   *         {@code null}.
   */
  TokenVectorsDL(final OrtEnvironment env, final OrtSession session,
      final Map<String, Integer> vocab, final boolean lowerCase,
      final InferenceOptions options) {
    this(env, session, vocab, lowerCase, options, 1);
  }

  /**
   * Initializes an encoder over shared state with a declared output dimension.
   *
   * @param env The ONNX environment, or {@code null} in tests.
   * @param session The ONNX session, or {@code null} in tests.
   * @param vocab The vocabulary map.
   * @param lowerCase {@code true} for an uncased model.
   * @param options The inference options. Must not be {@code null}.
   * @param dimension The positive hidden-state dimension.
   * @throws IllegalArgumentException Thrown if an argument is invalid.
   */
  TokenVectorsDL(final OrtEnvironment env, final OrtSession session,
      final Map<String, Integer> vocab, final boolean lowerCase,
      final InferenceOptions options, final int dimension) {
    super(env, session, requireVocabulary(vocab), lowerCase);
    this.specials = specials();
    if (options == null) {
      throw new IllegalArgumentException("options must not be null");
    }
    if (dimension <= 0) {
      throw new IllegalArgumentException("dimension must be positive: " + dimension);
    }
    this.includeAttentionMask = options.isIncludeAttentionMask();
    this.includeTokenTypeIds = options.isIncludeTokenTypeIds();
    this.dimension = dimension;
  }

  /**
   * Validates a vocabulary before superclass construction.
   *
   * @param vocab The vocabulary map.
   * @return The validated map.
   * @throws IllegalArgumentException Thrown if {@code vocab} is {@code null}.
   */
  private static Map<String, Integer> requireVocabulary(Map<String, Integer> vocab) {
    if (vocab == null) {
      throw new IllegalArgumentException("vocab must not be null");
    }
    return vocab;
  }

  /**
   * Reads the special token ids from the tokenizer.
   *
   * @return The classification and separator token ids.
   */
  private long[] specials() {
    final long[] empty = encodeTokens("").ids();
    return new long[] {empty[0], empty[empty.length - 1]};
  }

  /** {@inheritDoc} */
  @Override
  public int dimension() {
    return dimension;
  }

  /**
   * {@inheritDoc} Each word's vector is the mean of its wordpiece hidden states.
   *
   * @throws IllegalArgumentException Thrown if {@code tokens} is {@code null}, empty, or
   *         contains {@code null}.
   * @throws IllegalStateException Thrown if inference fails.
   */
  @Override
  public float[][] vectors(final String[] tokens) {
    if (tokens == null || tokens.length == 0) {
      throw new IllegalArgumentException("tokens must not be null or empty");
    }
    final List<long[]> pieces = new ArrayList<>(tokens.length);
    for (int t = 0; t < tokens.length; t++) {
      if (tokens[t] == null) {
        throw new IllegalArgumentException("tokens must not contain null at index " + t);
      }
      pieces.add(pieceIds(tokens[t]));
    }
    final float[][] vectors = new float[tokens.length][];
    for (final int[] window : windows(pieces, MAX_PIECES)) {
      encode(pieces, window[0], window[1], vectors);
    }
    return vectors;
  }

  /**
   * Splits a word into wordpiece ids without the surrounding special tokens.
   *
   * @param word The word.
   * @return Its piece ids, possibly empty. Never {@code null}.
   */
  long[] pieceIds(final String word) {
    final long[] pieces = encodeTokens(word).ids();
    final int count = Math.max(0, pieces.length - SPECIAL_PIECES);
    final long[] ids = new long[count];
    for (int p = 0; p < count; p++) {
      ids[p] = pieces[p + 1];
    }
    return ids;
  }

  /**
   * Packs consecutive words into windows whose pieces, plus the special tokens, fit
   * the limit. A single word longer than the limit forms a window of its own and is
   * truncated when encoded.
   *
   * @param pieces The piece ids of each word.
   * @param maxPieces The most pieces a window may hold.
   * @return The {@code [first word, last word exclusive]} bounds of each window.
   */
  static List<int[]> windows(final List<long[]> pieces, final int maxPieces) {
    final List<int[]> windows = new ArrayList<>();
    final int capacity = maxPieces - SPECIAL_PIECES;
    int start = 0;
    int used = 0;
    for (int w = 0; w < pieces.size(); w++) {
      final int count = pieces.get(w).length;
      if (w > start && used + count > capacity) {
        windows.add(new int[] {start, w});
        start = w;
        used = 0;
      }
      used += count;
    }
    windows.add(new int[] {start, pieces.size()});
    return windows;
  }

  /**
   * Encodes one window and writes its word vectors.
   *
   * @param pieces The piece ids of each word.
   * @param from The first included word index.
   * @param to The first excluded word index.
   * @param vectors The output array.
   */
  private void encode(final List<long[]> pieces, final int from, final int to,
      final float[][] vectors) {
    final int capacity = MAX_PIECES - SPECIAL_PIECES;
    final List<Long> input = new ArrayList<>();
    input.add(specials[0]);
    final int[] firstPiece = new int[to - from];
    final int[] pieceCount = new int[to - from];
    for (int w = from; w < to; w++) {
      firstPiece[w - from] = input.size();
      for (final long piece : pieces.get(w)) {
        if (input.size() - 1 < capacity) {
          input.add(piece);
          pieceCount[w - from]++;
        }
      }
    }
    input.add(specials[1]);
    final long[] ids = new long[input.size()];
    final long[] ones = new long[ids.length];
    final long[] zeros = new long[ids.length];
    for (int p = 0; p < ids.length; p++) {
      ids[p] = input.get(p);
      ones[p] = 1L;
    }
    final float[][] hidden = run(ids, ones, zeros);
    for (int w = from; w < to; w++) {
      final int count = pieceCount[w - from];
      vectors[w] = mean(hidden, firstPiece[w - from], count);
    }
  }

  /**
   * Averages a consecutive range of hidden vectors.
   *
   * @param hidden The hidden vectors.
   * @param first The first vector to include.
   * @param count The number of vectors to include.
   * @return Their component-wise mean, or a zero vector when {@code count} is zero.
   */
  static float[] mean(float[][] hidden, int first, int count) {
    final float[] mean = new float[hidden[0].length];
    if (count > 0) {
      for (int d = 0; d < mean.length; d++) {
        double sum = 0.0;
        for (int p = 0; p < count; p++) {
          sum += hidden[first + p][d];
        }
        mean[d] = (float) (sum / count);
      }
    }
    return mean;
  }

  /**
   * Runs one encoder input.
   *
   * @param ids The input token ids.
   * @param mask The attention mask.
   * @param types The token type ids.
   * @return One hidden vector per input position.
   * @throws IllegalStateException Thrown if inference fails or the output is invalid.
   */
  private float[][] run(final long[] ids, final long[] mask, final long[] types) {
    final Map<String, OnnxTensor> inputs = new HashMap<>();
    final Object output;
    try {
      final long[] shape = {1, ids.length};
      for (final Map.Entry<String, long[]> input : inputValues(ids, mask, types).entrySet()) {
        inputs.put(input.getKey(),
            OnnxTensor.createTensor(env, LongBuffer.wrap(input.getValue()), shape));
      }
      try (OrtSession.Result result = session.run(inputs)) {
        output = result.get(0).getValue();
      }
    } catch (OrtException e) {
      throw new IllegalStateException("token encoding failed", e);
    } finally {
      inputs.values().forEach(OnnxTensor::close);
    }
    final float[][] hidden = hiddenFromOutput(output, ids.length);
    if (hidden[0].length != dimension) {
      throw new IllegalStateException("token encoder returned dimension "
          + hidden[0].length + ", expected " + dimension);
    }
    return hidden;
  }

  /**
   * Reads the hidden-state dimension from the first ONNX output.
   *
   * @return The positive hidden-state dimension.
   * @throws OrtException Thrown if the output metadata cannot be read.
   * @throws IllegalArgumentException Thrown if the first output is not a rank-three
   *         tensor with a fixed positive final dimension.
   */
  private int outputDimension() throws OrtException {
    if (session.getNumOutputs() == 0) {
      throw new IllegalArgumentException("model must declare an output");
    }
    final String outputName = session.getOutputNames().iterator().next();
    final NodeInfo output = session.getOutputInfo().get(outputName);
    if (output == null || !(output.getInfo() instanceof TensorInfo tensor)) {
      throw new IllegalArgumentException("model first output must be a tensor");
    }
    final long[] shape = tensor.getShape();
    if (shape.length != 3 || shape[2] <= 0 || shape[2] > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "model first output must declare a positive hidden-state dimension");
    }
    return (int) shape[2];
  }

  /**
   * Selects the arrays supplied to the model from the options captured at construction.
   *
   * @param ids The input token ids.
   * @param mask The attention mask.
   * @param types The token type ids.
   * @return The selected model inputs by ONNX input name.
   */
  Map<String, long[]> inputValues(final long[] ids, final long[] mask, final long[] types) {
    final Map<String, long[]> inputs = HashMap.newHashMap(3);
    inputs.put(INPUT_IDS, ids);
    if (includeAttentionMask) {
      inputs.put(ATTENTION_MASK, mask);
    }
    if (includeTokenTypeIds) {
      inputs.put(TOKEN_TYPE_IDS, types);
    }
    return inputs;
  }

  /**
   * Validates and extracts the hidden-state matrix returned for one encoder input.
   *
   * @param output The first ONNX output value.
   * @param expectedPositions The number of input positions.
   * @return The hidden vector for each input position.
   * @throws IllegalStateException Thrown if the output is not one finite, rectangular
   *         {@code float[position][dimension]} matrix with the expected position count.
   */
  static float[][] hiddenFromOutput(final Object output, final int expectedPositions) {
    if (!(output instanceof float[][][] batches) || batches.length != 1
        || batches[0] == null) {
      throw new IllegalStateException("token encoder output must contain one float matrix");
    }
    final float[][] hidden = batches[0];
    if (hidden.length != expectedPositions) {
      throw new IllegalStateException("token encoder returned " + hidden.length
          + " positions, expected " + expectedPositions);
    }
    int dimension = -1;
    for (int p = 0; p < hidden.length; p++) {
      final float[] vector = hidden[p];
      if (vector == null || vector.length == 0) {
        throw new IllegalStateException("token encoder returned an empty vector at position " + p);
      }
      if (dimension < 0) {
        dimension = vector.length;
      } else if (vector.length != dimension) {
        throw new IllegalStateException("token encoder returned dimension " + vector.length
            + " at position " + p + ", expected " + dimension);
      }
      for (int d = 0; d < vector.length; d++) {
        if (!Float.isFinite(vector[d])) {
          throw new IllegalStateException("token encoder returned a non-finite value at position "
              + p + ", dimension " + d);
        }
      }
    }
    return hidden;
  }

}
