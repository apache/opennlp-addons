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

package opennlp.geo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.geo.AttributeValue;
import opennlp.tools.geo.ContainmentChain;
import opennlp.tools.geo.GazetteerEntry;
import opennlp.tools.geo.GeoPoint;
import opennlp.tools.geo.GeoResolution;
import opennlp.tools.geo.GeocodeAnnotator;
import opennlp.tools.geo.PlaceAncestor;
import opennlp.tools.geo.PlaceHierarchy;
import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests hierarchy input validation, source identifiers and independent requests. */
class HierarchyContractTest {

  private static final String TEXT = "Paris and Nice.";
  private static final String KEY = "customer:place-id";
  private static final String PLACE_ID = "place-1";
  private static final Span PARIS = new Span(0, 5);
  private static final Span NICE = new Span(10, 14);
  private static final PlaceAncestor PARENT = new PlaceAncestor("parent-1", "Region", "region");

  /**
   * Creates an annotation with independently supplied wrapper and value spans.
   *
   * @param span The annotation span.
   * @param mention The resolution span.
   * @param id The hierarchy identifier, or null to omit the join attribute.
   * @return The location annotation.
   */
  private Annotation<GeoResolution> location(Span span, Span mention, String id) {
    final Map<String, AttributeValue> attributes = id == null ? Map.of()
        : Map.of(KEY, new AttributeValue(id, "attribute-source", ""));
    final GazetteerEntry entry = new GazetteerEntry("catalog", "record-1", "Place", List.of(),
        new GeoPoint(0, 0), "FR", List.of(), 0, GazetteerEntry.FEATURE_CLASS_CITY, attributes);
    return new Annotation<>(span, new GeoResolution(mention, entry, 0.5));
  }

  /** Existing output is rejected before calling the hierarchy. */
  @Test
  void testExistingOutputDoesNotCallHierarchy() {
    final AtomicInteger calls = new AtomicInteger();
    final HierarchyAnnotator annotator = new HierarchyAnnotator(id -> {
      calls.incrementAndGet();
      return List.of(PARENT);
    }, KEY);
    final Document input = Document.of(TEXT)
        .with(GeocodeAnnotator.LOCATIONS, List.of(location(PARIS, PARIS, PLACE_ID)))
        .with(HierarchyAnnotator.CONTAINMENT, List.of());
    assertThrows(IllegalArgumentException.class, () -> annotator.annotate(input));
    assertEquals(0, calls.get());
    assertTrue(input.get(HierarchyAnnotator.CONTAINMENT).isEmpty());
  }

  /**
   * Both spans must describe the same original-text offsets.
   *
   * @param start The incorrect resolution start.
   * @param end The incorrect resolution end.
   */
  @ParameterizedTest
  @CsvSource({"1,5", "0,4", "10,14", "30,35"})
  void testMismatchedResolutionSpanIsRejected(int start, int end) {
    final AtomicInteger calls = new AtomicInteger();
    final HierarchyAnnotator annotator = new HierarchyAnnotator(id -> {
      calls.incrementAndGet();
      return List.of(PARENT);
    }, KEY);
    final Document input = Document.of(TEXT).with(GeocodeAnnotator.LOCATIONS,
        List.of(location(PARIS, new Span(start, end), PLACE_ID)));
    assertThrows(IllegalArgumentException.class, () -> annotator.annotate(input));
    assertEquals(0, calls.get());
    assertFalse(input.layers().contains(HierarchyAnnotator.CONTAINMENT));
  }

  /**
   * Lower-ranked inputs are validated even when the first candidate has no join key.
   *
   * @param missingJoin Whether the first candidate omits the join attribute.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testInvalidLowerRankedCandidateIsRejected(boolean missingJoin) {
    final AtomicInteger calls = new AtomicInteger();
    final HierarchyAnnotator annotator = new HierarchyAnnotator(id -> {
      calls.incrementAndGet();
      return List.of(PARENT);
    }, KEY);
    final Document input = Document.of(TEXT).with(GeocodeAnnotator.LOCATIONS, List.of(
        location(PARIS, PARIS, missingJoin ? null : PLACE_ID),
        location(PARIS, NICE, "place-2")));
    assertThrows(IllegalArgumentException.class, () -> annotator.annotate(input));
    assertEquals(0, calls.get());
  }

  /** All input spans are checked before the first provider call. */
  @Test
  void testLaterInvalidMentionPreventsProviderCalls() {
    final AtomicInteger calls = new AtomicInteger();
    final HierarchyAnnotator annotator = new HierarchyAnnotator(id -> {
      calls.incrementAndGet();
      return List.of(PARENT);
    }, KEY);
    final Document input = Document.of(TEXT).with(GeocodeAnnotator.LOCATIONS, List.of(
        location(PARIS, PARIS, PLACE_ID), location(NICE, PARIS, "place-2")));
    assertThrows(IllegalArgumentException.class, () -> annotator.annotate(input));
    assertEquals(0, calls.get());
  }

  /** Null provider lists produce an argument error. */
  @Test
  void testNullProviderResultIsRejected() {
    final Document input = Document.of(TEXT).with(GeocodeAnnotator.LOCATIONS,
        List.of(location(PARIS, PARIS, PLACE_ID)));
    assertThrows(IllegalArgumentException.class,
        () -> new HierarchyAnnotator(id -> null, KEY).annotate(input));
  }

  /** Null ancestors are rejected before publishing the output layer. */
  @Test
  void testNullAncestorIsRejected() {
    final Document input = Document.of(TEXT).with(GeocodeAnnotator.LOCATIONS,
        List.of(location(PARIS, PARIS, PLACE_ID)));
    assertThrows(IllegalArgumentException.class,
        () -> new HierarchyAnnotator(id -> Arrays.asList(PARENT, null), KEY).annotate(input));
    assertFalse(input.layers().contains(HierarchyAnnotator.CONTAINMENT));
  }

  /**
   * A provider must exclude the queried place from all positions in the result.
   *
   * @param index The position of the queried place in the invalid chain.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void testQueriedPlaceCannotBeItsOwnAncestor(int index) {
    final List<PlaceAncestor> invalid = new ArrayList<>(List.of(PARENT));
    invalid.add(index, new PlaceAncestor(PLACE_ID, "Place", "locality"));
    final Document input = Document.of(TEXT).with(GeocodeAnnotator.LOCATIONS,
        List.of(location(PARIS, PARIS, PLACE_ID)));
    assertThrows(IllegalArgumentException.class,
        () -> new HierarchyAnnotator(id -> invalid, KEY).annotate(input));
  }

  /**
   * The configured attribute value is used without trimming or case conversion.
   *
   * @param joinId The opaque hierarchy identifier.
   */
  @ParameterizedTest
  @ValueSource(strings = {"0021", " customer:İ/\uD83D\uDE80 ", "CUSTOMER:Place"})
  void testOpaqueJoinIdentifierIsPreserved(String joinId) {
    final List<String> lookedUp = new ArrayList<>();
    final PlaceAncestor catalogCollision = new PlaceAncestor("record-1", "Region", "region");
    final PlaceHierarchy hierarchy = id -> {
      lookedUp.add(id);
      return List.of(catalogCollision);
    };
    final Span typed = new Span(0, 5, "location");
    final Document input = Document.of(TEXT).with(GeocodeAnnotator.LOCATIONS,
        List.of(location(typed, PARIS, joinId)));
    final Document output = new HierarchyAnnotator(hierarchy, KEY).annotate(input);
    assertEquals(List.of(joinId), lookedUp);
    assertEquals(List.of(new Annotation<>(typed, new ContainmentChain(List.of(catalogCollision)))),
        output.get(HierarchyAnnotator.CONTAINMENT));
    assertEquals(input.get(GeocodeAnnotator.LOCATIONS), output.get(GeocodeAnnotator.LOCATIONS));
  }

  /** Provider exceptions propagate without returning a partial document. */
  @Test
  void testProviderFailureIsNotSuppressed() {
    final IllegalStateException failure = new IllegalStateException("lookup failed");
    final Document input = Document.of(TEXT).with(GeocodeAnnotator.LOCATIONS, List.of(
        location(PARIS, PARIS, PLACE_ID), location(NICE, NICE, "place-2")));
    final HierarchyAnnotator annotator = new HierarchyAnnotator(id -> {
      if (PLACE_ID.equals(id)) {
        return List.of(PARENT);
      }
      throw failure;
    }, KEY);
    assertSame(failure, assertThrows(IllegalStateException.class, () -> annotator.annotate(input)));
    assertFalse(input.layers().contains(HierarchyAnnotator.CONTAINMENT));
  }

  /** Each result copies the provider's list before another lookup can change it. */
  @Test
  void testProviderListReuseDoesNotChangeEarlierChains() {
    final List<PlaceAncestor> reused = new ArrayList<>();
    final PlaceAncestor second = new PlaceAncestor("parent-2", "Other region", "region");
    final HierarchyAnnotator annotator = new HierarchyAnnotator(id -> {
      reused.clear();
      reused.add(PLACE_ID.equals(id) ? PARENT : second);
      return reused;
    }, KEY);
    final Document input = Document.of(TEXT).with(GeocodeAnnotator.LOCATIONS, List.of(
        location(PARIS, PARIS, PLACE_ID), location(NICE, NICE, "place-2")));
    final List<Annotation<ContainmentChain>> result =
        annotator.annotate(input).get(HierarchyAnnotator.CONTAINMENT);
    reused.clear();
    assertEquals(List.of(PARENT), result.getFirst().value().ancestors());
    assertEquals(List.of(second), result.getLast().value().ancestors());
    assertThrows(UnsupportedOperationException.class,
        () -> result.getFirst().value().ancestors().clear());
  }

  /** An empty first result does not select a lower-ranked candidate. */
  @Test
  void testEmptyFirstCandidateDoesNotFallBack() {
    final List<String> lookedUp = new ArrayList<>();
    final HierarchyAnnotator annotator = new HierarchyAnnotator(id -> {
      lookedUp.add(id);
      return PLACE_ID.equals(id) ? List.of() : List.of(PARENT);
    }, KEY);
    final Document input = Document.of(TEXT).with(GeocodeAnnotator.LOCATIONS, List.of(
        location(PARIS, PARIS, PLACE_ID), location(PARIS, PARIS, "place-2")));
    assertTrue(annotator.annotate(input).get(HierarchyAnnotator.CONTAINMENT).isEmpty());
    assertEquals(List.of(PLACE_ID), lookedUp);
  }

  /**
   * Candidate order determines the chain without applying a confidence threshold.
   *
   * @param confidence The first candidate's confidence.
   */
  @ParameterizedTest
  @ValueSource(doubles = {0.0, 0.1, 1.0})
  void testCandidateOrderIsNotResorted(double confidence) {
    final List<String> lookedUp = new ArrayList<>();
    final HierarchyAnnotator annotator = new HierarchyAnnotator(id -> {
      lookedUp.add(id);
      return List.of(PARENT);
    }, KEY);
    final GazetteerEntry first = location(PARIS, PARIS, PLACE_ID).value().entry();
    final GazetteerEntry lower = location(PARIS, PARIS, "place-2").value().entry();
    final Document input = Document.of(TEXT).with(GeocodeAnnotator.LOCATIONS, List.of(
        new Annotation<>(PARIS, new GeoResolution(PARIS, first, confidence)),
        new Annotation<>(PARIS, new GeoResolution(PARIS, lower, 1.0))));
    assertEquals(List.of(new Annotation<>(PARIS, new ContainmentChain(List.of(PARENT)))),
        annotator.annotate(input).get(HierarchyAnnotator.CONTAINMENT));
    assertEquals(List.of(PLACE_ID), lookedUp);
  }

  /**
   * Configured attribute names must match.
   *
   * @param key The non-matching key.
   */
  @ParameterizedTest
  @ValueSource(strings = {"CUSTOMER:place-id", " customer:place-id "})
  void testAttributeKeyMatchingIsExact(String key) {
    final AtomicInteger calls = new AtomicInteger();
    final HierarchyAnnotator annotator = new HierarchyAnnotator(id -> {
      calls.incrementAndGet();
      return List.of(PARENT);
    }, key);
    final Document input = Document.of(TEXT).with(GeocodeAnnotator.LOCATIONS,
        List.of(location(PARIS, PARIS, PLACE_ID)));
    assertTrue(annotator.annotate(input).get(HierarchyAnnotator.CONTAINMENT).isEmpty());
    assertEquals(0, calls.get());
  }

  /** Empty input provides an empty layer without querying the hierarchy. */
  @Test
  void testEmptyLocationsDoNotCallProvider() {
    final AtomicInteger calls = new AtomicInteger();
    final Document input = Document.of("").with(GeocodeAnnotator.LOCATIONS, List.of());
    final Document output = new HierarchyAnnotator(id -> {
      calls.incrementAndGet();
      return List.of(PARENT);
    }, KEY).annotate(input);
    assertTrue(output.layers().contains(HierarchyAnnotator.CONTAINMENT));
    assertTrue(output.get(HierarchyAnnotator.CONTAINMENT).isEmpty());
    assertEquals(0, calls.get());
  }

  /**
   * Shared annotators keep per-document IDs, spans and empty results independent.
   *
   * @throws Exception If a request fails or the workers do not finish.
   */
  @Test
  void testConcurrentDocumentsRemainIndependent() throws Exception {
    final int workers = 4;
    final int requests = 64;
    final AtomicInteger calls = new AtomicInteger();
    final HierarchyAnnotator annotator = new HierarchyAnnotator(id -> {
      calls.incrementAndGet();
      return List.of(new PlaceAncestor("parent-" + id, id, "region"));
    }, KEY);
    final var executor = Executors.newFixedThreadPool(workers);
    final CountDownLatch ready = new CountDownLatch(workers);
    final CountDownLatch start = new CountDownLatch(1);
    try {
      final List<Future<Void>> futures = new ArrayList<>();
      for (int i = 0; i < requests; i++) {
        final int request = i;
        futures.add(executor.submit(() -> {
          ready.countDown();
          assertTrue(start.await(10, TimeUnit.SECONDS));
          final String id = "place-" + request;
          final String text = "\uD83D\uDE80 " + id;
          final Span span = new Span(3, text.length(), "location");
          final Span untyped = new Span(3, text.length());
          final Document input = Document.of(text).with(GeocodeAnnotator.LOCATIONS, List.of(
              location(span, untyped, id), location(untyped, span, "lower-ranked")));
          final List<Annotation<ContainmentChain>> chains =
              annotator.annotate(input).get(HierarchyAnnotator.CONTAINMENT);
          assertEquals(List.of(new Annotation<>(span, new ContainmentChain(List.of(
              new PlaceAncestor("parent-" + id, id, "region"))))), chains);
          assertEquals(id, chains.getFirst().span().getCoveredText(text).toString());
          assertFalse(input.layers().contains(HierarchyAnnotator.CONTAINMENT));
          assertTrue(annotator.annotate(Document.of("").with(GeocodeAnnotator.LOCATIONS, List.of()))
              .get(HierarchyAnnotator.CONTAINMENT).isEmpty());
          return null;
        }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      for (Future<Void> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
      assertEquals(requests, calls.get());
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }
}
