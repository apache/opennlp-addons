/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
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

package opennlp.tools.util.normalizer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Checks expansion tables, serialization, and shared use of the normalizer. */
class EnglishContractionContractTest {

  private static final EnglishContractionCharSequenceNormalizer NORMALIZER =
      EnglishContractionCharSequenceNormalizer.getInstance();

  /**
   * Checks supported negative and auxiliary forms in lowercase and uppercase.
   *
   * @param input The contraction.
   * @param expansion The expected lowercase expansion.
   */
  @ParameterizedTest
  @CsvSource(value = {"aren't|are not", "couldn't|could not", "daren't|dare not", "didn't|did not",
      "don't|do not", "doesn't|does not", "hadn't|had not", "hasn't|has not", "haven't|have not",
      "isn't|is not", "mayn't|may not", "mightn't|might not", "mustn't|must not", "needn't|need not",
      "oughtn't|ought not", "shouldn't|should not", "wasn't|was not", "weren't|were not",
      "wouldn't|would not", "how're|how are", "there're|there are", "they're|they are",
      "what're|what are", "when're|when are", "where're|where are", "who're|who are", "why're|why are",
      "you're|you are", "could've|could have", "I've|I have", "might've|might have", "must've|must have",
      "should've|should have", "we've|we have", "who've|who have", "would've|would have",
      "you've|you have", "he'll|he will", "how'll|how will", "it'll|it will", "she'll|she will",
      "that'll|that will", "there'll|there will", "they'll|they will", "we'll|we will",
      "what'll|what will", "when'll|when will", "where'll|where will", "who'll|who will",
      "why'll|why will", "you'll|you will"}, delimiter = '|')
  void testSupportedForms(String input, String expansion) {
    assertEquals(expansion, NORMALIZER.normalize(input).toString());
    final AlignedText aligned = NORMALIZER.normalizeAligned(input);
    assertEquals(expansion, aligned.normalizedString());
    assertEquals(new Span(0, input.length()), aligned.toOriginalSpan(0, expansion.length()));
    assertEquals(StringUtil.toUpperCase(expansion),
        NORMALIZER.normalize(StringUtil.toUpperCase(input)).toString());
    assertEquals(expansion, NORMALIZER.normalize(expansion).toString());
  }

  /**
   * Copies unsupported text around successful expansions without changing its alignment.
   *
   * @param unsupported The word or identifier that must be preserved.
   */
  @ParameterizedTest
  @ValueSource(strings = {"_can't", "can't1", "\u00E9can't", "can't've", "he's", ""})
  void testRejectedAndAcceptedCandidatesTogether(String unsupported) {
    final String input = unsupported + " won't " + unsupported + " 'can't' " + unsupported;
    final String expected = unsupported + " will not " + unsupported + " 'can not' " + unsupported;
    final AlignedText aligned = NORMALIZER.normalizeAligned(input);
    assertEquals(expected, NORMALIZER.normalize(input).toString());
    assertEquals(expected, aligned.normalizedString());
    final int start = expected.indexOf("will not");
    assertEquals(new Span(unsupported.length() + 1, unsupported.length() + 6),
        aligned.toOriginalSpan(start, start + "will not".length()));
  }

  /**
   * Restores the singleton when reading a serialized normalizer.
   *
   * @throws IOException If serialization fails.
   * @throws ClassNotFoundException If deserialization cannot load the normalizer.
   */
  @Test
  void testSerializationRestoresSingleton() throws IOException, ClassNotFoundException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(NORMALIZER);
    }
    try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      final Object restored = in.readObject();
      assertSame(NORMALIZER, restored);
      assertEquals("'can not'", ((OffsetAwareNormalizer) restored).normalize("'can't'").toString());
    }
  }

  /**
   * Shares one normalizer across independent calls without sharing alignment state.
   *
   * @throws Exception If a worker fails or times out.
   */
  @Test
  @Timeout(15)
  void testConcurrentCalls() throws Exception {
    final ExecutorService executor = Executors.newFixedThreadPool(4);
    try {
      final List<Future<AlignedText>> results = new ArrayList<>();
      for (int i = 0; i < 64; i++) {
        final String prefix = "item " + i + " ";
        results.add(executor.submit(() -> NORMALIZER.normalizeAligned(prefix + "'can't' _won't")));
      }
      for (int i = 0; i < results.size(); i++) {
        final String prefix = "item " + i + " ";
        final AlignedText result = results.get(i).get(10, TimeUnit.SECONDS);
        assertEquals(prefix + "'can not' _won't", result.normalizedString());
        assertEquals(new Span(prefix.length() + 1, prefix.length() + 6),
            result.toOriginalSpan(prefix.length() + 1, prefix.length() + 8));
      }
    } finally {
      executor.shutdownNow();
    }
  }
}
