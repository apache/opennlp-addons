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

package opennlp.tools.assets;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

/** Throughput benchmarks for clean text and the three new transport forms. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class CursorAssetDetectorBenchmark {

  /** Shared stateless detector and trial inputs. */
  @State(Scope.Benchmark)
  public static class BenchmarkState {

    private final CursorAssetDetector detector = new CursorAssetDetector();

    private String clean;
    private String wrapped;
    private String pem;
    private String jwt;

    /** Builds representative inputs once per trial. */
    @Setup(Level.Trial)
    public void setUp() {
      clean = "Ordinary prose with identifiers 550e8400-e29b-41d4-a716-446655440000. "
          .repeat(64);

      final byte[] image = new byte[4_096];
      final byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
      System.arraycopy(png, 0, image, 0, png.length);
      wrapped = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
          .encodeToString(image);

      final String body = Base64.getMimeEncoder(64,
          "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(new byte[4_096]);
      pem = "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----";

      final String header = encodeUrl("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
      final String claims = encodeUrl("{\"sub\":\"123\",\"roles\":[\"reader\",\"writer\"]}");
      jwt = header + "." + claims + "." + encodeUrl("32-byte-signature-placeholder-value");
    }

    /** @return The unpadded base64url image of UTF-8 text. */
    private String encodeUrl(String value) {
      return Base64.getUrlEncoder().withoutPadding()
          .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
  }

  /** Measures the false-positive hot path over clean prose. */
  @Benchmark
  public void clean(BenchmarkState state, Blackhole blackhole) {
    blackhole.consume(state.detector.detect(state.clean));
  }

  /** Measures one 4 KiB MIME-wrapped binary. */
  @Benchmark
  public void wrapped(BenchmarkState state, Blackhole blackhole) {
    blackhole.consume(state.detector.detect(state.wrapped));
  }

  /** Measures one 4 KiB PEM envelope. */
  @Benchmark
  public void pem(BenchmarkState state, Blackhole blackhole) {
    blackhole.consume(state.detector.detect(state.pem));
  }

  /** Measures compact JWT structural validation. */
  @Benchmark
  public void jwt(BenchmarkState state, Blackhole blackhole) {
    blackhole.consume(state.detector.detect(state.jwt));
  }
}
