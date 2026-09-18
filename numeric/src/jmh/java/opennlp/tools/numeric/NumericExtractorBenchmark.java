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

package opennlp.tools.numeric;

import java.time.LocalDate;
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

import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.money.CursorMoneyExtractor;
import opennlp.tools.quantity.CursorQuantityExtractor;
import opennlp.tools.temporal.CursorTemporalExtractor;

/**
 * Throughput benchmarks for numeric extraction over exact 4,096-character documents.
 * Clean input exercises accepted money, quantity, and temporal forms; malformed input
 * exercises fail-closed separator paths.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class NumericExtractorBenchmark {

  private static final int DOCUMENT_LENGTH = 4_096;

  /** Shared immutable extractors and benchmark documents. */
  @State(Scope.Benchmark)
  public static class BenchmarkState {

    private final CursorMoneyExtractor money = new CursorMoneyExtractor();
    private final CursorQuantityExtractor quantity = new CursorQuantityExtractor();
    private final CursorTemporalExtractor temporal = new CursorTemporalExtractor();
    private final DocumentAnalyzer fullPipeline = NumericPacks.fullPipeline();

    private String clean;
    private String malformed;

    /** Builds exact-length inputs once per trial. */
    @Setup(Level.Trial)
    public void setUp() {
      clean = exactLength("Filed 14 July 2026. Paid $1.2 million yesterday for 1,250 GB. ");
      malformed = exactLength("Rejected $1.2.3 and 1,00,000 USD with 1.2.3 kg. ");
    }

    /**
     * Repeats and truncates a seed to the benchmark document length.
     *
     * @param seed The non-empty text to repeat.
     * @return An exact-length document.
     */
    private String exactLength(String seed) {
      final StringBuilder text = new StringBuilder(DOCUMENT_LENGTH);
      while (text.length() < DOCUMENT_LENGTH) {
        text.append(seed);
      }
      return text.substring(0, DOCUMENT_LENGTH);
    }
  }

  /** Measures accepted money shapes. */
  @Benchmark
  public void money(BenchmarkState state, Blackhole blackhole) {
    blackhole.consume(state.money.extract(state.clean));
  }

  /** Measures accepted quantities. */
  @Benchmark
  public void quantity(BenchmarkState state, Blackhole blackhole) {
    blackhole.consume(state.quantity.extract(state.clean));
  }

  /** Measures relative and absolute temporal extraction together. */
  @Benchmark
  public void temporal(BenchmarkState state, Blackhole blackhole) {
    blackhole.consume(state.temporal.extract(state.clean, LocalDate.of(2026, 7, 14)));
  }

  /** Measures the ready-made pipeline over all numeric layers. */
  @Benchmark
  public void fullPipeline(BenchmarkState state, Blackhole blackhole) {
    blackhole.consume(state.fullPipeline.analyze(state.clean));
  }

  /** Measures fail-closed scans with repeated and unsupported separators. */
  @Benchmark
  public void malformed(BenchmarkState state, Blackhole blackhole) {
    blackhole.consume(state.money.extract(state.malformed));
    blackhole.consume(state.quantity.extract(state.malformed));
  }
}
