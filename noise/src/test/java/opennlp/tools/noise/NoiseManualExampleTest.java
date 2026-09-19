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

import org.junit.jupiter.api.Test;

import opennlp.tools.assets.AssetAnnotator;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the scoring and document examples in {@code noise.xml}.
 */
public class NoiseManualExampleTest {

  private static final String TEXT = "rnodern tirnes and zxkcvbnmsdfg";

  /** The scoring example prints exactly the two stated lines. */
  @Test
  void testScoringExamplePrintsTheStatedLines() {
    final Set<String> words = Set.of("modern", "times");
    final NoiseScorer scorer = new StructuralNoiseScorer(words::contains);
    final List<NoiseSpan> noise = scorer.score(TEXT, List.of());
    assertEquals(2, noise.size());
    assertEquals("misspelled [0..14)",
        noise.get(0).severity() + " " + noise.get(0).span());
    assertEquals("gibberish [19..31)",
        noise.get(1).severity() + " " + noise.get(1).span());
  }

  /** The chapter's contrast: without a dictionary only the gibberish is reported. */
  @Test
  void testWithoutADictionaryOnlyTheGibberishRemains() {
    final List<NoiseSpan> noise = new StructuralNoiseScorer().score(TEXT, List.of());
    assertEquals(1, noise.size());
    assertEquals(NoiseSpan.SEVERITY_GIBBERISH, noise.get(0).severity());
  }

  /** Exercises the asset-aware manual example. */
  @Test
  void testDocumentExample() {
    String text = "The scan contains zxkcvbnmsdfg here.";
    Document document = new AssetAnnotator().annotate(Document.of(text));
    document = new NoiseAnnotator().annotate(document);

    List<Annotation<NoiseSpan>> noise = document.get(NoiseAnnotator.NOISE);
    assertEquals(1, noise.size());
    assertEquals("zxkcvbnmsdfg", noise.get(0).span().getCoveredText(text).toString());
    assertEquals(NoiseSpan.SEVERITY_GIBBERISH, noise.get(0).value().severity());
    assertEquals(List.of(), document.get(AssetAnnotator.ASSETS));
  }

  /** Exercises the standalone manual example. */
  @Test
  void testStandaloneDocumentExample() {
    Document document = new NoiseAnnotator(new StructuralNoiseScorer(), false)
        .annotate(Document.of("The scan contains zxkcvbnmsdfg here."));
    List<Annotation<NoiseSpan>> noise = document.get(NoiseAnnotator.NOISE);

    assertEquals(1, noise.size());
    assertEquals("zxkcvbnmsdfg", noise.get(0).span().getCoveredText(document.text()).toString());
    assertEquals(NoiseSpan.SEVERITY_GIBBERISH, noise.get(0).value().severity());
  }
}
