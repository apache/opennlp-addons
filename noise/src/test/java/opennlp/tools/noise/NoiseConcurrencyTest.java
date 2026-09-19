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

package opennlp.tools.noise;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import opennlp.tools.assets.AssetAnnotator;
import opennlp.tools.assets.EmbeddedAsset;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests concurrent use of shared noise components. */
public class NoiseConcurrencyTest {

  /** Shared instances keep concurrent calls and document results independent. */
  @Test
  void testSharedInstancesAreSafeForConcurrentUse() throws Exception {
    final Set<String> words = Set.of("hello", "modern");
    final StructuralNoiseScorer scorer = concurrentScorer(words);
    final List<Callable<List<NoiseSpan>>> scorerCalls = List.of(
        () -> scorer.score("hello bcdfg", List.of()),
        () -> scorer.score("rnodern", List.of()),
        () -> scorer.score("hello bcdfg zxkcvbnmsdfg", List.of(new Span(6, 11))),
        () -> scorer.score("\ud83d\ude00 (bcdfg),\u2003end", List.of()));
    final List<List<NoiseSpan>> expectedScores = List.of(
        List.of(new NoiseSpan(new Span(6, 11), NoiseSpan.SEVERITY_DAMAGED, 0.5)),
        List.of(new NoiseSpan(new Span(0, 7), NoiseSpan.SEVERITY_MISSPELLED, 0.9)),
        List.of(new NoiseSpan(new Span(12, 24), NoiseSpan.SEVERITY_GIBBERISH, 0.5)),
        List.of(new NoiseSpan(new Span(4, 9), NoiseSpan.SEVERITY_DAMAGED, 0.5)));
    final NoiseAnnotator annotator = new NoiseAnnotator(concurrentScorer(words), true);
    final List<Document> inputs = List.of(
        withAsset("hello bcdfg", new Span(0, 5)),
        withAsset("rnodern bcdfg", new Span(8, 13)),
        withAsset("zxkcvbnmsdfg hello", new Span(13, 18)),
        withAsset("\ud83d\ude00 (bcdfg),\u2003end", new Span(12, 15)));
    final List<List<Annotation<NoiseSpan>>> expectedAnnotations = List.of(
        List.of(new Annotation<>(new Span(6, 11), expectedScores.get(0).get(0))),
        List.of(new Annotation<>(new Span(0, 7), expectedScores.get(1).get(0))),
        List.of(new Annotation<>(new Span(0, 12),
            new NoiseSpan(new Span(0, 12), NoiseSpan.SEVERITY_GIBBERISH, 0.5))),
        List.of(new Annotation<>(new Span(4, 9), expectedScores.get(3).get(0))));

    try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
      final List<Future<List<NoiseSpan>>> scores = executor.invokeAll(scorerCalls);
      for (int i = 0; i < scores.size(); i++) {
        assertEquals(expectedScores.get(i), scores.get(i).get());
      }

      final List<Future<Document>> annotated = executor.invokeAll(inputs.stream()
          .<Callable<Document>>map(input -> () -> annotator.annotate(input))
          .toList());
      for (int i = 0; i < annotated.size(); i++) {
        final Document result = annotated.get(i).get();
        assertEquals(inputs.get(i).text().toString(), result.text().toString());
        assertFalse(inputs.get(i).layers().contains(NoiseAnnotator.NOISE));
        assertTrue(result.layers().contains(NoiseAnnotator.NOISE));
        assertSame(inputs.get(i).get(AssetAnnotator.ASSETS).get(0),
            result.get(AssetAnnotator.ASSETS).get(0));
        assertEquals(expectedAnnotations.get(i), result.get(NoiseAnnotator.NOISE));
      }
    }
  }

  /**
   * Synchronizes the first dictionary lookup of four concurrent scoring calls.
   *
   * @param words The accepted dictionary words.
   * @return The scorer with a synchronized dictionary callback.
   */
  private StructuralNoiseScorer concurrentScorer(Set<String> words) {
    final CountDownLatch enteredDictionary = new CountDownLatch(4);
    return new StructuralNoiseScorer(word -> {
      enteredDictionary.countDown();
      try {
        assertTrue(enteredDictionary.await(5, TimeUnit.SECONDS),
            "All four scoring calls must enter the dictionary callback");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Dictionary synchronization was interrupted", e);
      }
      return words.contains(word.toString());
    });
  }

  /**
   * Creates a document with one positional asset annotation.
   *
   * @param text The source text.
   * @param span The asset span to exclude from scoring.
   * @return The document with an asset layer.
   */
  private Document withAsset(String text, Span span) {
    final EmbeddedAsset asset = new EmbeddedAsset(span, span, "raw",
        "application/octet-stream", span.length(), -1, -1);
    return Document.of(text).with(AssetAnnotator.ASSETS,
        List.of(new Annotation<>(span, asset)));
  }
}
