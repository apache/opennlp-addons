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
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.OrtException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import opennlp.dl.InferenceOptions;

public class TokenVectorsDLTest {

  /** Names a directory with {@code model.onnx} and {@code vocab.txt} to run the encoder. */
  private static final String ENCODER_DIR_PROPERTY = "opennlp.dl.encoder.dir";

  private static final Map<String, Integer> VOCAB = Map.of("[CLS]", 0, "[SEP]", 1, "[UNK]", 2,
      "the", 3, "play", 4, "##ing", 5, "##s", 6, ".", 7);

  /** An encoder over the tiny vocabulary with no session. */
  private TokenVectorsDL tokenizerOnly() {
    return new TokenVectorsDL(null, null, VOCAB, true);
  }

  @Test
  void testWordPiecesExcludeSpecialTokensAndFollowTheVocabulary() {
    final TokenVectorsDL encoder = tokenizerOnly();
    Assertions.assertArrayEquals(new long[] {3}, encoder.pieceIds("The"));
    Assertions.assertArrayEquals(new long[] {4, 5}, encoder.pieceIds("playing"));
    Assertions.assertArrayEquals(new long[] {4, 6, 7}, encoder.pieceIds("plays."));
    Assertions.assertArrayEquals(new long[] {2}, encoder.pieceIds("zebra"));
    Assertions.assertEquals(0, encoder.pieceIds("").length);
  }

  @Test
  void testWordPiecesWithRobertaSpecialTokens() {
    final Map<String, Integer> vocab = Map.of("<s>", 8, "</s>", 9, "<unk>", 10,
        "the", 3, "play", 4, "##ing", 5);
    final TokenVectorsDL encoder = new TokenVectorsDL(null, null, vocab, true);

    Assertions.assertArrayEquals(new long[] {3}, encoder.pieceIds("The"));
    Assertions.assertArrayEquals(new long[] {4, 5}, encoder.pieceIds("playing"));
    Assertions.assertArrayEquals(new long[] {10}, encoder.pieceIds("zebra"));
    Assertions.assertEquals(0, encoder.pieceIds("").length);
  }

  @Test
  void testWindowsPackWordsUpToTheLimitAndNeverSplitAWord() {
    final List<long[]> pieces = List.of(new long[2], new long[3], new long[1], new long[4],
        new long[0], new long[9]);
    // capacity is the limit less the two special tokens
    final List<int[]> windows = TokenVectorsDL.windows(pieces, 8);
    Assertions.assertEquals(3, windows.size());
    Assertions.assertArrayEquals(new int[] {0, 3}, windows.get(0)); // 2 + 3 + 1 = 6
    Assertions.assertArrayEquals(new int[] {3, 5}, windows.get(1)); // 4 + 0
    Assertions.assertArrayEquals(new int[] {5, 6}, windows.get(2)); // 9 alone, over the limit
    Assertions.assertEquals(1, TokenVectorsDL.windows(List.of(new long[1]), 3).size());
  }

  @Test
  void testRejectsInvalidInput() {
    final TokenVectorsDL encoder = tokenizerOnly();
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new TokenVectorsDL(null, null, null, true));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new TokenVectorsDL(null, null, VOCAB, true, null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new TokenVectorsDL(null, null, VOCAB, true, new InferenceOptions(), 0));
    Assertions.assertThrows(IllegalArgumentException.class, () -> encoder.vectors(null));
    Assertions.assertThrows(IllegalArgumentException.class, () -> encoder.vectors(new String[0]));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> encoder.vectors(new String[] {"the", null}));
  }

  @Test
  void testInferenceOptionsSelectModelInputsAndAreSnapshotted() {
    final InferenceOptions options = new InferenceOptions();
    options.setIncludeAttentionMask(false);
    options.setIncludeTokenTypeIds(false);
    final TokenVectorsDL encoder = new TokenVectorsDL(null, null, VOCAB, true, options);

    final long[] ids = {0, 3, 1};
    final long[] mask = {1, 1, 1};
    final long[] types = {0, 0, 0};
    Assertions.assertEquals(Set.of("input_ids"),
        encoder.inputValues(ids, mask, types).keySet());

    options.setIncludeAttentionMask(true);
    options.setIncludeTokenTypeIds(true);
    Assertions.assertEquals(Set.of("input_ids"),
        encoder.inputValues(ids, mask, types).keySet());
    Assertions.assertEquals(Set.of("input_ids", "attention_mask", "token_type_ids"),
        tokenizerOnly().inputValues(ids, mask, types).keySet());
  }

  @Test
  void testRejectsInvalidEncoderOutput() {
    Assertions.assertThrows(IllegalStateException.class,
        () -> TokenVectorsDL.hiddenFromOutput(null, 3));
    Assertions.assertThrows(IllegalStateException.class,
        () -> TokenVectorsDL.hiddenFromOutput(new float[0][][], 3));
    Assertions.assertThrows(IllegalStateException.class,
        () -> TokenVectorsDL.hiddenFromOutput(new float[][][] {{{1}, {2}}}, 3));
    Assertions.assertThrows(IllegalStateException.class,
        () -> TokenVectorsDL.hiddenFromOutput(new float[][][] {{{1}, {2, 3}, {4}}}, 3));
    Assertions.assertThrows(IllegalStateException.class,
        () -> TokenVectorsDL.hiddenFromOutput(new float[][][] {{{}, {}, {}}}, 3));
    Assertions.assertThrows(IllegalStateException.class,
        () -> TokenVectorsDL.hiddenFromOutput(
            new float[][][] {{{1}, {Float.NaN}, {3}}}, 3));

    final float[][] expected = {{1, 2}, {3, 4}, {5, 6}};
    Assertions.assertSame(expected,
        TokenVectorsDL.hiddenFromOutput(new float[][][] {expected}, 3));
  }

  @Test
  void testMeanDoesNotOverflowForFiniteHiddenValues() {
    final float[] mean = TokenVectorsDL.mean(
        new float[][] {{Float.MAX_VALUE}, {Float.MAX_VALUE}}, 0, 2);

    Assertions.assertEquals(Float.MAX_VALUE, mean[0]);
    Assertions.assertTrue(Float.isFinite(mean[0]));
  }

  @Test
  @EnabledIfSystemProperty(named = ENCODER_DIR_PROPERTY, matches = ".+")
  void testEncoderAlignsOneVectorPerWordInContext() throws OrtException, IOException {
    final File directory = new File(System.getProperty(ENCODER_DIR_PROPERTY));
    try (TokenVectorsDL encoder = new TokenVectorsDL(new File(directory, "model.onnx"),
        new File(directory, "vocab.txt"))) {
      final float[][] vectors = encoder.vectors(
          new String[] {"The", "bank", "approved", "the", "loan", "."});
      Assertions.assertEquals(6, vectors.length);
      Assertions.assertEquals(vectors[0].length, encoder.dimension());
      for (final float[] vector : vectors) {
        Assertions.assertEquals(vectors[0].length, vector.length);
      }
      final float[][] river = encoder.vectors(
          new String[] {"The", "bank", "of", "the", "river", "flooded", "."});
      // the same word in two contexts gets two vectors, and both differ from a neighbor
      Assertions.assertTrue(cosine(vectors[1], river[1]) < 0.999);
      Assertions.assertTrue(cosine(vectors[1], river[1]) > cosine(vectors[1], vectors[2]));
      // a very long sentence is encoded in windows, one vector per word
      final String[] words = new String[1200];
      java.util.Arrays.fill(words, "unbelievable");
      Assertions.assertEquals(words.length, encoder.vectors(words).length);
    }
  }

  private double cosine(float[] u, float[] v) {
    double dot = 0;
    double uu = 0;
    double vv = 0;
    for (int d = 0; d < u.length; d++) {
      dot += u[d] * v[d];
      uu += u[d] * u[d];
      vv += v[d] * v[d];
    }
    return dot / Math.sqrt(uu * vv);
  }
}
