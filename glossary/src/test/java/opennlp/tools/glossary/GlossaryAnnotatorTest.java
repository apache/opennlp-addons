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
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;
import opennlp.tools.util.Span;
import opennlp.tools.util.normalizer.TermAnalyzer;

import static opennlp.tools.glossary.GlossaryTestSupport.englishStemmingAnalyzer;

/** Tests glossary annotation and document layer validation. */
public class GlossaryAnnotatorTest {

  /** City entry used in document layer tests. */
  private static final GlossaryEntry NEW_YORK_CITY = new GlossaryEntry("Q60", "New York City");

  /**
   * Builds an annotator for {@link #NEW_YORK_CITY}.
   *
   * @param ignoreCase Whether the matcher matches regardless of character case.
   * @return The annotator to exercise. Never {@code null}.
   */
  private GlossaryAnnotator annotator(boolean ignoreCase) {
    return new GlossaryAnnotator(
        new AhoCorasickGlossaryMatcher(List.of(NEW_YORK_CITY), ignoreCase));
  }

  /**
   * Adds matching identifiers and spans to the glossary layer.
   */
  @Test
  void testProvidesGlossaryLayer() {
    final GlossaryAnnotator annotator = annotator(true);

    final Document document =
        annotator.annotate(Document.of("Prices in new york city keep climbing."));

    final List<Annotation<GlossaryMatch>> hits = document.get(GlossaryAnnotator.GLOSSARY);
    Assertions.assertEquals(1, hits.size());
    Assertions.assertEquals("Q60", hits.get(0).value().id());
    Assertions.assertEquals("new york city", document.text().subSequence(
        hits.get(0).span().getStart(), hits.get(0).span().getEnd()).toString());
    Assertions.assertTrue(document.layers().contains(GlossaryAnnotator.GLOSSARY));
  }

  /**
   * Adds an empty glossary layer when the input contains no matching terms.
   */
  @Test
  void testNoHitsStillProvidesEmptyGlossaryLayer() {
    final GlossaryAnnotator annotator = annotator(false);

    final Document noHits = annotator.annotate(Document.of("Nothing to see here."));
    Assertions.assertTrue(noHits.layers().contains(GlossaryAnnotator.GLOSSARY));
    Assertions.assertTrue(noHits.get(GlossaryAnnotator.GLOSSARY).isEmpty());

    final Document emptyText = annotator.annotate(Document.of(""));
    Assertions.assertTrue(emptyText.layers().contains(GlossaryAnnotator.GLOSSARY));
    Assertions.assertTrue(emptyText.get(GlossaryAnnotator.GLOSSARY).isEmpty());
  }

  /**
   * Rejects annotation of a document that already has a glossary layer.
   */
  @Test
  void testAnnotateTwiceRejectsDuplicateGlossaryLayer() {
    final GlossaryAnnotator annotator = annotator(false);

    final Document once = annotator.annotate(Document.of("New York City"));
    Assertions.assertEquals(1, once.get(GlossaryAnnotator.GLOSSARY).size());
    Assertions.assertThrows(IllegalArgumentException.class, () -> annotator.annotate(once));
  }

  /**
   * Rejects a duplicate output layer before invoking a potentially expensive matcher.
   *
   * @param text The already annotated source text.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "New York City"})
  void testDuplicateLayerRejectedBeforeMatching(String text) {
    final Document document = Document.of(text).with(GlossaryAnnotator.GLOSSARY, List.of());
    final GlossaryAnnotator annotator = new GlossaryAnnotator(input -> {
      throw new AssertionError("matcher must not run for a duplicate output layer");
    });
    Assertions.assertThrows(IllegalArgumentException.class, () -> annotator.annotate(document));
  }

  /**
   * Rejects a null matcher or document.
   */
  @Test
  void testInvalidArguments() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> new GlossaryAnnotator(null));
    final GlossaryAnnotator annotator = annotator(false);
    Assertions.assertThrows(IllegalArgumentException.class, () -> annotator.annotate(null));
  }

  /** Checks the pipeline input and output layer declarations. */
  @Test
  void testLayerDeclarations() {
    final GlossaryAnnotator annotator = annotator(false);
    Assertions.assertEquals(Set.of(), annotator.requires());
    Assertions.assertEquals(Set.of(GlossaryAnnotator.GLOSSARY), annotator.provides());
  }

  /** Preserves input text, token annotations, and a separately named glossary layer. */
  @Test
  void testPreservesInputAndOtherLayers() {
    final LayerKey<GlossaryMatch> referenceKey =
        LayerKey.of("reference:glossary", GlossaryMatch.class);
    final List<Annotation<String>> tokens = List.of(new Annotation<>(new Span(0, 3), "New"));
    final List<Annotation<GlossaryMatch>> reference = List.of(new Annotation<>(new Span(0, 8),
        new GlossaryMatch(new Span(0, 8), "STATE", "New York")));
    final Document input = Document.of(NEW_YORK_CITY.term())
        .with(Layers.TOKENS, tokens).with(referenceKey, reference);

    final Document result = annotator(false).annotate(input);

    Assertions.assertNotSame(input, result);
    Assertions.assertEquals(input.text(), result.text());
    Assertions.assertEquals(tokens, result.get(Layers.TOKENS));
    Assertions.assertEquals(reference, result.get(referenceKey));
    Assertions.assertFalse(input.layers().contains(GlossaryAnnotator.GLOSSARY));
    Assertions.assertEquals(List.of(new Annotation<>(new Span(0, 13),
        new GlossaryMatch(new Span(0, 13), NEW_YORK_CITY.id(), NEW_YORK_CITY.term()))),
        result.get(GlossaryAnnotator.GLOSSARY));
  }

  /**
   * Rejects a duplicate glossary layer even when a different matcher is used.
   */
  @Test
  void testSecondGlossaryAnnotatorRejectsDuplicateLayer() {
    final List<GlossaryEntry> glossary = List.of(NEW_YORK_CITY);
    final GlossaryAnnotator exact =
        new GlossaryAnnotator(new AhoCorasickGlossaryMatcher(glossary, true));
    final TermAnalyzer analyzer = englishStemmingAnalyzer();
    final GlossaryAnnotator inflected =
        new GlossaryAnnotator(new TermAnalyzingGlossaryMatcher(glossary, analyzer));

    final Document once = exact.annotate(Document.of("New York City"));
    Assertions.assertEquals(1, once.get(GlossaryAnnotator.GLOSSARY).size());
    Assertions.assertThrows(IllegalArgumentException.class, () -> inflected.annotate(once));
  }

  /**
   * Adds exact and inflected matches to a single glossary layer in source order.
   */
  @Test
  void testCompositeMatcherCarriesExactAndInflectedHitsInOneLayer() {
    final List<GlossaryEntry> glossary = List.of(
        new GlossaryEntry("NYC", "New York City"),
        new GlossaryEntry("FOOD", "hot dog"));
    final TermAnalyzer analyzer = englishStemmingAnalyzer();
    final GlossaryMatcher composite = new CompositeGlossaryMatcher(List.of(
        new AhoCorasickGlossaryMatcher(glossary, true),
        new TermAnalyzingGlossaryMatcher(glossary, analyzer)));
    final GlossaryAnnotator annotator = new GlossaryAnnotator(composite);

    final String text = "Hot dogs are sold across New York City.";
    final Document document = annotator.annotate(Document.of(text));

    final List<Annotation<GlossaryMatch>> hits = document.get(GlossaryAnnotator.GLOSSARY);
    Assertions.assertEquals(2, hits.size());
    Assertions.assertEquals("FOOD", hits.get(0).value().id());
    Assertions.assertEquals("Hot dogs", document.text().subSequence(
        hits.get(0).span().getStart(), hits.get(0).span().getEnd()).toString());
    Assertions.assertEquals("NYC", hits.get(1).value().id());
    Assertions.assertEquals("New York City", document.text().subSequence(
        hits.get(1).span().getStart(), hits.get(1).span().getEnd()).toString());
  }
}
