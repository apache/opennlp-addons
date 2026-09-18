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

package opennlp.tools.pii;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import opennlp.tools.document.Annotation;
import opennlp.tools.util.Span;

/** Measures offset and annotation remapping across a dense PII rewrite. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class PiiRewriteBenchmark {

  private static final int MENTIONS = 4096;

  /** Holds one dense rewrite and its annotations for all benchmark invocations. */
  @State(Scope.Benchmark)
  public static class RewriteState {

    PiiRewrite rewrite;
    List<Annotation<String>> annotations;

    /** Builds a rewrite containing one replacement every three input characters. */
    @Setup
    public void create() {
      final String text = "xx ".repeat(MENTIONS);
      final List<PiiMention> mentions = new ArrayList<>(MENTIONS);
      final List<Annotation<String>> sourceAnnotations = new ArrayList<>(MENTIONS);
      for (int i = 0; i < MENTIONS; i++) {
        final int start = i * 3;
        final Span span = new Span(start, start + 2);
        mentions.add(new PiiMention(span, PiiMention.TYPE_EMAIL, "value" + i));
        sourceAnnotations.add(new Annotation<>(span, "value" + i));
      }
      rewrite = new Pseudonymizer().rewrite(text, mentions);
      annotations = List.copyOf(sourceAnnotations);
    }
  }

  /**
   * Measures individual offset lookups across the complete dense rewrite.
   *
   * @param state The dense rewrite.
   * @param bh Consumes mapped offsets.
   */
  @Benchmark
  @OperationsPerInvocation(MENTIONS)
  public void mapOffsets(RewriteState state, Blackhole bh) {
    for (int i = 0; i < MENTIONS; i++) {
      bh.consume(state.rewrite.mapOffset(i * 3 + 1));
    }
  }

  /**
   * Measures bulk annotation remapping across the complete dense rewrite.
   *
   * @param state The dense rewrite and annotations.
   * @param bh Consumes mapped annotations.
   */
  @Benchmark
  @OperationsPerInvocation(MENTIONS)
  public void remapAnnotations(RewriteState state, Blackhole bh) {
    bh.consume(state.rewrite.remap(state.annotations));
  }
}
