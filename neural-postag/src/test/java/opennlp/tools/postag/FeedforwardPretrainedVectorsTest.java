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
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import opennlp.tools.util.ObjectStreamUtils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the opt-in pretrained-vector block of the feedforward tagger. The corpus is built
 * so that every discrete feature is identical across all examples: each word occurs once
 * (below the word cutoff, so all share the unknown embedding), all words share their
 * suffixes and shape, and every sentence is a single token. Only the supplied word
 * vectors distinguish the two tag classes, so training without them provably cannot
 * separate the classes while training with them must.
 */
public class FeedforwardPretrainedVectorsTest {

  /** Class A words: unique, one occurrence each, all ending in the shared suffixes. */
  private static final List<String> CLASS_A = List.of("aazqx", "bazqx", "cazqx", "dazqx");

  /** Class B words, indistinguishable from class A by every discrete feature. */
  private static final List<String> CLASS_B = List.of("eezqx", "fezqx", "gezqx", "hezqx");

  /** Maps a class A word to (1, 0) and a class B word to (0, 1); the only class signal. */
  private static final Function<CharSequence, float[]> VECTORS =
      word -> word.charAt(0) <= 'd' ? new float[] {1f, 0f} : new float[] {0f, 1f};

  /**
   * Builds the corpus: one single-token sentence per word, tag {@code A} or {@code B}.
   *
   * @return The training samples. Never {@code null}.
   */
  private static List<POSSample> corpus() {
    final List<POSSample> samples = new ArrayList<>();
    for (final String word : CLASS_A) {
      samples.add(new POSSample(new String[] {word}, new String[] {"A"}));
    }
    for (final String word : CLASS_B) {
      samples.add(new POSSample(new String[] {word}, new String[] {"B"}));
    }
    return samples;
  }

  /**
   * @return Small deterministic hyperparameters without dropout, so the tiny task
   *         converges and both runs are reproducible.
   */
  private static FeedforwardPOSTrainer.Settings settings() {
    return new FeedforwardPOSTrainer.Settings(8, 16, 60, 4, 0.1, 1e-8, 0.0, 2, 2, 17L);
  }

  /**
   * Asserts the discriminating pair. Without vectors every example presents the same
   * input, so the model necessarily assigns every word one and the same tag. With
   * vectors the same corpus and hyperparameters separate perfectly.
   *
   * @throws IOException Thrown if reading the in-memory sample stream fails.
   */
  @Test
  void testVectorsSeparateWhatDiscreteFeaturesCannot() throws IOException {
    final FeedforwardPOSModel control = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings());
    assertFalse(control.usesPretrainedVectors());
    final FeedforwardPOSTagger controlTagger = new FeedforwardPOSTagger(control);
    final String firstTag = controlTagger.tag(new String[] {CLASS_A.get(0)})[0];
    for (final String word : CLASS_A) {
      assertEquals(firstTag, controlTagger.tag(new String[] {word})[0],
          "identical inputs must tag identically");
    }
    for (final String word : CLASS_B) {
      assertEquals(firstTag, controlTagger.tag(new String[] {word})[0],
          "identical inputs must tag identically");
    }

    final FeedforwardPOSModel model = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings(), VECTORS);
    assertTrue(model.usesPretrainedVectors());
    final FeedforwardPOSTagger tagger = new FeedforwardPOSTagger(model);
    for (final String word : CLASS_A) {
      assertEquals("A", tagger.tag(new String[] {word})[0], word);
    }
    for (final String word : CLASS_B) {
      assertEquals("B", tagger.tag(new String[] {word})[0], word);
    }
  }

  /**
   * Asserts the format versioning: a model without the block keeps the original magic,
   * one with the block carries the versioned magic, and a serialize/load round trip
   * preserves every tagging decision, including over words without a stored vector.
   *
   * @throws IOException Thrown if the in-memory serialization round trip fails.
   */
  @Test
  void testRoundTripPreservesFormatAndTagging() throws IOException {
    final FeedforwardPOSModel model = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings(), VECTORS);
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    model.serialize(bytes);
    assertEquals("ONLP-FFPT-2", new DataInputStream(
        new ByteArrayInputStream(bytes.toByteArray())).readUTF());

    final FeedforwardPOSModel control = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings());
    final ByteArrayOutputStream controlBytes = new ByteArrayOutputStream();
    control.serialize(controlBytes);
    assertEquals("ONLP-FFPT-1", new DataInputStream(
        new ByteArrayInputStream(controlBytes.toByteArray())).readUTF());

    final FeedforwardPOSModel loaded =
        FeedforwardPOSModel.load(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(loaded.usesPretrainedVectors());
    final FeedforwardPOSTagger before = new FeedforwardPOSTagger(model);
    final FeedforwardPOSTagger after = new FeedforwardPOSTagger(loaded);
    final String[] sentence = {"aazqx", "eezqx", "zzzqx"};
    assertArrayEquals(before.tag(sentence), after.tag(sentence),
        "the reloaded model must decide exactly like the original");
  }

  /**
   * Asserts the lexicon overload: two words absent from the training data get their
   * vectors stored through the lexicon, so tagging separates them by vector class,
   * while the same words on a model trained without the lexicon present identical
   * inputs and provably tag identically.
   *
   * @throws IOException Thrown if reading the in-memory sample stream fails.
   */
  @Test
  void testLexiconExtendsCoverageBeyondTrainingWords() throws IOException {
    final FeedforwardPOSModel withLexicon = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings(), VECTORS,
        List.of("aazqy", "eezqy"));
    final FeedforwardPOSTagger tagger = new FeedforwardPOSTagger(withLexicon);
    assertEquals("A", tagger.tag(new String[] {"aazqy"})[0]);
    assertEquals("B", tagger.tag(new String[] {"eezqy"})[0]);

    final FeedforwardPOSModel without = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings(), VECTORS);
    final FeedforwardPOSTagger withoutTagger = new FeedforwardPOSTagger(without);
    assertEquals(withoutTagger.tag(new String[] {"aazqy"})[0],
        withoutTagger.tag(new String[] {"eezqy"})[0],
        "without the lexicon both words score the block as zeros and must tag alike");
  }

  /**
   * A lexicon word the source has no vector for is skipped, not an error.
   *
   * @throws IOException Thrown if reading the in-memory sample stream fails.
   */
  @Test
  void testLexiconWordsWithoutAVectorAreSkipped() throws IOException {
    final Function<CharSequence, float[]> partial =
        word -> word.charAt(0) == 'z' ? null : VECTORS.apply(word);
    final FeedforwardPOSModel model = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings(), partial,
        List.of("zzskip", "aazqy"));
    assertTrue(model.usesPretrainedVectors());
    assertEquals("A", new FeedforwardPOSTagger(model).tag(new String[] {"aazqy"})[0]);
  }

  /** The lexicon overload must reject a {@code null} lexicon and a contained {@code null}. */
  @Test
  void testLexiconRejectsContractViolations() {
    assertThrows(IllegalArgumentException.class, () -> FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings(), VECTORS, null),
        "a null lexicon must fail loud on the lexicon overload");
    assertThrows(IllegalArgumentException.class, () -> FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings(), VECTORS,
        Arrays.asList("aazqy", null)),
        "a lexicon containing null must fail loud");
  }

  /**
   * A word the model never saw scores the vector block as zeros and still tags.
   *
   * @throws IOException Thrown if reading the in-memory sample stream fails.
   */
  @Test
  void testWordsWithoutAStoredVectorStillTag() throws IOException {
    final FeedforwardPOSModel model = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings(), VECTORS);
    final String[] tags = new FeedforwardPOSTagger(model).tag(new String[] {"zzzqx"});
    assertEquals(1, tags.length);
    assertTrue("A".equals(tags[0]) || "B".equals(tags[0]));
  }

  /**
   * Asserts the fail-loud contract of the vector option: a {@code null} or degenerate
   * vector source is rejected at training time, and scoring must refuse both a plain
   * model given vector rows and a vectored model given none.
   *
   * @throws IOException Thrown if training the two models for the scoring checks fails.
   */
  @Test
  void testRejectsContractViolations() throws IOException {
    assertThrows(IllegalArgumentException.class, () -> FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings(), null));
    assertThrows(IllegalArgumentException.class, () -> FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings(), word -> null),
        "an embedder without a single vector must fail loud");
    assertThrows(IllegalArgumentException.class, () -> FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings(), word -> new float[0]),
        "an empty vector must fail loud");
    final Function<CharSequence, float[]> inconsistent =
        word -> word.charAt(0) == 'a' ? new float[] {1f} : new float[] {1f, 2f};
    assertThrows(IllegalArgumentException.class, () -> FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings(), inconsistent),
        "vectors of two different lengths must fail loud");

    final FeedforwardPOSModel plain = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings());
    final FeedforwardPOSModel vectored = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(corpus()), settings(), VECTORS);
    final int[] features = plain.featureIds(
        FeedforwardPOSContext.extract(new String[] {"aazqx"}, 0, null, null));
    assertThrows(IllegalArgumentException.class, () -> plain.score(features, new int[3]),
        "a plain model must reject vector rows");
    assertThrows(IllegalArgumentException.class, () -> vectored.score(features),
        "a vectored model must refuse to score without its block");
  }
}
