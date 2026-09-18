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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import opennlp.tools.stemmer.snowball.SnowballStemmer;
import opennlp.tools.stemmer.snowball.SnowballStemmerFactory;
import opennlp.tools.util.normalizer.EnglishContractionCharSequenceNormalizer;
import opennlp.tools.util.normalizer.GermanUmlautCharSequenceNormalizer;
import opennlp.tools.util.normalizer.TermAnalyzer;

/**
 * JMH throughput for the three glossary matching paths: the exact character
 * automaton, the offset-aware orthographic fold, and the token-normalized
 * {@link TermAnalyzingGlossaryMatcher}. Two inputs per path: a short mixed text
 * and a 4 KiB document. The exact and token-normalized paths also run
 * the 4 KiB input against a 2,007-entry glossary.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AhoCorasickGlossaryMatcherBenchmark {

  private static final String SHORT_TEXT =
      ("Die Strasse und die Stra\u00DFe liegen an New York "
      + "City und Manhattan. Machine Learning meets maximum entropy model. ")
      .repeat(20);

  /** A paragraph repeated and truncated to 4,096 ASCII characters. */
  private static final String FOUR_KIB_TEXT = buildFourKibText();

  /** A 4 KiB document with no registered glossary term. */
  private static final String NO_HIT_FOUR_KIB_TEXT =
      "Alpha beta gamma delta epsilon zeta theta lambda. ".repeat(100).substring(0, 4096);

  /** A 4 KiB document containing 1,024 separate exact hits. */
  private static final String HIT_HEAVY_FOUR_KIB_TEXT = "cat ".repeat(1024);

  /** A contraction-rich document used by the aligned English expansion path. */
  private static final String CONTRACTION_TEXT =
      "We can't leave because they won't stop and we're not ready. ".repeat(70);

  /**
   * Builds the 4 KiB matching input.
   *
   * @return The fixed-length document text.
   */
  private static String buildFourKibText() {
    final String paragraph = "The vendors sold hot dogs and soft drinks near the "
        + "hot dog stands along the avenue, while machine learning engineers from "
        + "New York City argued about maximum entropy models over lunch. Down the "
        + "Strasse, a perceptron model classified the crowd, and the training data "
        + "kept growing as more documents arrived from Manhattan. ";
    final StringBuilder text = new StringBuilder(4200);
    while (text.length() < 4096) {
      text.append(paragraph);
    }
    return text.substring(0, 4096);
  }

  /**
   * Creates the terms used by the small-glossary measurements.
   *
   * @return Seven glossary entries.
   */
  private static List<GlossaryEntry> smallGlossary() {
    final List<GlossaryEntry> glossary = new ArrayList<>();
    glossary.add(new GlossaryEntry("ST", "strasse"));
    glossary.add(new GlossaryEntry("NYC", "New York City"));
    glossary.add(new GlossaryEntry("MN", "Manhattan"));
    glossary.add(new GlossaryEntry("ML", "machine learning"));
    glossary.add(new GlossaryEntry("ME", "maximum entropy model"));
    glossary.add(new GlossaryEntry("FOOD", "hot dog"));
    glossary.add(new GlossaryEntry("DRINK", "soft drink"));
    return glossary;
  }

  /**
   * Adds generated terms to the small glossary.
   *
   * @return The 2,007-entry glossary.
   */
  private static List<GlossaryEntry> wideGlossary() {
    final List<GlossaryEntry> glossary = smallGlossary();
    for (int i = 0; i < 1000; i++) {
      glossary.add(new GlossaryEntry("SYN-" + i, "synthetic term " + i));
      glossary.add(new GlossaryEntry("PROD-" + i, "product line " + i + " release"));
    }
    return glossary;
  }

  /** Prebuilt matchers shared by benchmark invocations. */
  @State(Scope.Benchmark)
  public static class MatcherState {
    AhoCorasickGlossaryMatcher exact;
    AhoCorasickGlossaryMatcher exactWide;
    AhoCorasickGlossaryMatcher exactHitHeavy;
    AhoCorasickGlossaryMatcher offsetAware;
    AhoCorasickGlossaryMatcher contractionAware;
    TermAnalyzingGlossaryMatcher termAnalyzing;
    TermAnalyzingGlossaryMatcher termAnalyzingWide;

    /** Builds the matchers before timing starts. */
    @Setup(Level.Trial)
    public void setUp() {
      final List<GlossaryEntry> small = smallGlossary();
      final List<GlossaryEntry> wide = wideGlossary();
      final TermAnalyzer analyzer = TermAnalyzer.builder()
          .caseFold()
          .stem(new SnowballStemmerFactory(SnowballStemmer.ALGORITHM.ENGLISH))
          .build();
      exact = new AhoCorasickGlossaryMatcher(small, true);
      exactWide = new AhoCorasickGlossaryMatcher(wide, true);
      exactHitHeavy = new AhoCorasickGlossaryMatcher(
          List.of(new GlossaryEntry("CAT", "cat")), false);
      offsetAware = new AhoCorasickGlossaryMatcher(small, true,
          GermanUmlautCharSequenceNormalizer.getInstance());
      contractionAware = new AhoCorasickGlossaryMatcher(List.of(
          new GlossaryEntry("CANNOT", "can not"),
          new GlossaryEntry("WILL_NOT", "will not"),
          new GlossaryEntry("WE_ARE", "we are")), true,
          EnglishContractionCharSequenceNormalizer.getInstance());
      termAnalyzing = new TermAnalyzingGlossaryMatcher(small, analyzer);
      termAnalyzingWide = new TermAnalyzingGlossaryMatcher(wide, analyzer);
    }
  }

  /**
   * Matches the shorter input without an additional normalizer.
   *
   * @param state The prebuilt matchers.
   * @param blackhole The result consumer.
   */
  @Benchmark
  public void exactIgnoreCaseShortText(MatcherState state, Blackhole blackhole) {
    blackhole.consume(state.exact.match(SHORT_TEXT));
  }

  /**
   * Matches a 4 KiB input without an additional normalizer.
   *
   * @param state The prebuilt matchers.
   * @param blackhole The result consumer.
   */
  @Benchmark
  public void exactIgnoreCaseFourKib(MatcherState state, Blackhole blackhole) {
    blackhole.consume(state.exact.match(FOUR_KIB_TEXT));
  }

  /**
   * Matches the larger glossary against the 4 KiB input.
   *
   * @param state The prebuilt matchers.
   * @param blackhole The result consumer.
   */
  @Benchmark
  public void exactWideGlossaryFourKib(MatcherState state, Blackhole blackhole) {
    blackhole.consume(state.exactWide.match(FOUR_KIB_TEXT));
  }

  /**
   * Measures input with no candidate matches.
   *
   * @param state The prebuilt matchers.
   * @param blackhole The result consumer.
   */
  @Benchmark
  public void exactNoHitFourKib(MatcherState state, Blackhole blackhole) {
    blackhole.consume(state.exact.match(NO_HIT_FOUR_KIB_TEXT));
  }

  /**
   * Measures boundary checks and overlap handling for 1,024 exact hits.
   *
   * @param state The prebuilt matchers.
   * @param blackhole The result consumer.
   */
  @Benchmark
  public void exactHitHeavyFourKib(MatcherState state, Blackhole blackhole) {
    blackhole.consume(state.exactHitHeavy.match(HIT_HEAVY_FOUR_KIB_TEXT));
  }

  /**
   * Matches the shorter input after German umlaut normalization.
   *
   * @param state The prebuilt matchers.
   * @param blackhole The result consumer.
   */
  @Benchmark
  public void offsetAwareGermanUmlautShortText(MatcherState state, Blackhole blackhole) {
    blackhole.consume(state.offsetAware.match(SHORT_TEXT));
  }

  /**
   * Matches the 4 KiB input after German umlaut normalization.
   *
   * @param state The prebuilt matchers.
   * @param blackhole The result consumer.
   */
  @Benchmark
  public void offsetAwareGermanUmlautFourKib(MatcherState state, Blackhole blackhole) {
    blackhole.consume(state.offsetAware.match(FOUR_KIB_TEXT));
  }

  /**
   * Measures contraction expansion, matching, and source-span mapping.
   *
   * @param state The prebuilt matchers.
   * @param blackhole The result consumer.
   */
  @Benchmark
  public void offsetAwareEnglishContractions(MatcherState state, Blackhole blackhole) {
    blackhole.consume(state.contractionAware.match(CONTRACTION_TEXT));
  }

  /**
   * Matches stemmed terms in the shorter input.
   *
   * @param state The prebuilt matchers.
   * @param blackhole The result consumer.
   */
  @Benchmark
  public void termAnalyzingStemShortText(MatcherState state, Blackhole blackhole) {
    blackhole.consume(state.termAnalyzing.match(SHORT_TEXT));
  }

  /**
   * Matches stemmed terms in the 4 KiB input.
   *
   * @param state The prebuilt matchers.
   * @param blackhole The result consumer.
   */
  @Benchmark
  public void termAnalyzingStemFourKib(MatcherState state, Blackhole blackhole) {
    blackhole.consume(state.termAnalyzing.match(FOUR_KIB_TEXT));
  }

  /**
   * Matches the larger glossary against the stemmed 4 KiB input.
   *
   * @param state The prebuilt matchers.
   * @param blackhole The result consumer.
   */
  @Benchmark
  public void termAnalyzingWideGlossaryFourKib(MatcherState state, Blackhole blackhole) {
    blackhole.consume(state.termAnalyzingWide.match(FOUR_KIB_TEXT));
  }
}
