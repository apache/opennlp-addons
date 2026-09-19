/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
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

package opennlp.tools.noise;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests the value and validation contract of {@link NoiseSpan}. */
public class NoiseSpanTest {

  /** Checks record accessors, equality, hashing, and text representation. */
  @Test
  void testRecordContract() {
    final Span span = new Span(1, 4);
    final NoiseSpan noise =
        new NoiseSpan(span, NoiseSpan.SEVERITY_DAMAGED, 0.5);
    final NoiseSpan equal =
        new NoiseSpan(new Span(1, 4), NoiseSpan.SEVERITY_DAMAGED, 0.5);

    assertEquals(span, noise.span());
    assertEquals(NoiseSpan.SEVERITY_DAMAGED, noise.severity());
    assertEquals(0.5, noise.score());
    assertEquals(equal, noise);
    assertEquals(equal.hashCode(), noise.hashCode());
    assertNotEquals(new NoiseSpan(span, NoiseSpan.SEVERITY_GIBBERISH, 0.5), noise);
    assertNotEquals(new NoiseSpan(new Span(1, 5), NoiseSpan.SEVERITY_DAMAGED, 0.5), noise);
    assertNotEquals(new NoiseSpan(span, NoiseSpan.SEVERITY_DAMAGED, 0.75), noise);
    assertEquals("NoiseSpan[span=[1..4), severity=damaged, score=0.5]",
        noise.toString());
  }

  /** Accepts the documented lower and upper score boundaries. */
  @Test
  void testScoreBoundaries() {
    final Span span = new Span(0, 1);
    assertEquals(Double.MIN_VALUE,
        new NoiseSpan(span, NoiseSpan.SEVERITY_DAMAGED, Double.MIN_VALUE).score());
    assertEquals(1.0,
        new NoiseSpan(span, NoiseSpan.SEVERITY_DAMAGED, 1.0).score());
  }

  /** Accepts non-built-in severity names. */
  @Test
  void testCustomSeverityIsAccepted() {
    assertEquals("ocr:suspect", new NoiseSpan(new Span(0, 1), "ocr:suspect", 0.5).severity());
  }

  /** @return Invalid record arguments and their expected validation messages. */
  private static Stream<Arguments> invalidValues() {
    final Span span = new Span(0, 1);
    return Stream.of(
        Arguments.of("null span", "span must not be null",
            (Executable) () -> new NoiseSpan(null, NoiseSpan.SEVERITY_DAMAGED, 0.5)),
        Arguments.of("null severity", "severity must not be null or blank",
            (Executable) () -> new NoiseSpan(span, null, 0.5)),
        Arguments.of("empty severity", "severity must not be null or blank",
            (Executable) () -> new NoiseSpan(span, "", 0.5)),
        Arguments.of("blank severity", "severity must not be null or blank",
            (Executable) () -> new NoiseSpan(span, " ", 0.5)),
        Arguments.of("Unicode blank severity", "severity must not be null or blank",
            (Executable) () -> new NoiseSpan(span, "\t\u2003\n", 0.5)),
        Arguments.of("zero score", "score must be in (0, 1], got: 0.0",
            (Executable) () -> new NoiseSpan(span, NoiseSpan.SEVERITY_DAMAGED, 0.0)),
        Arguments.of("negative zero score", "score must be in (0, 1], got: -0.0",
            (Executable) () -> new NoiseSpan(span, NoiseSpan.SEVERITY_DAMAGED, -0.0)),
        Arguments.of("negative score", "score must be in (0, 1], got: -0.1",
            (Executable) () -> new NoiseSpan(span, NoiseSpan.SEVERITY_DAMAGED, -0.1)),
        Arguments.of("score above one",
            "score must be in (0, 1], got: 1.0000000000000002",
            (Executable) () -> new NoiseSpan(span, NoiseSpan.SEVERITY_DAMAGED,
                Math.nextUp(1.0))),
        Arguments.of("NaN score", "score must be in (0, 1], got: NaN",
            (Executable) () -> new NoiseSpan(span, NoiseSpan.SEVERITY_DAMAGED, Double.NaN)),
        Arguments.of("negative infinite score", "score must be in (0, 1], got: -Infinity",
            (Executable) () -> new NoiseSpan(span, NoiseSpan.SEVERITY_DAMAGED,
                Double.NEGATIVE_INFINITY)),
        Arguments.of("infinite score", "score must be in (0, 1], got: Infinity",
            (Executable) () -> new NoiseSpan(span, NoiseSpan.SEVERITY_DAMAGED,
                Double.POSITIVE_INFINITY)));
  }

  /** Rejects each invalid record component with its contract message. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidValues")
  void testRejectsInvalidValues(String name, String expectedMessage, Executable call) {
    final IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, call, name);
    assertEquals(expectedMessage, error.getMessage());
  }
}
