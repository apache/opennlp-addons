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
package opennlp.wordnet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.wordnet.WordNetPOS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests dependency caching and ownership of resolved document streams. */
class WnLmfResolutionContractTest {

  private static final String RESOURCE_NAME = "extensions.xml";
  private static final String VERSION = "1";
  private static final String NEXT_VERSION = "2";
  private static final String BASE_REF = "chain-0";
  private static final String EXTENSION_REF = "chain-1";
  private static final String BASE_SYNSET = "base-synset";
  private static final String LEMMA = "token";
  private static final String VERSIONED_REF = "named";
  private static final String CHAIN_PREFIX = "chain-";
  private static final String ROOT = """
      <Lexicon id="chain-0" label="Base" language="en" version="1">
        <LexicalEntry id="base-entry">
          <Lemma writtenForm="token" partOfSpeech="n"/>
          <Sense id="base-sense" synset="base-synset"/>
        </LexicalEntry>
        <Synset id="base-synset" partOfSpeech="n"/>
      </Lexicon>
      """;

  /**
   * A cached base cannot shorten the logical dependency chain.
   *
   * @param cachedLevel The base loaded by the shorter extension.
   * @param warmFirst Whether the shorter extension precedes the over-depth extension.
   */
  @ParameterizedTest
  @CsvSource({"1,true", "8,true", "15,true", "15,false"})
  void testDepthLimitDoesNotDependOnCacheOrder(int cachedLevel, boolean warmFirst) {
    final Catalog catalog = chain(16);
    final String warm = extension("warm", CHAIN_PREFIX + cachedLevel);
    final String deep = extension("deep", "chain-16");
    final CountingStream input = stream(resource(warmFirst ? warm + deep : deep + warm));
    final InvalidFormatException error = assertThrows(InvalidFormatException.class,
        () -> WnLmfReader.readResource(input, RESOURCE_NAME, catalog));
    assertTrue(error.getMessage().contains("16"), error.getMessage());
    assertTrue(error.getMessage().contains("depth"), error.getMessage());
    assertEquals(0, input.closes);
    catalog.assertClosedOnce();
  }

  /**
   * Cached extension paths must participate in cycle detection.
   *
   * @param repeatedLevel The repeated identifier in the cached path.
   * @param cachedLevel The top of the cached path.
   * @param warmFirst Whether to load the valid extension first.
   */
  @ParameterizedTest
  @CsvSource({"1,3,true", "2,3,true", "3,4,true", "1,3,false"})
  void testCycleDetectionDoesNotDependOnCacheOrder(
      int repeatedLevel, int cachedLevel, boolean warmFirst) {
    final Catalog catalog = chain(cachedLevel);
    final String warm = extension("warm", CHAIN_PREFIX + cachedLevel);
    final String cyclic = extension(CHAIN_PREFIX + repeatedLevel, CHAIN_PREFIX + cachedLevel);
    final CountingStream input = stream(resource(warmFirst ? warm + cyclic : cyclic + warm));
    final InvalidFormatException error = assertThrows(InvalidFormatException.class,
        () -> WnLmfReader.readResource(input, RESOURCE_NAME, catalog));
    assertTrue(error.getMessage().contains("cycle"), error.getMessage());
    assertTrue(error.getMessage().contains(cyclePath(repeatedLevel, cachedLevel)),
        error.getMessage());
    assertEquals(0, input.closes);
    catalog.assertClosedOnce();
  }

  /**
   * Valid boundary-depth extensions share a cache but retain separate identities.
   *
   * @param warmFirst Whether to resolve the shorter extension first.
   * @throws IOException If composition fails.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testSixteenLevelsRemainValidWithCachedBases(boolean warmFirst) throws IOException {
    final Catalog catalog = chain(15);
    final String warm = extension("warm", "chain-8");
    final String boundary = extension("boundary", "chain-15");
    final WnLmfResource result = WnLmfReader.readResource(
        stream(resource(warmFirst ? warm + boundary : boundary + warm)), RESOURCE_NAME, catalog);
    assertEquals(warmFirst ? List.of("warm", "boundary") : List.of("boundary", "warm"),
        result.lexicons().stream().map(WnLmfLexicon::id).toList());
    for (final WnLmfLexicon lexicon : result.lexicons()) {
      assertEquals(BASE_SYNSET,
          lexicon.knowledgeBase().lookup(LEMMA, WordNetPOS.NOUN).getFirst().id());
    }
    assertEquals(16, catalog.streams.size());
    assertTrue(catalog.calls.values().stream().allMatch(count -> count == 1));
    catalog.assertClosedOnce();
  }

  /**
   * Bases with one identifier and distinct versions occupy separate cache entries.
   *
   * @throws IOException If composition fails.
   */
  @Test
  void testCacheKeepsVersionsSeparate() throws IOException {
    final List<String> versions = new ArrayList<>();
    final List<CountingStream> sources = new ArrayList<>();
    final WnLmfResolver resolver = reference -> {
      assertEquals(BASE_REF, reference.ref());
      versions.add(reference.version());
      final String base = ROOT.replace("version=\"1\"", "version=\"" + reference.version() + "\"")
          .replace("writtenForm=\"token\"", "writtenForm=\"token-" + reference.version() + "\"");
      final CountingStream source = stream(resource(base));
      sources.add(source);
      return new WnLmfSource("base-" + reference.version() + ".xml", source);
    };
    final String document = resource(extension("first", VERSION, BASE_REF, VERSION)
        + extension("next", VERSION, BASE_REF, NEXT_VERSION)
        + extension("last", VERSION, BASE_REF, VERSION));
    final WnLmfResource result = WnLmfReader.readResource(stream(document), RESOURCE_NAME, resolver);
    assertEquals(List.of(VERSION, NEXT_VERSION), versions);
    assertEquals(List.of("token-1"), result.lexicons().getFirst().knowledgeBase()
        .synset(BASE_SYNSET).orElseThrow().lemmas());
    assertEquals(List.of("token-2"), result.lexicons().get(1).knowledgeBase()
        .synset(BASE_SYNSET).orElseThrow().lemmas());
    assertEquals(List.of("token-1"), result.lexicons().getLast().knowledgeBase()
        .synset(BASE_SYNSET).orElseThrow().lemmas());
    for (final CountingStream source : sources) {
      assertEquals(1, source.closes);
    }
  }

  /**
   * Reusing an identifier at another version does not form a cycle.
   *
   * @param warmFirst Whether to resolve the shared base before the same-name extension.
   * @throws IOException If composition fails.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testCycleIdentityIncludesVersion(boolean warmFirst) throws IOException {
    final AtomicInteger calls = new AtomicInteger();
    final WnLmfResolver resolver = reference -> {
      calls.incrementAndGet();
      final String document;
      if (VERSIONED_REF.equals(reference.ref())) {
        assertEquals(NEXT_VERSION, reference.version());
        document = resource(extension(VERSIONED_REF, NEXT_VERSION, BASE_REF, VERSION));
      } else {
        assertEquals(BASE_REF, reference.ref());
        assertEquals(VERSION, reference.version());
        document = resource(ROOT);
      }
      return new WnLmfSource(reference.ref(), stream(document));
    };
    final String warm = extension("warm", VERSION, VERSIONED_REF, NEXT_VERSION);
    final String named = extension(VERSIONED_REF, VERSION, VERSIONED_REF, NEXT_VERSION);
    final WnLmfResource result = WnLmfReader.readResource(
        stream(resource(warmFirst ? warm + named : named + warm)), RESOURCE_NAME, resolver);
    assertEquals(2, calls.get());
    assertEquals(warmFirst ? List.of("warm", VERSIONED_REF) : List.of(VERSIONED_REF, "warm"),
        result.lexicons().stream().map(WnLmfLexicon::id).toList());
    for (final WnLmfLexicon lexicon : result.lexicons()) {
      assertEquals(VERSION, lexicon.version());
      assertEquals(BASE_SYNSET,
          lexicon.knowledgeBase().lookup(LEMMA, WordNetPOS.NOUN).getFirst().id());
    }
  }

  /**
   * A failed read cannot retain a cached failure in another read.
   *
   * @throws IOException If the successful retry fails.
   */
  @Test
  void testNewReadCanRetryFailedResolution() throws IOException {
    final List<CountingStream> sources = new ArrayList<>();
    final WnLmfResolver resolver = reference -> {
      final CountingStream source = stream(sources.isEmpty() ? "<broken" : resource(ROOT));
      sources.add(source);
      return new WnLmfSource("retry.xml", source);
    };
    final String document = resource(extension("top", BASE_REF));
    assertThrows(InvalidFormatException.class,
        () -> WnLmfReader.readResource(stream(document), RESOURCE_NAME, resolver));
    final WnLmfResource result = WnLmfReader.readResource(stream(document), RESOURCE_NAME, resolver);
    assertEquals(BASE_SYNSET,
        result.lexicons().getFirst().knowledgeBase().lookup(LEMMA, WordNetPOS.NOUN)
            .getFirst().id());
    assertEquals(2, sources.size());
    for (final CountingStream source : sources) {
      assertEquals(1, source.closes);
    }
  }

  /**
   * Concurrent calls use one resolver with independent caches and extension identities.
   *
   * @throws Exception If a request fails or the workers do not finish.
   */
  @Test
  void testConcurrentReadsKeepCachesIndependent() throws Exception {
    final int workers = 4;
    final int requests = 32;
    final AtomicInteger calls = new AtomicInteger();
    final ConcurrentLinkedQueue<CountingStream> sources = new ConcurrentLinkedQueue<>();
    final Map<String, String> documents = Map.of(BASE_REF, resource(ROOT),
        EXTENSION_REF, resource(extension(EXTENSION_REF, BASE_REF)));
    final WnLmfResolver resolver = reference -> {
      assertEquals(VERSION, reference.version());
      calls.incrementAndGet();
      final CountingStream source = stream(documents.get(reference.ref()));
      sources.add(source);
      return new WnLmfSource(reference.ref(), source);
    };
    final var executor = Executors.newFixedThreadPool(workers);
    final CountDownLatch ready = new CountDownLatch(workers);
    final CountDownLatch start = new CountDownLatch(1);
    try {
      final List<Future<Void>> futures = new ArrayList<>();
      for (int request = 0; request < requests; request++) {
        final String id = "request-" + request;
        futures.add(executor.submit(() -> {
          ready.countDown();
          assertTrue(start.await(10, TimeUnit.SECONDS));
          final CountingStream input = stream(resource(extension("warm", EXTENSION_REF)
              + extension(id, EXTENSION_REF)));
          final WnLmfResource result = WnLmfReader.readResource(input, RESOURCE_NAME, resolver);
          assertEquals(List.of("warm", id),
              result.lexicons().stream().map(WnLmfLexicon::id).toList());
          assertEquals(BASE_SYNSET, result.lexicons().getLast().knowledgeBase()
              .lookup(LEMMA, WordNetPOS.NOUN).getFirst().id());
          assertEquals(0, input.closes);
          return null;
        }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      for (final Future<Void> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
      assertEquals(requests * 2, calls.get());
      assertEquals(requests * 2, sources.size());
      for (final CountingStream source : sources) {
        assertEquals(1, source.closes);
      }
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /** Failed reuse does not close a previously owned stream again. */
  @Test
  void testReusedResolverSourceIsNotClosedAgain() {
    final CountingStream base = stream(resource(ROOT));
    final WnLmfSource shared = new WnLmfSource("base.xml", base);
    final CountingStream input = stream(resource(
        extension("first", BASE_REF) + extension("next", "another-base")));
    assertThrows(IllegalStateException.class,
        () -> WnLmfReader.readResource(input, RESOURCE_NAME, reference -> shared));
    assertEquals(1, base.closes);
    assertEquals(0, input.closes);
  }

  /**
   * Closing an unused source makes it unavailable for resolution.
   *
   * @throws IOException If closing fails.
   */
  @Test
  void testClosedSourceCannotBeConsumed() throws IOException {
    final CountingStream base = stream(resource(ROOT));
    final WnLmfSource source = new WnLmfSource("closed.xml", base);
    source.close();
    assertThrows(IllegalStateException.class, source::consume);
    source.close();
    assertEquals(1, base.closes);
  }

  /**
   * A close failure does not trigger another close attempt on source reuse.
   *
   * @throws IOException If setup fails.
   */
  @Test
  void testCloseFailureIsNotRetried() throws IOException {
    final CountingStream base = stream(resource(ROOT));
    final IOException failure = new IOException("close failed");
    base.closeFailure = failure;
    final WnLmfSource source = new WnLmfSource("base.xml", base);
    assertSame(failure, assertThrows(IOException.class, source::close));
    source.close();
    assertThrows(IllegalStateException.class, source::consume);
    assertEquals(1, base.closes);
  }

  /** Parse errors remain primary when closing the source also fails. */
  @Test
  void testParseFailurePreservesSuppressedCloseFailure() {
    final CountingStream base = stream("<LexicalResource><Lexicon");
    final IOException closeFailure = new IOException("close failed");
    base.closeFailure = closeFailure;
    final InvalidFormatException error = assertThrows(InvalidFormatException.class,
        () -> WnLmfReader.readResource(stream(resource(extension("top", BASE_REF))),
            RESOURCE_NAME, ref -> new WnLmfSource("broken-base.xml", base)));
    assertEquals(List.of(closeFailure), List.of(error.getSuppressed()));
    assertEquals(1, base.closes);
    assertTrue(error.getMessage().contains("broken-base.xml"));
  }

  /** A close failure after successful parsing remains the primary I/O error. */
  @Test
  void testSuccessfulParsePropagatesCloseFailure() {
    final CountingStream base = stream(resource(ROOT));
    final IOException failure = new IOException("close failed");
    base.closeFailure = failure;
    final CountingStream input = stream(resource(extension("top", BASE_REF)));
    assertSame(failure, assertThrows(IOException.class,
        () -> WnLmfReader.readResource(input, RESOURCE_NAME,
            ref -> new WnLmfSource("base.xml", base))));
    assertEquals(1, base.closes);
    assertEquals(0, input.closes);
  }

  /** Read failures retain their identity and close the resolved source. */
  @Test
  void testReadFailureClosesSource() {
    final IOException failure = new IOException("read failed");
    final int[] closes = {0};
    final InputStream base = new InputStream() {
      /** {@inheritDoc} */
      @Override
      public int read() throws IOException {
        throw failure;
      }

      /** {@inheritDoc} */
      @Override
      public void close() {
        closes[0]++;
      }
    };
    final IOException error = assertThrows(IOException.class,
        () -> WnLmfReader.readResource(stream(resource(extension("top", BASE_REF))),
            RESOURCE_NAME, ref -> new WnLmfSource("unreadable.xml", base)));
    assertSame(failure, error);
    assertEquals(1, closes[0]);
  }

  /**
   * Describes the path through the repeated identifier.
   *
   * @param repeatedLevel The repeated identifier.
   * @param cachedLevel The resolved chain's highest level.
   * @return The path used in the error message.
   */
  private String cyclePath(int repeatedLevel, int cachedLevel) {
    final StringBuilder path = new StringBuilder(CHAIN_PREFIX);
    path.append(repeatedLevel).append(' ').append(VERSION);
    for (int level = cachedLevel; level >= repeatedLevel; level--) {
      path.append(" -> chain-").append(level).append(' ').append(VERSION);
    }
    return path.toString();
  }

  /**
   * Builds a catalog ending in a plain base lexicon.
   *
   * @param levels The highest extension level in the catalog.
   * @return The catalog.
   */
  private Catalog chain(int levels) {
    final Catalog catalog = new Catalog();
    catalog.documents.put(BASE_REF, resource(ROOT));
    for (int i = 1; i <= levels; i++) {
      catalog.documents.put(CHAIN_PREFIX + i, resource(extension(CHAIN_PREFIX + i, CHAIN_PREFIX + (i - 1))));
    }
    return catalog;
  }

  /**
   * Creates an empty extension with a required base version.
   *
   * @param id The extension identifier.
   * @param base The base identifier.
   * @return The XML element.
   */
  private String extension(String id, String base) {
    return extension(id, VERSION, base, VERSION);
  }

  /**
   * Creates an empty extension with explicit source and base versions.
   *
   * @param id The extension identifier.
   * @param version The extension version.
   * @param base The base identifier.
   * @param baseVersion The base version.
   * @return The XML element.
   */
  private String extension(String id, String version, String base, String baseVersion) {
    return "<LexiconExtension id=\"" + id + "\" label=\"Extension\" language=\"en\" version=\""
        + version + "\"><Extends ref=\"" + base + "\" version=\"" + baseVersion
        + "\"/></LexiconExtension>";
  }

  /**
   * Wraps lexical content in a document.
   *
   * @param content The lexicon and extension elements.
   * @return The XML document.
   */
  private String resource(String content) {
    return "<LexicalResource>" + content + "</LexicalResource>";
  }

  /**
   * Opens UTF-8 text as a tracked stream.
   *
   * @param text The document text.
   * @return The stream.
   */
  private CountingStream stream(String text) {
    return new CountingStream(text);
  }

  /** Resolves original test documents without external I/O. */
  private static final class Catalog implements WnLmfResolver {

    private final Map<String, String> documents = new HashMap<>();
    private final Map<String, Integer> calls = new HashMap<>();
    private final List<CountingStream> streams = new ArrayList<>();

    /** {@inheritDoc} */
    @Override
    public WnLmfSource resolve(WnLmfDependency reference) throws IOException {
      assertEquals(VERSION, reference.version());
      calls.merge(reference.ref(), 1, Integer::sum);
      final String document = documents.get(reference.ref());
      if (document == null) {
        throw new IOException("Missing test document " + reference.ref());
      }
      final CountingStream input = new CountingStream(document);
      streams.add(input);
      return new WnLmfSource(reference.ref() + ".xml", input);
    }

    /** Checks for a single close call per supplied stream. */
    private void assertClosedOnce() {
      assertFalse(streams.isEmpty());
      for (final CountingStream input : streams) {
        assertEquals(1, input.closes);
      }
    }
  }

  /** Records close attempts, including failures. */
  private static final class CountingStream extends ByteArrayInputStream {

    private int closes;
    private IOException closeFailure;

    /**
     * Creates a stream over a test document.
     *
     * @param text The UTF-8 document text.
     */
    private CountingStream(String text) {
      super(text.getBytes(StandardCharsets.UTF_8));
    }

    /** {@inheritDoc} */
    @Override
    public void close() throws IOException {
      closes++;
      if (closeFailure != null) {
        throw closeFailure;
      }
      super.close();
    }
  }
}
