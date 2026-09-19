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

package opennlp.tools.coref;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;
import opennlp.tools.namefind.NameFinderAnnotator;
import opennlp.tools.namefind.TokenNameFinder;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

/**
 * Demonstrates the {@link CorefAnnotator} as the last step of a {@link DocumentAnalyzer}
 * pipeline, with deterministic upstream annotations. The text repeats a person name and
 * refers back with a gendered and a neutral pronoun. Name containment links the bare
 * surname to the full name, and pronoun resolution attaches {@code She} to the person
 * chain and {@code its} to the organization chain.
 */
public class CorefPipelineExampleTest {

  /** The example text: two entities, a repeated surname, and two referring pronouns. */
  private static final String TEXT =
      "Ada Lovelace advised Orion Labs. Lovelace saw its potential. She was right.";

  /** The lowercased token forms the test tagger labels with the {@code PRON} tag. */
  private static final Set<String> PRONOUN_FORMS = Set.of("she", "her", "it", "its", "they");

  /**
   * Provides {@link Layers#SENTENCES} by closing a sentence at every period and opening
   * the next one at the following non-whitespace character.
   */
  private static final class PeriodSentenceAnnotator implements DocumentAnnotator {

    /**
     * Splits the document text into period-terminated sentences.
     *
     * @param document The document to annotate. Must not be {@code null}.
     * @return A new {@link Document} carrying the sentence layer. Never {@code null}.
     * @throws IllegalArgumentException Thrown if {@code document} is {@code null}.
     */
    @Override
    public Document annotate(Document document) {
      if (document == null) {
        throw new IllegalArgumentException("document must not be null");
      }
      final CharSequence text = document.text();
      final List<Annotation<String>> sentences = new ArrayList<>();
      int start = 0;
      for (int i = 0; i < text.length(); i++) {
        if (text.charAt(i) == '.') {
          sentences.add(new Annotation<>(new Span(start, i + 1),
              text.subSequence(start, i + 1).toString()));
          start = i + 1;
          while (start < text.length() && StringUtil.isWhitespace(text.charAt(start))) {
            start++;
          }
          i = start - 1;
        }
      }
      return document.with(Layers.SENTENCES, sentences);
    }

    @Override
    public Set<LayerKey<?>> provides() {
      return Set.of(Layers.SENTENCES);
    }
  }

  /**
   * Provides {@link Layers#TOKENS} by splitting on whitespace and {@link Layers#POS_TAGS}
   * from a dictionary: forms listed in {@link #PRONOUN_FORMS} are tagged {@code PRON},
   * everything else {@code X}. That is all the coreference annotator needs to spot
   * pronoun tokens.
   */
  private static final class WhitespaceTokenTagAnnotator implements DocumentAnnotator {

    /**
     * Tokenizes the document text and tags every token.
     *
     * @param document The document to annotate. Must not be {@code null}.
     * @return A new {@link Document} carrying aligned token and POS tag layers. Never
     *         {@code null}.
     * @throws IllegalArgumentException Thrown if {@code document} is {@code null}.
     */
    @Override
    public Document annotate(Document document) {
      if (document == null) {
        throw new IllegalArgumentException("document must not be null");
      }
      final CharSequence text = document.text();
      final List<Annotation<String>> tokens = new ArrayList<>();
      final List<Annotation<String>> tags = new ArrayList<>();
      int start = -1;
      for (int i = 0; i <= text.length(); i++) {
        final boolean boundary = i == text.length() || StringUtil.isWhitespace(text.charAt(i));
        if (boundary && start >= 0) {
          final String form = text.subSequence(start, i).toString();
          tokens.add(new Annotation<>(new Span(start, i), form));
          tags.add(new Annotation<>(new Span(start, i),
              PRONOUN_FORMS.contains(StringUtil.toLowerCase(form)) ? "PRON" : "X"));
          start = -1;
        } else if (!boundary && start < 0) {
          start = i;
        }
      }
      return document.with(Layers.TOKENS, tokens).with(Layers.POS_TAGS, tags);
    }

    @Override
    public Set<LayerKey<?>> provides() {
      return Set.of(Layers.TOKENS, Layers.POS_TAGS);
    }
  }

  /**
   * Provides {@link Layers#ENTITIES} from a fixed list.
   */
  private static final class FixedEntityAnnotator implements DocumentAnnotator {

    private final List<Annotation<String>> entities;

    /**
     * Initializes the annotator with the entity annotations to provide.
     *
     * @param entities The entity annotations. Must not be {@code null}.
     * @throws IllegalArgumentException Thrown if {@code entities} is {@code null}.
     */
    FixedEntityAnnotator(List<Annotation<String>> entities) {
      if (entities == null) {
        throw new IllegalArgumentException("entities must not be null");
      }
      this.entities = List.copyOf(entities);
    }

    /**
     * Adds the fixed entity layer to the document.
     *
     * @param document The document to annotate. Must not be {@code null}.
     * @return A new {@link Document} carrying the entity layer. Never {@code null}.
     * @throws IllegalArgumentException Thrown if {@code document} is {@code null}.
     */
    @Override
    public Document annotate(Document document) {
      if (document == null) {
        throw new IllegalArgumentException("document must not be null");
      }
      return document.with(Layers.ENTITIES, entities);
    }

    @Override
    public Set<LayerKey<?>> provides() {
      return Set.of(Layers.ENTITIES);
    }
  }

  /**
   * A deterministic {@link TokenNameFinder} for a single entity type. It locates fixed
   * token sequences and returns the untyped spans reported by such a model.
   */
  private static final class UntypedLexiconNameFinder implements TokenNameFinder {

    private final List<String[]> patterns;

    /**
     * Initializes the finder with the token sequences it reports.
     *
     * @param patterns The token sequences to locate, tried in the given order at every
     *                 position, so longer sequences should come first. Must not be
     *                 {@code null}.
     * @throws IllegalArgumentException Thrown if {@code patterns} is {@code null}.
     */
    UntypedLexiconNameFinder(List<String[]> patterns) {
      if (patterns == null) {
        throw new IllegalArgumentException("patterns must not be null");
      }
      this.patterns = List.copyOf(patterns);
    }

    /**
     * Scans the tokens left to right and reports every non-overlapping pattern match as
     * an untyped span.
     *
     * @param tokens The tokens to scan.
     * @return The untyped spans of the matches. Never {@code null}.
     */
    @Override
    public Span[] find(String[] tokens) {
      final List<Span> spans = new ArrayList<>();
      int i = 0;
      while (i < tokens.length) {
        final int matched = matchedLengthAt(tokens, i);
        if (matched > 0) {
          spans.add(new Span(i, i + matched));
          i += matched;
        } else {
          i++;
        }
      }
      return spans.toArray(new Span[0]);
    }

    /**
     * Tries every pattern at one position.
     *
     * @param tokens The tokens to scan.
     * @param at The position the patterns are tried at.
     * @return The length of the first matching pattern, or zero if none matches.
     */
    private int matchedLengthAt(String[] tokens, int at) {
      for (final String[] pattern : patterns) {
        if (at + pattern.length > tokens.length) {
          continue;
        }
        boolean all = true;
        for (int k = 0; k < pattern.length; k++) {
          if (!pattern[k].equals(tokens[at + k])) {
            all = false;
            break;
          }
        }
        if (all) {
          return pattern.length;
        }
      }
      return 0;
    }

    /**
     * Does nothing, since the finder holds no adaptive data.
     */
    @Override
    public void clearAdaptiveData() {
    }
  }

  /**
   * Builds an entity annotation over the first occurrence of a surface form at or after
   * the given offset.
   *
   * @param text The document text to search in.
   * @param surface The exact surface form of the entity.
   * @param from The offset the search starts at.
   * @param type The entity type label.
   * @return The located entity annotation. Never {@code null}.
   * @throws IllegalStateException Thrown if the surface form does not occur at or after
   *         {@code from}.
   */
  private Annotation<String> entity(String text, String surface, int from, String type) {
    final int start = text.indexOf(surface, from);
    if (start < 0) {
      throw new IllegalStateException("surface form not found: " + surface);
    }
    return new Annotation<>(new Span(start, start + surface.length()), type);
  }

  /**
   * Reads the text one annotation covers.
   *
   * @param document The annotated document.
   * @param mention The annotation to read.
   * @return The covered text. Never {@code null}.
   */
  private String surface(Document document, Annotation<CorefMention> mention) {
    return document.text().subSequence(
        mention.span().getStart(), mention.span().getEnd()).toString();
  }

  /**
   * Reads the surface text covered by every chain annotation, in layer order.
   *
   * @param document The annotated document.
   * @param chains The chains layer of that document.
   * @return The covered texts in layer order. Never {@code null}.
   */
  private List<String> surfaces(Document document, List<Annotation<CorefMention>> chains) {
    final List<String> surfaces = new ArrayList<>(chains.size());
    for (final Annotation<CorefMention> mention : chains) {
      surfaces.add(surface(document, mention));
    }
    return surfaces;
  }

  /**
   * Collects the surface texts of the mentions in one chain, in text order.
   *
   * @param document The annotated document.
   * @param chains The chains layer of that document.
   * @param chain The chain identifier to collect.
   * @return The covered texts of that chain's mentions. Never {@code null}.
   */
  private List<String> chainSurfaces(Document document,
      List<Annotation<CorefMention>> chains, int chain) {
    final List<String> surfaces = new ArrayList<>();
    for (final Annotation<CorefMention> mention : chains) {
      if (mention.value().chain() == chain) {
        surfaces.add(surface(document, mention));
      }
    }
    return surfaces;
  }

  /**
   * Runs the full pipeline over the example text and asserts the exact clustering: one
   * chain holding the full name, the bare surname, and the gendered pronoun, and a
   * second chain holding the organization and the neutral pronoun.
   */
  @Test
  void testPipelineClustersRecurringNameAndPronouns() {
    final Document document = DocumentAnalyzer.builder()
        .add(new PeriodSentenceAnnotator())
        .add(new WhitespaceTokenTagAnnotator())
        .add(new FixedEntityAnnotator(List.of(
            entity(TEXT, "Ada Lovelace", 0, "person"),
            entity(TEXT, "Orion Labs", 0, "organization"),
            entity(TEXT, "Lovelace", 13, "person"))))
        .add(new CorefAnnotator())
        .build()
        .analyze(TEXT);

    final List<Annotation<CorefMention>> chains = document.get(CorefAnnotator.CHAINS);
    Assertions.assertEquals(5, chains.size());
    Assertions.assertEquals(List.of("Ada Lovelace", "Orion Labs", "Lovelace", "its", "She"),
        surfaces(document, chains));
    Assertions.assertEquals(List.of(
            new Span(0, 12), new Span(21, 31), new Span(33, 41), new Span(46, 49),
            new Span(61, 64)),
        chains.stream().map(Annotation::span).toList());
    Assertions.assertArrayEquals(new int[] {0, 1, 0, 1, 0},
        chains.stream().mapToInt(a -> a.value().chain()).toArray());
    Assertions.assertEquals(List.of(CorefMention.KIND_ENTITY, CorefMention.KIND_ENTITY,
            CorefMention.KIND_ENTITY, CorefMention.KIND_PRONOUN, CorefMention.KIND_PRONOUN),
        chains.stream().map(a -> a.value().kind()).toList());
    Assertions.assertArrayEquals(
        new int[] {0, 1, 2, CorefMention.NO_ENTITY, CorefMention.NO_ENTITY},
        chains.stream().mapToInt(a -> a.value().entity()).toArray());
    Assertions.assertEquals(List.of("Ada Lovelace", "Lovelace", "She"),
        chainSurfaces(document, chains, 0));
    Assertions.assertEquals(List.of("Orion Labs", "its"),
        chainSurfaces(document, chains, 1));
  }

  /** Resolves a feminine pronoun when the name finder supplies no entity types. */
  @Test
  void testUntypedEntitiesResolvePronouns() {
    final String text =
        "Ada Lovelace advised Orion Labs daily. Later Lovelace saw its potential. She was right.";
    final Document document = DocumentAnalyzer.builder()
        .add(new PeriodSentenceAnnotator())
        .add(new WhitespaceTokenTagAnnotator())
        .add(new NameFinderAnnotator(new UntypedLexiconNameFinder(List.of(
            new String[] {"Ada", "Lovelace"},
            new String[] {"Orion", "Labs"},
            new String[] {"Lovelace"}))))
        .add(new CorefAnnotator())
        .build()
        .analyze(text);

    final List<Annotation<CorefMention>> chains = document.get(CorefAnnotator.CHAINS);
    Assertions.assertEquals(5, chains.size());
    Assertions.assertEquals(List.of("Ada Lovelace", "Orion Labs", "Lovelace", "its", "She"),
        surfaces(document, chains));
    Assertions.assertArrayEquals(new int[] {0, 1, 0, 2, 0},
        chains.stream().mapToInt(a -> a.value().chain()).toArray());
    Assertions.assertEquals(List.of("Ada Lovelace", "Lovelace", "She"),
        chainSurfaces(document, chains, 0));
    Assertions.assertEquals(List.of("Orion Labs"),
        chainSurfaces(document, chains, 1));
    Assertions.assertEquals(List.of("its"), chainSurfaces(document, chains, 2));
  }

  /**
   * Asserts the declared layer contract: the annotator requires the four upstream layers
   * and provides only the chains layer, and a pipeline missing the upstream layers is
   * rejected when it is assembled.
   */
  @Test
  void testDeclaredLayerContract() {
    final CorefAnnotator annotator = new CorefAnnotator();
    Assertions.assertEquals(
        Set.of(Layers.SENTENCES, Layers.TOKENS, Layers.POS_TAGS, Layers.ENTITIES),
        annotator.requires());
    Assertions.assertEquals(Set.of(CorefAnnotator.CHAINS), annotator.provides());

    final DocumentAnalyzer.Builder incomplete = DocumentAnalyzer.builder().add(annotator);
    Assertions.assertThrows(IllegalArgumentException.class, incomplete::build);
  }
}
