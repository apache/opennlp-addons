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
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks binary model structure, values and allocation on truncated input. */
class BilstmPOSModelValidationTest {

  @TempDir
  Path temporary;

  /** {@return all format flags with and without pretrained vectors where supported} */
  private static Stream<Arguments> layouts() {
    final List<Arguments> cases = new ArrayList<>();
    for (int version = 1; version <= 4; version++) {
      for (boolean pretrained : new boolean[] {false, true}) {
        cases.add(Arguments.of(version, version >= 3, version % 2 == 0, pretrained));
      }
    }
    for (boolean stacked : new boolean[] {false, true}) {
      for (boolean crf : new boolean[] {false, true}) {
        cases.add(Arguments.of(5, stacked, crf, true));
      }
    }
    return cases.stream();
  }

  /**
   * Valid layouts retain bytes, scores and tagging through stream and file loading.
   *
   * @param version The format version.
   * @param stacked Whether the model has a second sentence layer.
   * @param crf Whether it has a CRF output layer.
   * @param pretrained Whether it has pretrained vectors.
   * @throws IOException If saving or loading fails.
   */
  @ParameterizedTest
  @MethodSource("layouts")
  void testValidLayout(int version, boolean stacked, boolean crf, boolean pretrained)
      throws IOException {
    final byte[] bytes = new ModelData(version, stacked, crf, pretrained).bytes();
    final BilstmPOSModel model = BilstmPOSModel.load(new ByteArrayInputStream(bytes));
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    model.serialize(output);
    assertArrayEquals(bytes, output.toByteArray());
    assertEquals(crf, model.isCrf());
    final Path file = temporary.resolve("pos-bilstm.bin");
    model.serialize(file);
    final BilstmPOSModel loaded = BilstmPOSModel.load(file);
    final String[] sentence = {"cat", "unseen", "\uD801\uDC00"};
    final double[][] expected = model.score(sentence);
    final double[][] actual = loaded.score(sentence);
    for (int token = 0; token < sentence.length; token++) {
      assertArrayEquals(expected[token], actual[token]);
    }
    assertArrayEquals(new BilstmPOSTagger(model).tag(sentence),
        new BilstmPOSTagger(loaded).tag(sentence));
  }

  /** {@return invalid common dimensions and vocabulary metadata in every format} */
  private static Stream<Arguments> structures() {
    final Map<String, Consumer<ModelData>> changes = new LinkedHashMap<>();
    for (String field : List.of("words.count", "chars.count", "tags.count", "word.rows",
        "word.cols", "char.rows", "char.cols", "maxWordLength")) {
      changes.put(field + " zero", model -> model.counts.put(field, 0));
      changes.put(field + " negative", model -> model.counts.put(field, -1));
    }
    for (String layer : List.of("cf", "cb", "wf", "wb")) {
      for (String dimension : List.of("input", "hidden")) {
        final String field = layer + "." + dimension;
        changes.put(field + " zero", model -> model.counts.put(field, 0));
        changes.put(field + " negative", model -> model.counts.put(field, -1));
      }
      for (String dimension : List.of("w.rows", "u.rows", "b.length")) {
        final String field = layer + "." + dimension;
        changes.put(field, model -> model.counts.put(field, 3));
      }
      for (String dimension : List.of("w.cols", "u.cols")) {
        final String field = layer + "." + dimension;
        changes.put(field, model -> model.counts.put(field, 2));
      }
    }
    for (String field : List.of("cb.input", "cb.hidden", "wb.hidden")) {
      changes.put(field, model -> model.counts.put(field, 2));
    }
    changes.put("word input", model -> model.counts.put("wf.input", 3));
    changes.put("backward input", model -> model.counts.put("wb.input", 3));
    changes.put("output rows", model -> model.counts.put("output.rows", 1));
    changes.put("output columns", model -> model.counts.put("output.cols", 1));
    changes.put("output bias", model -> model.counts.put("outputBias.length", 1));
    changes.put("duplicate tag", model -> model.duplicateTag = true);
    for (int index = 0; index < 2; index++) {
      final int vocabulary = index;
      changes.put("missing unknown " + index, model -> model.vocabularies.get(vocabulary).remove("*UNK*"));
      changes.put("unknown row " + index, model -> model.vocabularies.get(vocabulary).put("*UNK*", 1));
      changes.put("negative row " + index, model -> model.vocabularies.get(vocabulary).put("other", -1));
      changes.put("row past end " + index, model -> model.vocabularies.get(vocabulary).put("other", 2));
      changes.put("duplicate symbol " + index, model -> model.duplicateVocabulary = vocabulary);
    }
    return changes.entrySet().stream().flatMap(change -> Stream.of(1, 2, 3, 4, 5)
        .map(version -> Arguments.of(change.getKey(), change.getValue(), version)));
  }

  /**
   * Invalid dimensions fail during loading with an I/O error.
   *
   * @param name The invalid field.
   * @param change The fixture change.
   * @param version The format version.
   * @throws IOException If fixture encoding fails.
   */
  @ParameterizedTest(name = "{0}, version={2}")
  @MethodSource("structures")
  void testInvalidStructure(String name, Consumer<ModelData> change, int version) throws IOException {
    final ModelData model = new ModelData(version);
    change.accept(model);
    assertInvalid(model.bytes(), name);
  }

  /** {@return invalid stacked, CRF, pretrained and adapter fields} */
  private static Stream<Arguments> optionalStructures() {
    final Map<String, Consumer<ModelData>> changes = new LinkedHashMap<>();
    for (String field : List.of("wf2.input", "wb2.input", "wb2.hidden", "transitions.rows",
        "transitions.cols", "start.length", "end.length", "pretrained.rows",
        "pretrained.cols", "adapter.rows", "adapter.cols", "adapterBias.length")) {
      changes.put(field + " zero", model -> model.counts.put(field, 0));
      changes.put(field + " negative", model -> model.counts.put(field, -1));
      if (!field.equals("pretrained.rows")) {
        changes.put(field + " wrong size", model -> model.counts.put(field, 3));
      }
    }
    for (String layer : List.of("wf2", "wb2")) {
      for (String dimension : List.of("hidden", "w.rows", "u.rows", "w.cols", "u.cols",
          "b.length")) {
        final String field = layer + "." + dimension;
        changes.put(field + " zero", model -> model.counts.put(field, 0));
        changes.put(field + " negative", model -> model.counts.put(field, -1));
      }
    }
    changes.put("pretrained negative count", model -> model.counts.put("pretrained.count", -1));
    changes.put("pretrained row past end", model -> model.vocabularies.get(2).put("cat", 1));
    changes.put("pretrained negative row", model -> model.vocabularies.get(2).put("cat", -1));
    changes.put("duplicate pretrained symbol", model -> model.duplicateVocabulary = 2);
    return changes.entrySet().stream().map(change -> Arguments.of(change.getKey(), change.getValue()));
  }

  /**
   * Optional blocks must agree with the layer shapes and vocabulary rows.
   *
   * @param name The invalid field.
   * @param change The fixture change.
   * @throws IOException If fixture encoding fails.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("optionalStructures")
  void testInvalidOptionalStructure(String name, Consumer<ModelData> change) throws IOException {
    final ModelData model = new ModelData(5);
    change.accept(model);
    assertInvalid(model.bytes(), name);
  }

  /** {@return non-finite values in every parameter block of the complete layout} */
  private static Stream<Arguments> nonFiniteValues() throws IOException {
    final ModelData model = new ModelData(5);
    model.bytes();
    return model.values.keySet().stream().flatMap(name -> Stream.of(Double.NaN,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).map(value -> Arguments.of(name, value)));
  }

  /** {@return finite boundary values in every numeric parameter block} */
  private static Stream<Arguments> finiteValues() throws IOException {
    final ModelData model = new ModelData(5);
    model.bytes();
    return model.values.keySet().stream().flatMap(name -> {
      final double maximum = name.equals("pretrained") ? Float.MAX_VALUE : Double.MAX_VALUE;
      final double minimum = name.equals("pretrained") ? Float.MIN_VALUE : Double.MIN_VALUE;
      return Stream.of(0.0, -0.0, maximum, -maximum, minimum, -minimum)
          .map(value -> Arguments.of(name, value));
    });
  }

  /**
   * Finite values, including signed zero and subnormals, retain their serialized bits.
   *
   * @param name The parameter block.
   * @param value The finite value.
   * @throws IOException If saving or loading fails.
   */
  @ParameterizedTest
  @MethodSource("finiteValues")
  void testFiniteValue(String name, double value) throws IOException {
    final ModelData model = new ModelData(5);
    model.values.put(name, value);
    assertRoundTrip(model.bytes());
  }

  /**
   * Numeric buffers and row tables can grow beyond their initial allocation.
   *
   * @param size The width and tag count around the buffer growth boundary.
   * @throws IOException If saving or loading fails.
   */
  @ParameterizedTest
  @ValueSource(ints = {4095, 4096, 4097, 8193})
  void testGrowingBuffers(int size) throws IOException {
    final ModelData model = new ModelData(1);
    model.counts.put("word.cols", size);
    model.counts.put("pretrained.cols", size);
    model.counts.put("wf.input", 2 * size + 2);
    model.counts.put("wb.input", 2 * size + 2);
    model.counts.put("tags.count", size);
    model.counts.put("output.rows", size);
    model.counts.put("outputBias.length", size);
    model.counts.put("word.rows", 17);
    model.counts.put("pretrained.rows", 17);
    assertRoundTrip(model.bytes());
  }

  /**
   * NaN and infinity are rejected before inference.
   *
   * @param name The parameter block.
   * @param value The invalid value.
   * @throws IOException If fixture encoding fails.
   */
  @ParameterizedTest
  @MethodSource("nonFiniteValues")
  void testNonFiniteValue(String name, double value) throws IOException {
    final ModelData model = new ModelData(5);
    model.values.put(name, value);
    assertInvalid(model.bytes(), name);
  }

  /**
   * Every strict prefix of a valid model fails with an I/O error.
   *
   * @param version The format version.
   * @throws IOException If fixture encoding fails.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void testTruncatedModel(int version) throws IOException {
    final byte[] bytes = new ModelData(version).bytes();
    for (int length = 0; length < bytes.length; length++) {
      assertInvalid(Arrays.copyOf(bytes, length), "prefix " + length);
    }
  }

  /**
   * Large declarations without values cannot exhaust a small child heap.
   *
   * @param field The field overwritten with the maximum signed integer.
   * @throws Exception If the fixture or child process fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {"tags.count", "word.rows", "word.cols", "cf.b.length",
      "outputBias.length", "pretrained.rows", "pretrained.cols", "adapter.rows", "adapter.cols"})
  void testLargeTruncatedDeclaration(String field) throws Exception {
    final ModelData model = new ModelData(5);
    final byte[] bytes = model.bytes();
    final int offset = model.offsets.get(field);
    ByteBuffer.wrap(bytes).putInt(offset, Integer.MAX_VALUE);
    final Path file = temporary.resolve("truncated.bin");
    Files.write(file, Arrays.copyOf(bytes, Math.min(bytes.length, offset + 8)));
    final Path log = temporary.resolve("child.log");
    final Process child = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx32m", "-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        LoadProbe.class.getName(), file.toString()).redirectErrorStream(true)
        .redirectOutput(log.toFile()).start();
    try {
      assertTrue(child.waitFor(20, TimeUnit.SECONDS), "loader did not finish");
      assertEquals(0, child.exitValue(), Files.readString(log));
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(5, TimeUnit.SECONDS), "loader process did not exit");
    }
  }

  /**
   * Rejects null streams and paths at the public boundary.
   *
   * @param argument The entry point called with null.
   * @throws IOException If loading the fixture fails.
   */
  @ParameterizedTest
  @EnumSource(NullArgument.class)
  void testNullArguments(NullArgument argument) throws IOException {
    final BilstmPOSModel model = BilstmPOSModel.load(new ByteArrayInputStream(new ModelData(1).bytes()));
    assertThrows(IllegalArgumentException.class, () -> {
      switch (argument) {
        case INPUT_STREAM -> BilstmPOSModel.load((InputStream) null);
        case INPUT_PATH -> BilstmPOSModel.load((Path) null);
        case OUTPUT_STREAM -> model.serialize((OutputStream) null);
        case OUTPUT_PATH -> model.serialize((Path) null);
      }
    });
  }

  /** Public stream and file entry points. */
  private enum NullArgument {
    INPUT_STREAM, INPUT_PATH, OUTPUT_STREAM, OUTPUT_PATH
  }

  /**
   * An adapter must have a pretrained vector block to transform.
   *
   * @throws IOException If fixture encoding fails.
   */
  @Test
  void testAdapterWithoutPretrainedVectors() throws IOException {
    assertInvalid(new ModelData(5, false, false, false).bytes(), "adapter without vectors");
  }

  /**
   * Shared row ids and unused rows do not prevent a valid model from loading.
   *
   * @param emptyPretrainedVocabulary Whether to leave the pretrained table unused.
   * @throws IOException If saving or loading fails.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testVocabularyAliasesAndUnusedRows(boolean emptyPretrainedVocabulary) throws IOException {
    final ModelData fixture = new ModelData(5);
    fixture.counts.put("word.rows", 3);
    fixture.counts.put("char.rows", 3);
    fixture.counts.put("pretrained.rows", 3);
    fixture.vocabularies.get(0).put("kitty", 1);
    fixture.vocabularies.get(1).put("k", 1);
    if (emptyPretrainedVocabulary) {
      fixture.vocabularies.get(2).clear();
    } else {
      fixture.vocabularies.get(2).put("kitty", 0);
    }
    assertRoundTrip(fixture.bytes());
  }

  /**
   * A second encoder can have a different hidden width from the first.
   *
   * @throws IOException If saving or loading fails.
   */
  @Test
  void testDifferentSecondLayerWidth() throws IOException {
    final ModelData fixture = new ModelData(5);
    fixture.counts.put("wf2.hidden", 2);
    fixture.counts.put("wb2.hidden", 2);
    fixture.counts.put("output.cols", 4);
    final BilstmPOSModel model = assertRoundTrip(fixture.bytes());
    assertEquals(2, model.score(new String[] {"cat"})[0].length);
  }

  /**
   * Stream overloads leave caller-owned resources open on success and failure.
   *
   * @param valid Whether to read a valid model.
   * @throws IOException If saving or loading fails.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testStreamOwnership(boolean valid) throws IOException {
    final boolean[] closed = {false};
    final byte[] bytes = new ModelData(1).bytes();
    final InputStream input = new ByteArrayInputStream(valid ? bytes : Arrays.copyOf(bytes, 20)) {
      /** {@inheritDoc} */
      @Override
      public void close() {
        closed[0] = true;
      }
    };
    if (valid) {
      BilstmPOSModel.load(input);
    } else {
      assertThrows(IOException.class, () -> BilstmPOSModel.load(input));
    }
    assertFalse(closed[0]);
    final OutputStream output = new OutputStream() {
      /** {@inheritDoc} */
      @Override
      public void write(int value) throws IOException {
        if (!valid) {
          throw new IOException("output failure");
        }
      }

      /** {@inheritDoc} */
      @Override
      public void close() {
        closed[0] = true;
      }
    };
    final BilstmPOSModel model = BilstmPOSModel.load(new ByteArrayInputStream(bytes));
    if (valid) {
      model.serialize(output);
    } else {
      assertThrows(IOException.class, () -> model.serialize(output));
    }
    assertFalse(closed[0]);
  }

  /**
   * Loads and serializes a model without changing any bytes.
   *
   * @param bytes The encoded model.
   * @return The loaded model.
   * @throws IOException If saving or loading fails.
   */
  private BilstmPOSModel assertRoundTrip(byte[] bytes) throws IOException {
    final BilstmPOSModel model = BilstmPOSModel.load(new ByteArrayInputStream(bytes));
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    model.serialize(output);
    assertArrayEquals(bytes, output.toByteArray());
    return model;
  }

  /**
   * Checks a malformed fixture through the public loader.
   *
   * @param bytes The encoded model.
   * @param description The invalid field.
   */
  private void assertInvalid(byte[] bytes, String description) {
    assertThrows(IOException.class, () -> BilstmPOSModel.load(new ByteArrayInputStream(bytes)), description);
  }

  /** Loads one truncated model in a small child JVM. */
  public static class LoadProbe {
    /**
     * Exits normally only when the loader reports an I/O error.
     *
     * @param arguments The model path.
     */
    public static void main(String[] arguments) {
      try {
        BilstmPOSModel.load(Path.of(arguments[0]));
        throw new AssertionError("malformed model accepted");
      } catch (IOException expected) {
        System.out.println(expected.getMessage());
      }
    }
  }

  /** Writes small fixtures independently of the production serializer. */
  private static class ModelData {
    private final int version;
    private final boolean stacked;
    private final boolean crf;
    private final boolean pretrained;
    private final List<LinkedHashMap<String, Integer>> vocabularies = new ArrayList<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, Integer> offsets = new LinkedHashMap<>();
    private final Map<String, Double> values = new LinkedHashMap<>();
    private int duplicateVocabulary = -1;
    private boolean duplicateTag;
    private ByteArrayOutputStream buffer;
    private DataOutputStream output;

    /**
     * Creates a version with all optional blocks that its header supports.
     *
     * @param version The binary format version.
     */
    private ModelData(int version) {
      this(version, version >= 3, version % 2 == 0 || version == 5, true);
    }

    /**
     * Creates a selected binary layout.
     *
     * @param version The format version.
     * @param stacked Whether to include a second sentence layer.
     * @param crf Whether to include CRF scores.
     * @param pretrained Whether to include word vectors.
     */
    private ModelData(int version, boolean stacked, boolean crf, boolean pretrained) {
      this.version = version;
      this.stacked = stacked;
      this.crf = crf;
      this.pretrained = pretrained;
      vocabularies.add(new LinkedHashMap<>());
      vocabularies.get(0).put("*UNK*", 0);
      vocabularies.get(0).put("cat", 1);
      vocabularies.add(new LinkedHashMap<>());
      vocabularies.get(1).put("*UNK*", 0);
      vocabularies.get(1).put("c", 1);
      vocabularies.add(new LinkedHashMap<>());
      vocabularies.get(2).put("cat", 0);
    }

    /**
     * Encodes the fixture and records integer field offsets.
     *
     * @return The encoded model.
     * @throws IOException If encoding fails.
     */
    private byte[] bytes() throws IOException {
      buffer = new ByteArrayOutputStream();
      output = new DataOutputStream(buffer);
      output.writeUTF("ONLP-BLPT-" + version);
      if (version == 5) {
        output.writeBoolean(stacked);
        output.writeBoolean(crf);
      }
      vocabulary("words", 0);
      vocabulary("chars", 1);
      final int tags = count("tags.count", 2);
      for (int i = 0; i < tags; i++) {
        output.writeUTF(duplicateTag || i == 0 ? "NN" : i == 1 ? "VB" : "tag" + i);
      }
      matrix("word", 2, 1);
      matrix("char", 2, 1);
      layer("cf", 1, 1);
      layer("cb", 1, 1);
      layer("wf", pretrained ? 4 : 3, 1);
      layer("wb", pretrained ? 4 : 3, 1);
      if (stacked) {
        layer("wf2", 2, 1);
        layer("wb2", 2, 1);
      }
      matrix("output", 2, 2);
      vector("outputBias", 2);
      if (crf) {
        matrix("transitions", 2, 2);
        vector("start", 2);
        vector("end", 2);
      }
      count("maxWordLength", 12);
      output.writeBoolean(pretrained);
      if (pretrained) {
        vocabulary("pretrained", 2);
        final int columns = count("pretrained.cols", 1);
        final int rows = count("pretrained.rows", 1);
        values.putIfAbsent("pretrained", 0.25);
        for (int row = 0; row < rows; row++) {
          for (int column = 0; column < columns; column++) {
            output.writeFloat(values.get("pretrained").floatValue());
          }
        }
      }
      if (version == 5) {
        matrix("adapter", 1, 1);
        vector("adapterBias", 1);
      }
      return buffer.toByteArray();
    }

    /**
     * Writes a vocabulary, optionally repeating one symbol.
     *
     * @param name The field name.
     * @param index The vocabulary index.
     * @throws IOException If writing fails.
     */
    private void vocabulary(String name, int index) throws IOException {
      final List<Map.Entry<String, Integer>> entries = new ArrayList<>(vocabularies.get(index).entrySet());
      if (duplicateVocabulary == index) {
        entries.add(entries.get(0));
      }
      final int size = count(name + ".count", entries.size());
      for (int i = 0; i < size; i++) {
        final Map.Entry<String, Integer> entry = entries.get(i);
        output.writeUTF(entry.getKey());
        output.writeInt(entry.getValue());
      }
    }

    /**
     * Writes a dimension and records its offset.
     *
     * @param name The field name.
     * @param fallback The default value.
     * @return The encoded value.
     * @throws IOException If writing fails.
     */
    private int count(String name, int fallback) throws IOException {
      final int value = counts.getOrDefault(name, fallback);
      offsets.put(name, buffer.size());
      output.writeInt(value);
      return value;
    }

    /**
     * Writes a dense double matrix.
     *
     * @param name The field name.
     * @param defaultRows The default row count.
     * @param defaultColumns The default column count.
     * @throws IOException If writing fails.
     */
    private void matrix(String name, int defaultRows, int defaultColumns) throws IOException {
      final int rows = count(name + ".rows", defaultRows);
      final int columns = count(name + ".cols", defaultColumns);
      values.putIfAbsent(name, (name.hashCode() % 11) * 0.015625);
      for (int row = 0; row < rows; row++) {
        for (int column = 0; column < columns; column++) {
          output.writeDouble(values.get(name));
        }
      }
    }

    /**
     * Writes a double vector.
     *
     * @param name The field name.
     * @param defaultLength The default length.
     * @throws IOException If writing fails.
     */
    private void vector(String name, int defaultLength) throws IOException {
      final int length = count(name + ".length", defaultLength);
      values.putIfAbsent(name, (name.hashCode() % 11) * 0.015625);
      for (int i = 0; i < length; i++) {
        output.writeDouble(values.get(name));
      }
    }

    /**
     * Writes a layer with declared sizes and its parameter arrays.
     *
     * @param name The field name.
     * @param defaultInput The default input width.
     * @param defaultHidden The default hidden width.
     * @throws IOException If writing fails.
     */
    private void layer(String name, int defaultInput, int defaultHidden) throws IOException {
      final int input = count(name + ".input", defaultInput);
      final int hidden = count(name + ".hidden", defaultHidden);
      matrix(name + ".w", 4 * hidden, input);
      matrix(name + ".u", 4 * hidden, hidden);
      vector(name + ".b", 4 * hidden);
    }
  }
}
