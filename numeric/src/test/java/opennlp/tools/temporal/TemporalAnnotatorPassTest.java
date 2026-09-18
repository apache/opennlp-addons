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

package opennlp.tools.temporal;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Tests validation before and after reference-date extraction. */
class TemporalAnnotatorPassTest {

  private static final String TEXT = "2026-07-14; 2026-07-15";
  private static final TemporalExpression DAY = new TemporalExpression(new Span(0, 10),
      "2026-07-14", TemporalExpression.Granularity.DAY);

  /**
   * Supplies invalid results, including data after an otherwise valid date.
   *
   * @return The invalid results with descriptive names.
   */
  private static Stream<Arguments> invalidResults() {
    final TemporalExpression later = new TemporalExpression(new Span(12, 22), "2026-07-15",
        TemporalExpression.Granularity.DAY);
    return Stream.of(Arguments.of("null list", null),
        Arguments.of("null after date", Arrays.asList(DAY, null)),
        Arguments.of("outside text", List.of(DAY, new TemporalExpression(new Span(23, 30),
            "2026-07-16", TemporalExpression.Granularity.DAY))),
        Arguments.of("reversed", List.of(later, DAY)),
        Arguments.of("overlap", List.of(DAY, new TemporalExpression(new Span(5, 15),
            "2026-07-15", TemporalExpression.Granularity.DAY))));
  }

  /**
   * The complete absolute result is validated before a reference date is used.
   *
   * @param description The failure case.
   * @param absolute The invalid first-pass result.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidResults")
  void testInvalidAbsolutePass(String description, List<TemporalExpression> absolute) {
    final AtomicInteger resolvedCalls = new AtomicInteger();
    final TemporalExtractor extractor = new TemporalExtractor() {
      /** {@inheritDoc} */
      @Override
      public List<TemporalExpression> extract(CharSequence text) {
        return absolute;
      }

      /** {@inheritDoc} */
      @Override
      public List<TemporalExpression> extract(CharSequence text, LocalDate reference) {
        resolvedCalls.incrementAndGet();
        return List.of();
      }
    };
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new TemporalAnnotator(extractor).annotate(Document.of(TEXT)), description);
    Assertions.assertEquals(0, resolvedCalls.get());
  }

  /**
   * The resolved result must satisfy the same contract as the absolute result.
   *
   * @param description The failure case.
   * @param resolved The invalid resolved result.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidResults")
  void testInvalidResolvedPass(String description, List<TemporalExpression> resolved) {
    final AtomicInteger resolvedCalls = new AtomicInteger();
    final TemporalExtractor extractor = new TemporalExtractor() {
      /** {@inheritDoc} */
      @Override
      public List<TemporalExpression> extract(CharSequence text) {
        return List.of(DAY);
      }

      /** {@inheritDoc} */
      @Override
      public List<TemporalExpression> extract(CharSequence text, LocalDate reference) {
        resolvedCalls.incrementAndGet();
        Assertions.assertEquals(LocalDate.of(2026, 7, 14), reference);
        return resolved;
      }
    };
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new TemporalAnnotator(extractor).annotate(Document.of(TEXT)), description);
    Assertions.assertEquals(1, resolvedCalls.get());
  }
}
