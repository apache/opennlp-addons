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

package opennlp.tools.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests conditional branch selection and annotation filtering.
 */
public class PredicateAnnotatorsTest {

  private static final String LONG_TEXT_PREFIX = "Long lengthy phrase ";
  private static final String SHORT_TEXT_PREFIX = "Short a ";
  private static final String EXCLUDED_CATEGORY = "ads";

  /** Word annotations used as filter input. */
  private static final LayerKey<String> WORDS =
      LayerKey.of("test.words", String.class);
  /** Selected word annotations. */
  private static final LayerKey<String> LONG_WORDS =
      LayerKey.of("test.words.long", String.class);
  /** A second input layer used to test branch requirements. */
  private static final LayerKey<String> OTHER_WORDS =
      LayerKey.of("test.words.other", String.class);

  /** A minimal producing annotator: every whitespace-free character run is a word. */
  private static final DocumentAnnotator PRODUCER = new DocumentAnnotator() {
    /** {@inheritDoc} */
    @Override
    public Document annotate(Document document) {
      final String text = document.text().toString();
      final List<Annotation<String>> words = new ArrayList<>();
      int start = -1;
      for (int i = 0; i <= text.length(); i++) {
        final boolean boundary = i == text.length() || text.charAt(i) == ' ';
        if (!boundary && start < 0) {
          start = i;
        } else if (boundary && start >= 0) {
          words.add(new Annotation<>(new Span(start, i), text.substring(start, i)));
          start = -1;
        }
      }
      return document.with(WORDS, words);
    }

    /** {@inheritDoc} */
    @Override
    public Set<LayerKey<?>> provides() {
      return Set.of(WORDS);
    }
  };

  @Test
  void testConditionRunsTheDelegate() {
    final Document document = new ConditionalAnnotator(d -> d.text().length() > 3,
        PRODUCER).annotate(Document.of("two words"));
    assertEquals(2, document.get(WORDS).size());
  }

  @Test
  void testSkippedDelegateStillProvidesItsLayersEmpty() {
    final ConditionalAnnotator guarded =
        new ConditionalAnnotator(d -> false, PRODUCER);
    final Document document = guarded.annotate(Document.of("two words"));
    assertTrue(document.layers().contains(WORDS),
        "a skipped delegate must still provide its layer for downstream requirements");
    assertEquals(List.of(), document.get(WORDS));
    assertEquals(PRODUCER.provides(), guarded.provides());
    assertEquals(PRODUCER.requires(), guarded.requires());
  }

  @Test
  void testSkippedDelegateProvidesEveryOneOfItsLayersEmpty() {
    final DocumentAnnotator twoLayers = new DocumentAnnotator() {
      /** {@inheritDoc} */
      @Override
      public Document annotate(Document document) {
        throw new AssertionError("the delegate must not run when the condition fails");
      }

      /** {@inheritDoc} */
      @Override
      public Set<LayerKey<?>> provides() {
        return Set.of(WORDS, LONG_WORDS);
      }
    };
    final Document document = new ConditionalAnnotator(d -> false, twoLayers)
        .annotate(Document.of("two words"));
    assertTrue(document.layers().containsAll(Set.of(WORDS, LONG_WORDS)));
    assertEquals(List.of(), document.get(WORDS));
    assertEquals(List.of(), document.get(LONG_WORDS));
  }

  @Test
  void testFilterWritesSurvivorsAndKeepsTheSource() {
    final Document produced = PRODUCER.annotate(Document.of("a lengthy pair of words"));
    final FilterAnnotator<String> filter = new FilterAnnotator<>(WORDS, LONG_WORDS,
        annotation -> annotation.value().length() > 4);
    final Document filtered = filter.annotate(produced);
    assertEquals(5, filtered.get(WORDS).size(), "the source layer must be unchanged");
    assertEquals(List.of("lengthy", "words"),
        filtered.get(LONG_WORDS).stream().map(Annotation::value).toList());
    assertEquals(Set.of(WORDS), filter.requires());
    assertEquals(Set.of(LONG_WORDS), filter.provides());
  }

  @Test
  void testFilterOnAnEmptySourceProvidesAnEmptyTarget() {
    final Document produced = PRODUCER.annotate(Document.of(""));
    final Document filtered = new FilterAnnotator<>(WORDS, LONG_WORDS, a -> true)
        .annotate(produced);
    assertTrue(filtered.layers().contains(LONG_WORDS));
    assertEquals(List.of(), filtered.get(LONG_WORDS));
  }

  @Test
  void testFilterFailsLoudWhenTheSourceLayerIsAbsent() {
    final FilterAnnotator<String> filter =
        new FilterAnnotator<>(WORDS, LONG_WORDS, a -> true);
    assertThrows(IllegalArgumentException.class,
        () -> filter.annotate(Document.of("no words layer")));
  }

  @Test
  void testSkippedConditionalStillRequiresTheDelegateLayers() {
    final DocumentAnnotator guarded = new ConditionalAnnotator(d -> false,
        new FilterAnnotator<>(WORDS, LONG_WORDS, a -> true));
    assertThrows(IllegalArgumentException.class,
        () -> guarded.annotate(Document.of("no words layer")),
        "a skipped delegate's required layers must still be present");
  }

  @Test
  void testSkipPathStillCollidesWithAPreExistingProvidedLayer() {
    final Document withWords = PRODUCER.annotate(Document.of("two words"));
    final ConditionalAnnotator guarded = new ConditionalAnnotator(d -> false, PRODUCER);
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> guarded.annotate(withWords),
        "adding the skipped delegate's layer empty must hit the duplicate-layer rejection");
    assertEquals("layer is already present: test.words<String>", e.getMessage());
  }

  @Test
  void testFilterRejectingEveryAnnotationProvidesAnEmptyTarget() {
    final Document produced = PRODUCER.annotate(Document.of("a lengthy pair of words"));
    assertEquals(5, produced.get(WORDS).size());
    final Document filtered = new FilterAnnotator<>(WORDS, LONG_WORDS, a -> false)
        .annotate(produced);
    assertTrue(filtered.layers().contains(LONG_WORDS),
        "an all-rejecting filter must still provide its target layer");
    assertEquals(List.of(), filtered.get(LONG_WORDS));
  }

  @Test
  void testConditionRunsADelegateWithRequirementsWhenItsLayerIsPresent() {
    final DocumentAnnotator guarded = new ConditionalAnnotator(d -> true,
        new FilterAnnotator<>(WORDS, LONG_WORDS, a -> a.value().length() > 4));
    final Document document =
        guarded.annotate(PRODUCER.annotate(Document.of("a lengthy pair of words")));
    assertTrue(document.layers().contains(LONG_WORDS), "the delegate must have run");
    assertEquals(List.of("lengthy", "words"),
        document.get(LONG_WORDS).stream().map(Annotation::value).toList());
  }

  @Test
  void testConditionIsTestedExactlyOnceWithTheAnnotatedDocument() {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<Document> seen = new AtomicReference<>();
    final Document document = Document.of("two words");
    new ConditionalAnnotator(d -> {
      calls.incrementAndGet();
      seen.set(d);
      return true;
    }, PRODUCER).annotate(document);
    assertEquals(1, calls.get(), "the condition must be tested once");
    assertSame(document, seen.get(),
        "the condition must see the same document instance passed to annotate");
  }

  @Test
  void testFilterKeepsTheSurvivingAnnotationInstances() {
    final Document produced = PRODUCER.annotate(Document.of("a lengthy pair of words"));
    final List<Annotation<String>> source = produced.get(WORDS);
    final Document filtered =
        new FilterAnnotator<>(WORDS, LONG_WORDS, a -> a.value().length() > 4)
            .annotate(produced);
    final List<Annotation<String>> matches = filtered.get(LONG_WORDS);
    final List<Annotation<String>> expected =
        source.stream().filter(a -> a.value().length() > 4).toList();
    assertEquals(expected.size(), matches.size());
    for (int i = 0; i < expected.size(); i++) {
      assertSame(expected.get(i), matches.get(i),
          "a match must be the same instance as its source annotation");
    }
  }

  @Test
  void testFilterCanInspectTheOriginalDocumentText() {
    final Document document = Document.of("Alice met bob").with(WORDS, List.of(
        new Annotation<>(new Span(0, 5), "alice"),
        new Annotation<>(new Span(6, 9), "met"),
        new Annotation<>(new Span(10, 13), "bob")));
    final Document filtered = new FilterAnnotator<>(WORDS, LONG_WORDS,
        (source, annotation) -> Character.isUpperCase(
            source.text().charAt(annotation.span().getStart())))
        .annotate(document);

    assertEquals(List.of("alice"),
        filtered.get(LONG_WORDS).stream().map(Annotation::value).toList());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testConditionalRunsTheSelectedBranch(boolean condition) {
    final Document document = PRODUCER.annotate(Document.of("a lengthy pair of words"));
    final DocumentAnnotator longWords =
        new FilterAnnotator<>(WORDS, LONG_WORDS, a -> a.value().length() > 4);
    final DocumentAnnotator shortWords =
        new FilterAnnotator<>(WORDS, LONG_WORDS, a -> a.value().length() <= 4);

    final Document result = new ConditionalAnnotator(d -> condition, longWords, shortWords)
        .annotate(document);

    assertEquals(condition ? List.of("lengthy", "words") : List.of("a", "pair", "of"),
        result.get(LONG_WORDS).stream().map(Annotation::value).toList());
  }

  @Test
  void testConditionalDeclaresRequirementsFromBothBranches() {
    final DocumentAnnotator words =
        new FilterAnnotator<>(WORDS, LONG_WORDS, a -> true);
    final DocumentAnnotator otherWords =
        new FilterAnnotator<>(OTHER_WORDS, LONG_WORDS, a -> true);
    final ConditionalAnnotator conditional =
        new ConditionalAnnotator(d -> true, words, otherWords);

    assertEquals(Set.of(WORDS, OTHER_WORDS), conditional.requires());
    final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> conditional.annotate(PRODUCER.annotate(Document.of("two words"))));
    assertEquals("document lacks the required layer test.words.other<String>",
        error.getMessage());
  }

  @Test
  void testConditionalRejectsBranchesWithDifferentOutputs() {
    final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new ConditionalAnnotator(d -> true, PRODUCER,
            new FilterAnnotator<>(WORDS, LONG_WORDS, a -> true)));
    assertEquals("branches must provide the same layers", error.getMessage());
  }

  /**
   * @return Invalid constructor and annotation calls with their expected messages.
   */
  private static Stream<Arguments> contractViolations() {
    return Stream.of(
        Arguments.of("null condition", "condition must not be null",
            (Executable) () -> new ConditionalAnnotator(null, PRODUCER)),
        Arguments.of("null delegate", "delegate must not be null",
            (Executable) () -> new ConditionalAnnotator(d -> true, null)),
        Arguments.of("null true branch", "whenTrue must not be null",
            (Executable) () -> new ConditionalAnnotator(d -> true, null, PRODUCER)),
        Arguments.of("null false branch", "whenFalse must not be null",
            (Executable) () -> new ConditionalAnnotator(d -> true, PRODUCER, null)),
        Arguments.of("null document passed to the conditional", "document must not be null",
            (Executable) () -> new ConditionalAnnotator(d -> true, PRODUCER).annotate(null)),
        Arguments.of("null source layer", "source must not be null",
            (Executable) () -> new FilterAnnotator<>(null, LONG_WORDS, a -> true)),
        Arguments.of("null target layer", "target must not be null",
            (Executable) () -> new FilterAnnotator<>(WORDS, null, a -> true)),
        Arguments.of("target equal to source", "target must differ from source",
            (Executable) () -> new FilterAnnotator<>(WORDS, WORDS, a -> true)),
        Arguments.of("null predicate", "keep must not be null",
            (Executable) () -> new FilterAnnotator<>(WORDS, LONG_WORDS,
                (Predicate<Annotation<String>>) null)),
        Arguments.of("null document-aware predicate", "keep must not be null",
            (Executable) () -> new FilterAnnotator<>(WORDS, LONG_WORDS,
                (BiPredicate<Document, Annotation<String>>) null)),
        Arguments.of("null document passed to the filter", "document must not be null",
            (Executable) () -> new FilterAnnotator<>(WORDS, LONG_WORDS, a -> true).annotate(null)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("contractViolations")
  void testRejectsContractViolations(String violation, String expectedMessage, Executable call) {
    final IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, call, violation);
    assertEquals(expectedMessage, error.getMessage());
  }

  /**
   * Filters document-level values without requiring positional spans.
   *
   * @param documentAware Whether the filter function receives the document.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testDocumentScopedFilter(boolean documentAware) {
    final LayerKey<String> categories = LayerKey.document("test.categories", String.class);
    final LayerKey<String> selected = LayerKey.document("test.categories.selected", String.class);
    final List<Annotation<String>> source = List.of(Annotation.of("news"),
        Annotation.of("sports"), Annotation.of(EXCLUDED_CATEGORY));
    final Document document = Document.of("Sports results").with(categories, source);
    final Predicate<Annotation<String>> keep = a -> !a.value().equals(EXCLUDED_CATEGORY);
    final FilterAnnotator<String> filter = documentAware
        ? new FilterAnnotator<>(categories, selected,
            (input, a) -> input.get(categories).contains(a) && keep.test(a))
        : new FilterAnnotator<>(categories, selected, keep);
    final Document result = filter.annotate(document);

    assertEquals(source.subList(0, 2), result.get(selected));
    assertEquals(source, result.get(categories));
    assertSame(source.getFirst(), result.get(selected).getFirst());
    assertSame(source.get(1), result.get(selected).get(1));
  }

  /** {@return filter constructors combined with implicit and explicit false branches} */
  private static Stream<Arguments> wrapperModes() {
    return Stream.of(false, true).flatMap(aware -> Stream.of(false, true)
        .map(explicit -> Arguments.of(aware, explicit)));
  }

  /**
   * Concurrent pipelines retain each document's source text, annotations and selected output.
   *
   * @param documentAware Whether filter predicates receive the document.
   * @param explicitFalse Whether the conditional has an explicit false branch.
   * @throws Exception If a pipeline task fails.
   */
  @ParameterizedTest
  @MethodSource("wrapperModes")
  void testConcurrentPipelines(boolean documentAware, boolean explicitFalse) throws Exception {
    final AtomicInteger conditionCalls = new AtomicInteger();
    final Predicate<Document> condition = document -> {
      conditionCalls.incrementAndGet();
      return document.text().charAt(0) == 'L';
    };
    final DocumentAnnotator longWords = wordFilter(documentAware, true);
    final ConditionalAnnotator conditional = explicitFalse
        ? new ConditionalAnnotator(condition, longWords, wordFilter(documentAware, false))
        : new ConditionalAnnotator(condition, longWords);
    final DocumentAnalyzer analyzer = DocumentAnalyzer.builder().add(PRODUCER)
        .add(conditional).build();
    final List<Callable<Document>> tasks = new ArrayList<>();
    final int taskCount = 200;
    for (int i = 0; i < taskCount; i++) {
      final String text = documentText(i);
      tasks.add(() -> analyzer.analyze(text));
    }

    try (var executor = Executors.newFixedThreadPool(4)) {
      final var results = executor.invokeAll(tasks);
      for (int i = 0; i < results.size(); i++) {
        final Document document = results.get(i).get();
        final boolean longDocument = i % 2 == 0;
        final String text = documentText(i);
        final List<String> expected = longDocument ? List.of("Long", "lengthy", "phrase")
            : explicitFalse ? List.of("a", Integer.toString(i)) : List.of();
        assertEquals(text, document.text().toString());
        assertEquals(expected, document.get(LONG_WORDS).stream().map(Annotation::value).toList());
        assertTrue(document.layers().contains(LONG_WORDS));
        assertEquals(longDocument ? 4 : 3, document.get(WORDS).size());
        for (Annotation<String> annotation : document.get(LONG_WORDS)) {
          assertEquals(annotation.value(), annotation.span().getCoveredText(document.text()).toString());
          assertTrue(document.get(WORDS).stream().anyMatch(source -> source == annotation));
        }
      }
    }
    assertEquals(taskCount, conditionCalls.get());
  }

  /**
   * Creates a word-length filter using the selected constructor.
   *
   * @param documentAware Whether the filter function also checks the original text.
   * @param longWords Whether to select long words instead of short words.
   * @return The filter.
   */
  private FilterAnnotator<String> wordFilter(boolean documentAware, boolean longWords) {
    final Predicate<Annotation<String>> keep = longWords
        ? a -> a.value().length() >= 4 : a -> a.value().length() < 4;
    return documentAware ? new FilterAnnotator<>(WORDS, LONG_WORDS,
        (document, a) -> a.span().getCoveredText(document.text()).toString().equals(a.value())
            && keep.test(a)) : new FilterAnnotator<>(WORDS, LONG_WORDS, keep);
  }

  /**
   * Constructs the document text for a concurrent request.
   *
   * @param index The request index.
   * @return The text selecting the corresponding annotation branch.
   */
  private String documentText(int index) {
    return (index % 2 == 0 ? LONG_TEXT_PREFIX : SHORT_TEXT_PREFIX) + index;
  }
}
