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

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks model dimensions, vocabulary rows and weight values before inference. */
class FeedforwardPOSModelValidationTest {

  @TempDir
  Path temporary;

  /** {@return invalid model structures in both binary formats} */
  private static Stream<Arguments> structures() {
    final Map<String, Consumer<ModelData>> changes = new LinkedHashMap<>();
    changes.put("tag count", model -> model.tags = new String[0]);
    changes.put("duplicate tag", model -> model.tags[1] = model.tags[0]);
    changes.put("embedding size", model -> model.embeddingSize = 0);
    changes.put("embedding width", model -> model.embeddingSize++);
    changes.put("embedding rows", model -> model.embeddings = new float[0][]);
    changes.put("empty embedding columns", model -> model.embeddings = new float[4][0]);
    changes.put("hidden rows", model -> model.hiddenWeights = new float[0][]);
    changes.put("hidden columns", model -> model.hiddenWeights = new float[2][1]);
    changes.put("empty hidden columns", model -> model.hiddenWeights = new float[2][0]);
    changes.put("hidden bias", model -> model.hiddenBias = new float[1]);
    changes.put("output rows", model -> model.outputWeights = new float[1][2]);
    changes.put("output columns", model -> model.outputWeights = new float[2][1]);
    changes.put("empty output columns", model -> model.outputWeights = new float[2][0]);
    changes.put("output bias", model -> model.outputBias = new float[1]);
    for (int vocabulary = 0; vocabulary < 4; vocabulary++) {
      final int index = vocabulary;
      changes.put("missing unknown " + index,
          model -> model.vocabularies.get(index).remove(FeedforwardPOSModel.UNKNOWN));
      changes.put("negative row " + index,
          model -> model.vocabularies.get(index).put(FeedforwardPOSModel.UNKNOWN, -1));
      changes.put("row past end " + index,
          model -> model.vocabularies.get(index).put(FeedforwardPOSModel.ABSENT, 4));
      changes.put("duplicate symbol " + index, model -> model.duplicateVocabulary = index);
    }
    return changes.entrySet().stream().flatMap(entry -> Stream.of(false, true)
        .map(pretrained -> Arguments.of(entry.getKey(), entry.getValue(), pretrained)));
  }

  /**
   * A complete but inconsistent model is rejected by the loader.
   *
   * @param name The invalid field.
   * @param change The mutation of a valid model.
   * @param pretrained Whether the model includes word vectors.
   * @throws IOException If fixture serialization fails.
   */
  @ParameterizedTest(name = "{0}, pretrained={2}")
  @MethodSource("structures")
  void testInvalidStructure(String name, Consumer<ModelData> change, boolean pretrained)
      throws IOException {
    final ModelData model = new ModelData(pretrained);
    change.accept(model);
    assertInvalid(model.bytes(), name);
  }

  /** {@return invalid pretrained vector declarations} */
  private static Stream<Arguments> pretrainedStructures() {
    return Stream.of(
        Arguments.of("zero size", (Consumer<ModelData>) model -> model.pretrainedSize = 0),
        Arguments.of("wrong width", (Consumer<ModelData>) model -> model.pretrainedSize++),
        Arguments.of("missing rows", (Consumer<ModelData>)
            model -> model.pretrainedVectors = new float[0][]),
        Arguments.of("empty columns", (Consumer<ModelData>)
            model -> model.pretrainedVectors = new float[1][0]),
        Arguments.of("negative row", (Consumer<ModelData>)
            model -> model.pretrainedIds.put("cat", -1)),
        Arguments.of("row past end", (Consumer<ModelData>)
            model -> model.pretrainedIds.put("cat", 1)),
        Arguments.of("duplicate symbol", (Consumer<ModelData>)
            model -> model.duplicateVocabulary = 4));
  }

  /**
   * The optional vector block must agree with the input layer.
   *
   * @param name The invalid field.
   * @param change The mutation of a valid model.
   * @throws IOException If fixture serialization fails.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("pretrainedStructures")
  void testInvalidPretrainedStructure(String name, Consumer<ModelData> change) throws IOException {
    final ModelData model = new ModelData(true);
    change.accept(model);
    assertInvalid(model.bytes(), name);
  }

  /** {@return non-finite values at each kind of weight or bias} */
  private static Stream<Arguments> nonFiniteWeights() {
    return Stream.of(false, true).flatMap(pretrained ->
        Stream.of(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
            .flatMap(value -> Stream.of("embeddings", "hidden weights", "hidden bias",
                "output weights", "output bias", "pretrained vectors")
                .filter(field -> pretrained || !field.equals("pretrained vectors"))
                .map(field -> Arguments.of(pretrained, field, value))));
  }

  /**
   * Invalid floating-point values are format errors, not tagger results.
   *
   * @param pretrained Whether the model includes word vectors.
   * @param field The affected parameter array.
   * @param value The non-finite value.
   * @throws IOException If fixture serialization fails.
   */
  @ParameterizedTest
  @MethodSource("nonFiniteWeights")
  void testNonFiniteWeight(boolean pretrained, String field, float value) throws IOException {
    final ModelData model = new ModelData(pretrained);
    switch (field) {
      case "embeddings" -> model.embeddings[0][0] = value;
      case "hidden weights" -> model.hiddenWeights[0][0] = value;
      case "hidden bias" -> model.hiddenBias[0] = value;
      case "output weights" -> model.outputWeights[0][0] = value;
      case "output bias" -> model.outputBias[0] = value;
      case "pretrained vectors" -> model.pretrainedVectors[0][0] = value;
      default -> throw new AssertionError(field);
    }
    assertInvalid(model.bytes(), field);
  }

  /**
   * A valid model retains parameter values and predictions after loading and writing.
   *
   * @param pretrained Whether the model includes word vectors.
   * @throws IOException If model loading or serialization fails.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testValidRoundTrip(boolean pretrained) throws IOException {
    final ModelData data = new ModelData(pretrained);
    data.embeddings[0][0] = Float.MIN_VALUE;
    data.embeddings[1][0] = -0.0f;
    final byte[] original = data.bytes();
    final FeedforwardPOSModel model = FeedforwardPOSModel.load(new ByteArrayInputStream(original));
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    model.serialize(output);
    assertArrayEquals(original, output.toByteArray());
    final FeedforwardPOSTagger tagger = new FeedforwardPOSTagger(model);
    assertArrayEquals(new String[] {"NN", "NN"}, tagger.tag(new String[] {"cat", "unseen"}));
    assertArrayEquals(new double[] {0.5, 0.5},
        tagger.topKSequences(new String[] {"cat", "unseen"})[0].getProbs());
  }

  /**
   * Empty pretrained coverage and shared embedding rows are valid lookup arrangements.
   *
   * @param pretrained Whether the model includes word vectors.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testSharedRowsAndEmptyCoverage(boolean pretrained) throws IOException {
    final ModelData data = new ModelData(pretrained);
    data.vocabularies.getFirst().put("alias", 0);
    data.pretrainedIds.clear();
    data.pretrainedVectors = new float[0][];
    final FeedforwardPOSTagger tagger = new FeedforwardPOSTagger(
        FeedforwardPOSModel.load(new ByteArrayInputStream(data.bytes())));
    assertArrayEquals(new String[] {"NN"}, tagger.tag(new String[] {"alias"}));
  }

  /**
   * Vector growth retains values around each initial buffer boundary.
   *
   * @param width The embedding and hidden dimensions.
   * @throws IOException If loading or serialization fails.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 4095, 4096, 4097, 8192, 8193})
  void testBufferBoundaries(int width) throws IOException {
    final ModelData data = new ModelData(true);
    data.embeddingSize = width;
    data.embeddings = new float[4][width];
    data.hiddenWeights = new float[2][12 * width + 10];
    for (float[][] matrix : List.of(data.embeddings, data.hiddenWeights)) {
      for (float[] row : matrix) {
        for (int column = 0; column < row.length; column++) {
          row[column] = (column % 23 - 11) * 0.03125f;
        }
      }
    }
    final byte[] original = data.bytes();
    final FeedforwardPOSModel loaded = FeedforwardPOSModel.load(new ByteArrayInputStream(original));
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    loaded.serialize(output);
    assertArrayEquals(original, output.toByteArray());
  }

  /**
   * Every truncated prefix of a small valid model is rejected as an I/O error.
   *
   * @param pretrained Whether the model includes word vectors.
   * @throws IOException If fixture serialization fails.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testTruncation(boolean pretrained) throws IOException {
    final byte[] bytes = new ModelData(pretrained).bytes();
    for (int length = 0; length < bytes.length; length++) {
      assertInvalid(Arrays.copyOf(bytes, length), "prefix length " + length);
    }
  }

  /** {@return size fields that could otherwise allocate from a short input} */
  private static Stream<Arguments> largeLengths() {
    return Stream.of("vocabulary 0", "vocabulary 1", "vocabulary 2", "vocabulary 3",
        "tag count", "embeddings rows", "embeddings columns",
        "hidden bias", "output bias", "vocabulary 4", "pretrained vectors columns")
        .map(field -> Arguments.of(field, field.equals("embeddings columns") ? 1 << 28 : 1 << 29));
  }

  /**
   * A short model file cannot force a large allocation.
   *
   * @param field The mutated size field.
   * @param length The declared length.
   * @throws Exception If the child JVM cannot run.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("largeLengths")
  void testLargeLengthInSmallHeap(String field, int length) throws Exception {
    final ModelData model = new ModelData(true);
    final byte[] bytes = model.bytes();
    ByteBuffer.wrap(bytes).putInt(model.offsets.get(field), length);
    final Path input = temporary.resolve("model.bin");
    final Path log = temporary.resolve("loader.log");
    Files.write(input, bytes);
    final Process process = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx32m", "-cp", System.getProperty("surefire.test.class.path",
            System.getProperty("java.class.path")), LoadProbe.class.getName(), input.toString())
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS), "loader did not finish");
      assertEquals(0, process.exitValue(), Files.readString(log));
    } finally {
      process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS), "loader process did not exit");
    }
  }

  /**
   * Checks that malformed input fails at the loading boundary.
   *
   * @param bytes The encoded model.
   * @param description The malformed field or prefix.
   */
  private void assertInvalid(byte[] bytes, String description) {
    assertThrows(IOException.class,
        () -> FeedforwardPOSModel.load(new ByteArrayInputStream(bytes)), description);
  }

  /** Loads one malformed fixture in a child JVM with a small heap. */
  public static class LoadProbe {

    /**
     * Exits normally only when the loader rejects the input with an I/O error.
     *
     * @param arguments The model path.
     */
    public static void main(String[] arguments) {
      try {
        FeedforwardPOSModel.load(Path.of(arguments[0]));
        throw new AssertionError("the malformed model was accepted");
      } catch (IOException expected) {
        System.out.println(expected.getMessage());
      }
    }
  }

  /** Writes original, small model fixtures without relying on the production serializer. */
  private static class ModelData {

    private final boolean pretrained;
    private final List<Map<String, Integer>> vocabularies = new ArrayList<>();
    private final Map<String, Integer> offsets = new LinkedHashMap<>();
    private String[] tags = {"NN", "VB"};
    private int embeddingSize = 2;
    private float[][] embeddings = new float[4][2];
    private float[][] hiddenWeights;
    private float[] hiddenBias = new float[2];
    private float[][] outputWeights = new float[2][2];
    private float[] outputBias = new float[2];
    private int pretrainedSize = 2;
    private final Map<String, Integer> pretrainedIds = new LinkedHashMap<>();
    private float[][] pretrainedVectors = {{0.25f, -0.25f}};
    private int duplicateVocabulary = -1;
    private ByteArrayOutputStream buffer;

    /**
     * Creates a valid model for either supported version.
     *
     * @param pretrained Whether to write the optional word vectors.
     */
    private ModelData(boolean pretrained) {
      this.pretrained = pretrained;
      hiddenWeights = new float[2][24 + (pretrained ? 10 : 0)];
      for (int i = 0; i < 4; i++) {
        final Map<String, Integer> vocabulary = new LinkedHashMap<>();
        vocabulary.put(FeedforwardPOSModel.UNKNOWN, 0);
        vocabulary.put(FeedforwardPOSModel.ABSENT, 1);
        vocabulary.put("cat", 2);
        vocabularies.add(vocabulary);
      }
      pretrainedIds.put("cat", 0);
    }

    /**
     * Encodes the fields and records the offset of each length field.
     *
     * @return The binary model.
     * @throws IOException If encoding fails.
     */
    private byte[] bytes() throws IOException {
      buffer = new ByteArrayOutputStream();
      final DataOutputStream output = new DataOutputStream(buffer);
      output.writeUTF(pretrained ? "ONLP-FFPT-2" : "ONLP-FFPT-1");
      for (int i = 0; i < vocabularies.size(); i++) {
        vocabulary(output, vocabularies.get(i), i);
      }
      length(output, "tag count", tags.length);
      for (String tag : tags) {
        output.writeUTF(tag);
      }
      output.writeInt(embeddingSize);
      matrix(output, "embeddings", embeddings);
      matrix(output, "hidden weights", hiddenWeights);
      vector(output, "hidden bias", hiddenBias);
      matrix(output, "output weights", outputWeights);
      vector(output, "output bias", outputBias);
      if (pretrained) {
        output.writeInt(pretrainedSize);
        vocabulary(output, pretrainedIds, 4);
        matrix(output, "pretrained vectors", pretrainedVectors);
      }
      return buffer.toByteArray();
    }

    /**
     * Writes a vocabulary, optionally with an extra occurrence of the first symbol.
     *
     * @param output The destination.
     * @param ids The symbol rows.
     * @param index The vocabulary number.
     * @throws IOException If writing fails.
     */
    private void vocabulary(DataOutputStream output, Map<String, Integer> ids, int index)
        throws IOException {
      length(output, "vocabulary " + index, ids.size() + (duplicateVocabulary == index ? 1 : 0));
      for (Map.Entry<String, Integer> entry : ids.entrySet()) {
        output.writeUTF(entry.getKey());
        output.writeInt(entry.getValue());
      }
      if (duplicateVocabulary == index) {
        output.writeUTF(ids.keySet().iterator().next());
        output.writeInt(0);
      }
    }

    /**
     * Writes a rectangular matrix.
     *
     * @param output The destination.
     * @param field The field name.
     * @param matrix The matrix values.
     * @throws IOException If writing fails.
     */
    private void matrix(DataOutputStream output, String field, float[][] matrix) throws IOException {
      length(output, field + " rows", matrix.length);
      length(output, field + " columns", matrix.length == 0 ? 0 : matrix[0].length);
      for (float[] row : matrix) {
        for (float value : row) {
          output.writeFloat(value);
        }
      }
    }

    /**
     * Writes a bias vector.
     *
     * @param output The destination.
     * @param field The field name.
     * @param vector The vector values.
     * @throws IOException If writing fails.
     */
    private void vector(DataOutputStream output, String field, float[] vector) throws IOException {
      length(output, field, vector.length);
      for (float value : vector) {
        output.writeFloat(value);
      }
    }

    /**
     * Records a length field's offset before writing it.
     *
     * @param output The destination.
     * @param field The field name.
     * @param value The length.
     * @throws IOException If writing fails.
     */
    private void length(DataOutputStream output, String field, int value) throws IOException {
      offsets.put(field, buffer.size());
      output.writeInt(value);
    }
  }
}
