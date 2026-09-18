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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.util.Span;

/** Tests the default reference-date overload independently of concrete extractors. */
class TemporalExtractorContractTest {

  private static final LocalDate DATE = LocalDate.of(2026, 7, 14);

  /** Null text is rejected before delegating to the absolute extractor. */
  @Test
  void testNullText() {
    final AtomicInteger calls = new AtomicInteger();
    final TemporalExtractor extractor = text -> {
      calls.incrementAndGet();
      return List.of();
    };
    Assertions.assertThrows(IllegalArgumentException.class, () -> extractor.extract(null, DATE));
    Assertions.assertEquals(0, calls.get());
  }

  /** A null reference is rejected before delegation. */
  @Test
  void testNullReference() {
    final TemporalExtractor extractor = text -> Assertions.fail("invalid arguments must not delegate");
    Assertions.assertThrows(IllegalArgumentException.class, () -> extractor.extract("text", null));
  }

  /** Valid input delegates once without copying or changing the result. */
  @Test
  void testDelegation() {
    final CharSequence source = new StringBuilder("2026-07-14");
    final List<TemporalExpression> expected = List.of(new TemporalExpression(new Span(0, 10),
        "2026-07-14", TemporalExpression.Granularity.DAY));
    final AtomicInteger calls = new AtomicInteger();
    final TemporalExtractor extractor = text -> {
      Assertions.assertSame(source, text);
      calls.incrementAndGet();
      return expected;
    };
    Assertions.assertSame(expected, extractor.extract(source, DATE));
    Assertions.assertEquals(1, calls.get());
  }
}
