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

package opennlp.tools.artifacts;

import java.util.Set;
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

/** Throughput benchmarks for clean text, dense damage, and type filtering. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class CursorArtifactDetectorBenchmark {

  private static final int DOCUMENT_LENGTH = 4_096;

  /** Shared immutable detectors and exact-length trial inputs. */
  @State(Scope.Benchmark)
  public static class BenchmarkState {

    private final CursorArtifactDetector all = new CursorArtifactDetector();
    private final CursorArtifactDetector textDamage = new CursorArtifactDetector(Set.of(
        TextArtifact.TYPE_MOJIBAKE, TextArtifact.TYPE_UNICODE_TAG));

    private String clean;
    private String damaged;

    /** Builds both documents once per trial. */
    @Setup(Level.Trial)
    public void setUp() {
      clean = exactLength("Ordinary multilingual prose: Déjà vu, 東京, Привет, and emoji 😀. ");
      final String mojibake = new String(new int[] {0x00E2, 0x0082, 0x00AC}, 0, 3);
      final String tags = new String(new int[] {0xE0073, 0xE0065, 0xE0063, 0xE007F}, 0, 4);
      final String control = new String(new int[] {0x0007}, 0, 1);
      damaged = exactLength("bad " + mojibake + " hidden " + tags
          + " control " + control + " ");
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
      if (Character.isHighSurrogate(text.charAt(DOCUMENT_LENGTH - 1))) {
        text.setCharAt(DOCUMENT_LENGTH - 1, ' ');
      }
      return text.substring(0, DOCUMENT_LENGTH);
    }
  }

  /**
   * Measures the default detector over clean multilingual text.
   *
   * @param state The trial inputs and detectors.
   * @param blackhole The result consumer.
   */
  @Benchmark
  public void clean(BenchmarkState state, Blackhole blackhole) {
    blackhole.consume(state.all.detect(state.clean));
  }

  /**
   * Measures all classes over dense mixed damage.
   *
   * @param state The trial inputs and detectors.
   * @param blackhole The result consumer.
   */
  @Benchmark
  public void allTypes(BenchmarkState state, Blackhole blackhole) {
    blackhole.consume(state.all.detect(state.damaged));
  }

  /**
   * Measures the selective detector over the same dense document.
   *
   * @param state The trial inputs and detectors.
   * @param blackhole The result consumer.
   */
  @Benchmark
  public void selectedTypes(BenchmarkState state, Blackhole blackhole) {
    blackhole.consume(state.textDamage.detect(state.damaged));
  }
}
