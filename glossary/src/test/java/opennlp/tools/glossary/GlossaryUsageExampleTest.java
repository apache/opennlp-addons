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

package opennlp.tools.glossary;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.document.Layers;
import opennlp.tools.stemmer.snowball.SnowballStemmer;
import opennlp.tools.stemmer.snowball.SnowballStemmerFactory;
import opennlp.tools.tokenize.TokenizerAnnotator;
import opennlp.tools.tokenize.WhitespaceTokenizer;
import opennlp.tools.util.Span;
import opennlp.tools.util.normalizer.EnglishContractionCharSequenceNormalizer;
import opennlp.tools.util.normalizer.GermanUmlautCharSequenceNormalizer;
import opennlp.tools.util.normalizer.OffsetAwareNormalizer;
import opennlp.tools.util.normalizer.TermAnalyzer;
import opennlp.tools.util.normalizer.TextNormalizer;

/**
 * Tests the pipeline and matching examples in the glossary manual.
 */
public class GlossaryUsageExampleTest {

  /**
   * Combines tokenization and glossary matching in a document pipeline.
   */
  @Test
  void testGlossaryPipelineEndToEnd() {
    final List<GlossaryEntry> glossary = List.of(
        new GlossaryEntry("LIB-1", "Apache OpenNLP"),
        new GlossaryEntry("ALG-42", "maximum entropy model"),
        new GlossaryEntry("ALG-7", "perceptron model"));

    // Token annotations and glossary annotations use separate layers.
    final DocumentAnalyzer analyzer = DocumentAnalyzer.builder()
        .add(new TokenizerAnnotator(WhitespaceTokenizer.INSTANCE))
        .add(new GlossaryAnnotator(new AhoCorasickGlossaryMatcher(glossary, true)))
        .build();

    final String text =
        "Apache OpenNLP ships a Maximum Entropy model and a perceptron model.";
    final Document document = analyzer.analyze(text);

    Assertions.assertTrue(document.layers().contains(Layers.TOKENS));
    Assertions.assertTrue(document.layers().contains(GlossaryAnnotator.GLOSSARY));
    final List<Annotation<String>> tokens = document.get(Layers.TOKENS);
    Assertions.assertEquals(11, tokens.size());
    Assertions.assertEquals("OpenNLP", tokens.get(1).value());

    final List<Annotation<GlossaryMatch>> hits = document.get(GlossaryAnnotator.GLOSSARY);
    Assertions.assertEquals(3, hits.size());
    assertHit(hits.get(0), 0, 14, "LIB-1", "Apache OpenNLP", "Apache OpenNLP", text);
    // Case-insensitive matching preserves the original capitalization in the source.
    assertHit(hits.get(1), 23, 44, "ALG-42", "maximum entropy model",
        "Maximum Entropy model", text);
    assertHit(hits.get(2), 51, 67, "ALG-7", "perceptron model", "perceptron model", text);
  }

  /**
   * Matches an ASCII registration to German sharp-s text with source offsets.
   */
  @Test
  void testOffsetAwareNormalizerManualExample() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("ST", "strasse")),
        true,
        GermanUmlautCharSequenceNormalizer.getInstance());

    final String text = "Die Stra\u00DFe ist frei.";
    final List<GlossaryMatch> hits = matcher.match(text);

    Assertions.assertEquals(1, hits.size());
    Assertions.assertEquals("ST", hits.get(0).id());
    Assertions.assertEquals("strasse", hits.get(0).term());
    Assertions.assertEquals("Stra\u00DFe",
        text.substring(hits.get(0).span().getStart(), hits.get(0).span().getEnd()));
  }

  /**
   * Matches a singular registration to a plural source phrase using English stemming.
   */
  @Test
  void testInflectedMultiwordManualExample() {
    final TermAnalyzer terms = TermAnalyzer.builder()
        .caseFold()
        .stem(new SnowballStemmerFactory(SnowballStemmer.ALGORITHM.ENGLISH))
        .build();
    final TermAnalyzingGlossaryMatcher matcher = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("FOOD", "hot dog")), terms);

    final String text = "the hot dogs were cold";
    final List<GlossaryMatch> hits = matcher.match(text);

    Assertions.assertEquals(1, hits.size());
    Assertions.assertEquals("FOOD", hits.get(0).id());
    Assertions.assertEquals("hot dog", hits.get(0).term());
    Assertions.assertEquals("hot dogs",
        text.substring(hits.get(0).span().getStart(), hits.get(0).span().getEnd()));
  }

  /**
   * Combines exact and inflected phrase matches in source order.
   */
  @Test
  void testCompositeMatcherManualExample() {
    final TermAnalyzer analyzer = TermAnalyzer.builder()
        .caseFold()
        .stem(new SnowballStemmerFactory(SnowballStemmer.ALGORITHM.ENGLISH))
        .build();
    final List<GlossaryEntry> glossary = List.of(
        new GlossaryEntry("NYC", "New York City"),
        new GlossaryEntry("FOOD", "hot dog"));
    final GlossaryMatcher exact = new AhoCorasickGlossaryMatcher(glossary, true);
    final GlossaryMatcher inflected = new TermAnalyzingGlossaryMatcher(glossary, analyzer);
    final GlossaryMatcher composite = new CompositeGlossaryMatcher(List.of(exact, inflected));
    final String text = "Hot dogs are sold across New York City.";
    final List<GlossaryMatch> matches = composite.match(text);

    Assertions.assertEquals(2, matches.size());
    Assertions.assertEquals("FOOD", matches.get(0).id());
    Assertions.assertEquals("Hot dogs",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
    Assertions.assertEquals("NYC", matches.get(1).id());
    Assertions.assertEquals("New York City",
        text.substring(matches.get(1).span().getStart(), matches.get(1).span().getEnd()));
    Assertions.assertEquals("New York City", matches.get(1).term());
  }

  /**
   * Expands a quoted contraction and stems a plural term with original-text offsets.
   */
  @Test
  void testContractionExpansionManualExample() {
    final TermAnalyzer terms = TermAnalyzer.builder()
        .caseFold()
        .stem(new SnowballStemmerFactory(SnowballStemmer.ALGORITHM.ENGLISH))
        .build();
    final OffsetAwareNormalizer contractions = TextNormalizer.builder()
        .with(EnglishContractionCharSequenceNormalizer.getInstance())
        .buildAligned();
    final TermAnalyzingGlossaryMatcher expanded = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("SHIPPING", "can not ship order")), terms, contractions);

    final String text = "We 'can't ship orders' today.";
    final List<GlossaryMatch> expandedHits = expanded.match(text);

    Assertions.assertEquals(1, expandedHits.size());
    Assertions.assertEquals("SHIPPING", expandedHits.get(0).id());
    Assertions.assertEquals(new Span(4, 21), expandedHits.get(0).span());
    Assertions.assertEquals("can't ship orders", text.substring(expandedHits.get(0).span().getStart(),
        expandedHits.get(0).span().getEnd()));
  }

  /**
   * Finds Tokyo in unspaced Japanese text without configuring a tokenizer.
   */
  @Test
  void testUnspacedCjkManualExample() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("Q1490", "\u6771\u4EAC")), false);

    final String text = "\u79C1\u306F\u6771\u4EAC\u306B\u4F4F\u3080";
    final List<GlossaryMatch> hits = matcher.match(text);

    Assertions.assertEquals(1, hits.size());
    Assertions.assertEquals("Q1490", hits.get(0).id());
    Assertions.assertEquals("\u6771\u4EAC",
        text.substring(hits.get(0).span().getStart(), hits.get(0).span().getEnd()));
  }

  /**
   * Returns an empty list for a missing glossary layer without adding that layer.
   */
  @Test
  void testReadingTheGlossaryLayerOfAnUnannotatedDocumentIsEmpty() {
    final Document document = Document.of("No glossary annotator ran over this text.");

    Assertions.assertTrue(document.get(GlossaryAnnotator.GLOSSARY).isEmpty());
    Assertions.assertFalse(document.layers().contains(GlossaryAnnotator.GLOSSARY));
  }

  /**
   * Checks the annotation span, match fields, and covered source text.
   *
   * @param hit The glossary annotation to check. Must not be {@code null}.
   * @param expectedStart The expected span start, inclusive, in original text chars.
   * @param expectedEnd The expected span end, exclusive, in original text chars.
   * @param expectedId The expected identifier of the matched entry.
   * @param expectedTerm The expected registered term of the matched entry.
   * @param expectedCovered The expected text between the span offsets.
   * @param text The original document text the span refers to. Must not be {@code null}.
   */
  private void assertHit(Annotation<GlossaryMatch> hit, int expectedStart,
      int expectedEnd, String expectedId, String expectedTerm, String expectedCovered,
      String text) {
    Assertions.assertEquals(expectedStart, hit.span().getStart());
    Assertions.assertEquals(expectedEnd, hit.span().getEnd());
    Assertions.assertEquals(hit.span().getStart(), hit.value().span().getStart());
    Assertions.assertEquals(hit.span().getEnd(), hit.value().span().getEnd());
    Assertions.assertEquals(expectedId, hit.value().id());
    Assertions.assertEquals(expectedTerm, hit.value().term());
    Assertions.assertEquals(expectedCovered,
        text.substring(hit.span().getStart(), hit.span().getEnd()));
  }
}
