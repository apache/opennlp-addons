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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import opennlp.tools.util.CollectionObjectStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link BilstmPOSModel} serialization and the thread-safety of tagging.
 */
class BilstmPOSModelTest {

  private static final List<POSSample> CORPUS = List.of(
      new POSSample(new String[] {"The", "cat", "sat"}, new String[] {"D", "N", "V"}),
      new POSSample(new String[] {"A", "dog", "ran"}, new String[] {"D", "N", "V"}));

  private static BilstmPOSModel tinyModel() throws IOException {
    return BilstmPOSTrainer.train(new CollectionObjectStream<>(CORPUS),
        new BilstmPOSTrainer.Settings(8, 4, 4, 8, 3, 2, 5e-3d, 5.0d, 0.1d, 1, 12, 7L, 2, 0.0d, 0, false, 1,
            0.0d, 0.0d, 1.0d, 0.0d, false));
  }

  @Test
  void testSerializationRoundTripPreservesScores() throws IOException {
    final BilstmPOSModel model = tinyModel();
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    model.serialize(buffer);
    final BilstmPOSModel loaded =
        BilstmPOSModel.load(new ByteArrayInputStream(buffer.toByteArray()));
    assertArrayEquals(model.tags(), loaded.tags());
    final String[] sentence = {"The", "dog", "sat"};
    final double[][] expected = model.score(sentence);
    final double[][] actual = loaded.score(sentence);
    assertEquals(expected.length, actual.length);
    for (int t = 0; t < expected.length; t++) {
      assertArrayEquals(expected[t], actual[t], 0.0d);
    }
  }

  @Test
  void testSerializationRoundTripPreservesPretrainedTable() throws IOException {
    final BilstmPOSModel model = BilstmPOSTrainer.train(
        new CollectionObjectStream<>(CORPUS),
        new BilstmPOSTrainer.Settings(8, 4, 4, 8, 3, 2, 5e-3d, 5.0d, 0.1d, 1, 12, 7L, 2, 0.0d, 0, false, 1,
            0.0d, 0.0d, 1.0d, 0.0d, false),
        w -> new float[] {w.length(), 1.0f}, List.of("unseen"));
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    model.serialize(buffer);
    final BilstmPOSModel loaded =
        BilstmPOSModel.load(new ByteArrayInputStream(buffer.toByteArray()));
    assertArrayEquals(model.wordRepresentation("unseen"),
        loaded.wordRepresentation("unseen"), 0.0d);
  }

  @Test
  void testSerializationRoundTripPreservesAdapter() throws IOException {
    final BilstmPOSModel model = BilstmPOSTrainer.train(
        new CollectionObjectStream<>(CORPUS),
        new BilstmPOSTrainer.Settings(8, 4, 4, 8, 3, 2, 5e-3d, 5.0d, 0.1d, 1, 12, 7L, 2, 0.0d, 0, false, 1,
            0.0d, 0.0d, 1.0d, 0.0d, true),
        w -> new float[] {w.length(), 1.0f}, List.of("unseen"));
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    model.serialize(buffer);
    // an adapter model is written under the new magic; adapter-less models keep 1-4
    assertEquals("ONLP-BLPT-5", magicOf(buffer.toByteArray()));
    final BilstmPOSModel loaded =
        BilstmPOSModel.load(new ByteArrayInputStream(buffer.toByteArray()));
    final String[] sentence = {"The", "dog", "sat", "unseen"};
    final double[][] expected = model.score(sentence);
    final double[][] actual = loaded.score(sentence);
    assertEquals(expected.length, actual.length);
    for (int t = 0; t < expected.length; t++) {
      assertArrayEquals(expected[t], actual[t], 0.0d);
    }
  }

  @Test
  void testIdentityAdapterMatchesFrozenPassThrough() {
    // twin models over shared components: the identity adapter must compute
    // byte-identical representations and scores to the frozen copy, and a
    // perturbed adapter must actually change the representation (so the
    // transform cannot be silently dropped)
    final LinkedHashMap<String, Integer> words = new LinkedHashMap<>();
    words.put(BilstmPOSModel.UNKNOWN, 0);
    words.put("cat", 1);
    final LinkedHashMap<String, Integer> chars = new LinkedHashMap<>();
    chars.put(BilstmPOSModel.UNKNOWN, 0);
    chars.put("c", 1);
    chars.put("a", 2);
    chars.put("t", 3);
    final String[] tags = {"D", "N", "V"};
    final Random random = new Random(7L);
    final double[][] wordEmbeddings = randomMatrix(words.size(), 4, random);
    final double[][] charEmbeddings = randomMatrix(chars.size(), 3, random);
    final LstmLayer charForward = new LstmLayer(3, 2, random);
    final LstmLayer charBackward = new LstmLayer(3, 2, random);
    final LstmLayer wordForward = new LstmLayer(4 + 2 * 2 + 2, 3, random);
    final LstmLayer wordBackward = new LstmLayer(4 + 2 * 2 + 2, 3, random);
    final double[][] outputWeights = randomMatrix(tags.length, 2 * 3, random);
    final double[] outputBias = new double[tags.length];
    final LinkedHashMap<String, Integer> pretrainedIds = new LinkedHashMap<>();
    pretrainedIds.put("cat", 0);
    final float[][] pretrainedVectors = {{0.5f, -0.25f}};
    final BilstmPOSModel frozen = new BilstmPOSModel(words, chars, tags,
        wordEmbeddings, charEmbeddings, charForward, charBackward, wordForward,
        wordBackward, null, null, outputWeights, outputBias, 10, pretrainedIds,
        pretrainedVectors, null, null, null);
    final double[][] identity = new double[2][2];
    identity[0][0] = 1.0d;
    identity[1][1] = 1.0d;
    final BilstmPOSModel adapted = new BilstmPOSModel(words, chars, tags,
        wordEmbeddings, charEmbeddings, charForward, charBackward, wordForward,
        wordBackward, null, null, outputWeights, outputBias, 10, pretrainedIds,
        pretrainedVectors, null, null, null, identity, new double[2]);
    assertArrayEquals(frozen.wordRepresentation("cat"),
        adapted.wordRepresentation("cat"), 0.0d);
    assertArrayEquals(frozen.wordRepresentation("dog"),
        adapted.wordRepresentation("dog"), 0.0d);
    final String[] sentence = {"cat", "dog"};
    final double[][] frozenScores = frozen.score(sentence);
    final double[][] adaptedScores = adapted.score(sentence);
    for (int t = 0; t < sentence.length; t++) {
      assertArrayEquals(frozenScores[t], adaptedScores[t], 0.0d);
    }
    identity[0][1] = 0.5d;
    final double[] moved = adapted.wordRepresentation("cat");
    final double[] plain = frozen.wordRepresentation("cat");
    boolean differs = false;
    for (int i = 0; i < moved.length; i++) {
      if (moved[i] != plain[i]) {
        differs = true;
      }
    }
    assertTrue(differs, "a non-identity adapter must change the representation");
  }

  private static String magicOf(byte[] serialized) {
    // DataOutputStream.writeUTF: two length bytes, then modified (ASCII-clean) UTF-8
    return new String(serialized, 2, serialized[1], StandardCharsets.UTF_8);
  }

  private static double[][] randomMatrix(int rows, int cols, Random random) {
    final double[][] matrix = new double[rows][cols];
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        matrix[r][c] = random.nextDouble() * 2.0d - 1.0d;
      }
    }
    return matrix;
  }

  @Test
  void testSerializationRoundTripPreservesCrfLayer() throws IOException {
    final BilstmPOSModel model = BilstmPOSTrainer.train(
        new CollectionObjectStream<>(CORPUS),
        new BilstmPOSTrainer.Settings(8, 4, 4, 8, 3, 2, 5e-3d, 5.0d, 0.1d, 1, 12, 7L, 2, 0.0d, 0, true, 1,
            0.0d, 0.0d, 1.0d, 0.0d, false));
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    model.serialize(buffer);
    final BilstmPOSModel loaded =
        BilstmPOSModel.load(new ByteArrayInputStream(buffer.toByteArray()));
    assertEquals(model.isCrf(), loaded.isCrf());
    final String[] sentence = {"The", "dog", "sat"};
    assertArrayEquals(new BilstmPOSTagger(model).tag(sentence),
        new BilstmPOSTagger(loaded).tag(sentence));
  }

  @Test
  void testSerializationRoundTripPreservesTwoLayerCrf() throws IOException {
    // regression: the reader used to skip transitions unless the magic was
    // exactly ONLP-BLPT-2, corrupting two-layer CRF (ONLP-BLPT-4) loads
    final BilstmPOSModel model = BilstmPOSTrainer.train(
        new CollectionObjectStream<>(CORPUS),
        new BilstmPOSTrainer.Settings(8, 4, 4, 8, 3, 2, 5e-3d, 5.0d, 0.1d, 1, 12, 7L, 2, 0.0d, 0, true, 2,
            0.0d, 0.0d, 1.0d, 0.0d, false));
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    model.serialize(buffer);
    assertEquals("ONLP-BLPT-4", magicOf(buffer.toByteArray()));
    final BilstmPOSModel loaded =
        BilstmPOSModel.load(new ByteArrayInputStream(buffer.toByteArray()));
    assertEquals(model.isCrf(), loaded.isCrf());
    final String[] sentence = {"The", "dog", "sat"};
    assertArrayEquals(new BilstmPOSTagger(model).tag(sentence),
        new BilstmPOSTagger(loaded).tag(sentence));
  }

  @Test
  void testRejectsForeignMagic() {
    final byte[] junk = "ONLP-FFPT-2\0\0\0\0".getBytes();
    assertThrows(IOException.class,
        () -> BilstmPOSModel.load(new ByteArrayInputStream(junk)));
  }

  @Test
  void testRejectsNullAndEmpty() {
    final BilstmPOSTagger tagger;
    try {
      tagger = new BilstmPOSTagger(tinyModel());
    }
    catch (IOException e) {
      throw new AssertionError(e);
    }
    assertThrows(IllegalArgumentException.class, () -> tagger.tag(null));
    assertEquals(0, tagger.tag(new String[0]).length);
  }

  @Test
  void testSharedTaggerIsThreadSafe()
      throws IOException, InterruptedException, ExecutionException {
    final BilstmPOSTagger tagger = new BilstmPOSTagger(tinyModel());
    final String[][] sentences = {
        {"The", "cat", "sat"}, {"A", "dog", "ran"}, {"The", "dog", "sat", "quickly"}};
    final String[][] expected = new String[sentences.length][];
    for (int i = 0; i < sentences.length; i++) {
      expected[i] = tagger.tag(sentences[i]);
    }
    final ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      final List<Callable<Boolean>> tasks = new ArrayList<>();
      for (int i = 0; i < sentences.length; i++) {
        final int index = i;
        tasks.add(() -> {
          for (int iteration = 0; iteration < 200; iteration++) {
            final String[] tagged = tagger.tag(sentences[index]);
            for (int t = 0; t < tagged.length; t++) {
              if (!tagged[t].equals(expected[index][t])) {
                return false;
              }
            }
          }
          return true;
        });
      }
      final List<Future<Boolean>> results = pool.invokeAll(tasks);
      for (final Future<Boolean> result : results) {
        assertEquals(Boolean.TRUE, result.get());
      }
    }
    finally {
      pool.shutdownNow();
    }
  }
}
