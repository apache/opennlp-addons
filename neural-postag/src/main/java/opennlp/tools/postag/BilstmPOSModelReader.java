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
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads and validates the five BiLSTM binary model layouts. */
final class BilstmPOSModelReader {

  private static final int INITIAL_VALUE_CAPACITY = 4096;
  private static final int LSTM_GATES = 4;
  private static final int DIRECTIONS = 2;
  private static final String WORDS = "words";
  private static final String CHARACTERS = "characters";
  private static final String PRETRAINED_VOCABULARY = "pretrained vocabulary";

  private final DataInputStream data;

  /**
   * Buffers a caller-owned model stream without closing it.
   *
   * @param input The stream checked by the public loader.
   */
  BilstmPOSModelReader(InputStream input) {
    data = new DataInputStream(new BufferedInputStream(input));
  }

  /**
   * Reads compatible vocabularies, layer dimensions and finite parameter values.
   *
   * @return The loaded model.
   * @throws IOException If reading fails or the model is malformed.
   */
  BilstmPOSModel read() throws IOException {
    final String magic = data.readUTF();
    if (!BilstmPOSModel.MAGIC.equals(magic) && !BilstmPOSModel.MAGIC_CRF.equals(magic)
        && !BilstmPOSModel.MAGIC_TWO_LAYER.equals(magic)
        && !BilstmPOSModel.MAGIC_TWO_LAYER_CRF.equals(magic)
        && !BilstmPOSModel.MAGIC_ADAPTER.equals(magic)) {
      throw new IOException("not an ONLP-BLPT model: " + magic);
    }
    final boolean adapter = BilstmPOSModel.MAGIC_ADAPTER.equals(magic);
    final boolean stacked = adapter ? data.readBoolean()
        : BilstmPOSModel.MAGIC_TWO_LAYER.equals(magic)
            || BilstmPOSModel.MAGIC_TWO_LAYER_CRF.equals(magic);
    final boolean crf = adapter ? data.readBoolean()
        : BilstmPOSModel.MAGIC_CRF.equals(magic)
            || BilstmPOSModel.MAGIC_TWO_LAYER_CRF.equals(magic);
    final LinkedHashMap<String, Integer> words = readVocabulary(WORDS, true);
    final LinkedHashMap<String, Integer> chars = readVocabulary(CHARACTERS, true);
    final String[] tags = readTags();
    final double[][] wordEmbeddings = readMatrix("word embeddings", 0, 0);
    final double[][] charEmbeddings = readMatrix("character embeddings", 0, 0);
    checkRows(words, wordEmbeddings.length, WORDS);
    checkRows(chars, charEmbeddings.length, CHARACTERS);
    final LstmLayer charForward = readLstm("character forward", charEmbeddings[0].length, 0);
    final LstmLayer charBackward = readLstm("character backward", charEmbeddings[0].length,
        charForward.hiddenSize());
    final LstmLayer wordForward = readLstm("word forward", 0, 0);
    final LstmLayer wordBackward = readLstm("word backward", wordForward.inputSize(),
        wordForward.hiddenSize());
    int outputWidth = checkedSize(DIRECTIONS * (long) wordForward.hiddenSize(), "encoder output");
    LstmLayer wordForward2 = null;
    LstmLayer wordBackward2 = null;
    if (stacked) {
      wordForward2 = readLstm("second word forward", outputWidth, 0);
      wordBackward2 = readLstm("second word backward", outputWidth, wordForward2.hiddenSize());
      outputWidth = checkedSize(DIRECTIONS * (long) wordForward2.hiddenSize(), "second encoder output");
    }
    final double[][] outputWeights = readMatrix("output weights", tags.length, outputWidth);
    final double[] outputBias = readVector("output bias", tags.length);
    double[][] transitionWeights = null;
    double[] startWeights = null;
    double[] endWeights = null;
    if (crf) {
      transitionWeights = readMatrix("CRF transitions", tags.length, tags.length);
      startWeights = readVector("CRF start", tags.length);
      endWeights = readVector("CRF end", tags.length);
    }
    final int maxWordLength = readDimension("maximum word length", 0);
    final boolean hasPretrained = data.readBoolean();
    if (adapter && !hasPretrained) {
      throw new IOException("adapter requires pretrained vectors");
    }
    LinkedHashMap<String, Integer> pretrainedIds = null;
    float[][] pretrainedVectors = null;
    int pretrainedDimension = 0;
    if (hasPretrained) {
      pretrainedIds = readVocabulary(PRETRAINED_VOCABULARY, false);
      pretrainedDimension = readDimension("pretrained dimension", 0);
      final int rows = readDimension("pretrained rows", 0);
      checkRows(pretrainedIds, rows, PRETRAINED_VOCABULARY);
      final List<float[]> vectors = new ArrayList<>();
      for (int row = 0; row < rows; row++) {
        vectors.add(readFloats(pretrainedDimension, "pretrained vectors"));
      }
      pretrainedVectors = vectors.toArray(float[][]::new);
    }
    final int representationSize = checkedSize(wordEmbeddings[0].length
        + DIRECTIONS * (long) charForward.hiddenSize() + pretrainedDimension, "word representation");
    if (wordForward.inputSize() != representationSize) {
      throw new IOException("word encoder input does not match word representation size");
    }
    double[][] adapterWeights = null;
    double[] adapterBias = null;
    if (adapter) {
      adapterWeights = readMatrix("adapter weights", pretrainedDimension, pretrainedDimension);
      adapterBias = readVector("adapter bias", pretrainedDimension);
    }
    return new BilstmPOSModel(words, chars, tags, wordEmbeddings, charEmbeddings,
        charForward, charBackward, wordForward, wordBackward, wordForward2,
        wordBackward2, outputWeights, outputBias, maxWordLength, pretrainedIds,
        pretrainedVectors, transitionWeights, startWeights, endWeights, adapterWeights,
        adapterBias);
  }

  /**
   * Reads a vocabulary without allocating from its declared size.
   *
   * @param name The field name used in error messages.
   * @param unknown Whether row zero must be the unknown symbol.
   * @return The vocabulary in file order.
   * @throws IOException If reading fails, a key repeats or a row index is invalid.
   */
  private LinkedHashMap<String, Integer> readVocabulary(String name, boolean unknown)
      throws IOException {
    final int size = data.readInt();
    if (size < 0) {
      throw new IOException(name + " count must not be negative");
    }
    final LinkedHashMap<String, Integer> vocabulary = new LinkedHashMap<>();
    for (int i = 0; i < size; i++) {
      final String symbol = data.readUTF();
      final int row = data.readInt();
      if (row < 0 || vocabulary.putIfAbsent(symbol, row) != null) {
        throw new IOException(name + " contains a negative row or duplicate symbol: " + symbol);
      }
    }
    if (unknown && !Integer.valueOf(0).equals(vocabulary.get(BilstmPOSModel.UNKNOWN))) {
      throw new IOException(name + " must map " + BilstmPOSModel.UNKNOWN + " to row zero");
    }
    return vocabulary;
  }

  /**
   * Checks that vocabulary entries address existing rows, allowing shared and unused rows.
   *
   * @param vocabulary The symbol-to-row mapping.
   * @param rows The number of parameter rows.
   * @param name The field name used in error messages.
   * @throws IOException If a row index is outside the parameter table.
   */
  private void checkRows(Map<String, Integer> vocabulary, int rows, String name) throws IOException {
    for (Map.Entry<String, Integer> entry : vocabulary.entrySet()) {
      if (entry.getValue() >= rows) {
        throw new IOException(name + " row is outside the parameter table: " + entry.getKey());
      }
    }
  }

  /**
   * Reads a nonempty, unique tag inventory in output order.
   *
   * @return The model tags.
   * @throws IOException If reading fails or the tag inventory is invalid.
   */
  private String[] readTags() throws IOException {
    final int size = readDimension("tag count", 0);
    final List<String> tags = new ArrayList<>();
    final Set<String> seen = new HashSet<>();
    for (int i = 0; i < size; i++) {
      final String tag = data.readUTF();
      if (!seen.add(tag)) {
        throw new IOException("duplicate tag: " + tag);
      }
      tags.add(tag);
    }
    return tags.toArray(String[]::new);
  }

  /**
   * Validates one LSTM's dimensions before constructing its transposed parameter arrays.
   *
   * @param name The layer name used in error messages.
   * @param expectedInput The required input width, or zero if not yet known.
   * @param expectedHidden The required hidden width, or zero if not yet known.
   * @return The layer over the validated weights.
   * @throws IOException If reading fails or a dimension or value is invalid.
   */
  private LstmLayer readLstm(String name, int expectedInput, int expectedHidden) throws IOException {
    final int input = readDimension(name + " input", expectedInput);
    final int hidden = readDimension(name + " hidden", expectedHidden);
    final int gates = checkedSize(LSTM_GATES * (long) hidden, name + " gate rows");
    final double[][] weights = readMatrix(name + " input weights", gates, input);
    final double[][] recurrence = readMatrix(name + " recurrent weights", gates, hidden);
    final double[] bias = readVector(name + " bias", gates);
    return LstmLayer.ofWeights(input, hidden, weights, recurrence, bias);
  }

  /**
   * Reads a positive dimension and checks it against a known layer size.
   *
   * @param name The field name used in error messages.
   * @param expected The required size, or zero if not yet known.
   * @return The validated dimension.
   * @throws IOException If reading fails or the dimension is invalid.
   */
  private int readDimension(String name, int expected) throws IOException {
    final int size = checkedSize(data.readInt(), name);
    if (expected != 0 && size != expected) {
      throw new IOException(name + " must be " + expected + ", got " + size);
    }
    return size;
  }

  /**
   * Checks dimensions computed with long arithmetic before narrowing them to an array index.
   *
   * @param size The declared or computed size.
   * @param name The field name used in error messages.
   * @return The positive size as an integer.
   * @throws IOException If the size is not a positive integer.
   */
  private int checkedSize(long size, String name) throws IOException {
    if (size <= 0 || size > Integer.MAX_VALUE) {
      throw new IOException(name + " must be a positive integer, got " + size);
    }
    return (int) size;
  }

  /**
   * Reads a rectangular matrix, allocating rows only as their values arrive.
   *
   * @param name The field name used in error messages.
   * @param expectedRows The required row count, or zero if not yet known.
   * @param expectedColumns The required column count, or zero if not yet known.
   * @return The parameter matrix.
   * @throws IOException If reading fails or a dimension or value is invalid.
   */
  private double[][] readMatrix(String name, int expectedRows, int expectedColumns) throws IOException {
    final int rows = readDimension(name + " rows", expectedRows);
    final int columns = readDimension(name + " columns", expectedColumns);
    final List<double[]> matrix = new ArrayList<>();
    for (int row = 0; row < rows; row++) {
      matrix.add(readDoubles(columns, name));
    }
    return matrix.toArray(double[][]::new);
  }

  /**
   * Reads a vector's declared length followed by its finite values.
   *
   * @param name The field name used in error messages.
   * @param expected The required vector length.
   * @return The parameter vector.
   * @throws IOException If reading fails or the length or a value is invalid.
   */
  private double[] readVector(String name, int expected) throws IOException {
    return readDoubles(readDimension(name + " length", expected), name);
  }

  /**
   * Reads doubles with bounded initial allocation and grows only after reading a value.
   *
   * @param length The positive declared length.
   * @param name The field name used in error messages.
   * @return The parameter values.
   * @throws IOException If reading fails or a value is not finite.
   */
  private double[] readDoubles(int length, String name) throws IOException {
    double[] values = new double[Math.min(length, INITIAL_VALUE_CAPACITY)];
    for (int i = 0; i < length; i++) {
      final double value = data.readDouble();
      if (!Double.isFinite(value)) {
        throw new IOException(name + " contains a non-finite value at index " + i);
      }
      if (i == values.length) {
        values = Arrays.copyOf(values, nextCapacity(values.length, length));
      }
      values[i] = value;
    }
    return values;
  }

  /**
   * Reads pretrained floats with the same allocation and finite-value checks as doubles.
   *
   * @param length The positive declared length.
   * @param name The field name used in error messages.
   * @return The parameter values.
   * @throws IOException If reading fails or a value is not finite.
   */
  private float[] readFloats(int length, String name) throws IOException {
    float[] values = new float[Math.min(length, INITIAL_VALUE_CAPACITY)];
    for (int i = 0; i < length; i++) {
      final float value = data.readFloat();
      if (!Float.isFinite(value)) {
        throw new IOException(name + " contains a non-finite value at index " + i);
      }
      if (i == values.length) {
        values = Arrays.copyOf(values, nextCapacity(values.length, length));
      }
      values[i] = value;
    }
    return values;
  }

  /**
   * Doubles an array's capacity without overflowing or exceeding the declared length.
   *
   * @param capacity The current positive capacity.
   * @param length The declared array length.
   * @return The increased capacity.
   */
  private int nextCapacity(int capacity, int length) {
    return (int) Math.min((long) capacity * 2, length);
  }
}
