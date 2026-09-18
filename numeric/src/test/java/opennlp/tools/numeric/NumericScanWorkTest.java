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
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.money.CursorMoneyExtractor;
import opennlp.tools.quantity.CursorQuantityExtractor;
import opennlp.tools.temporal.CursorTemporalExtractor;

/** Checks character-access work without relying on machine timing. */
class NumericScanWorkTest {

  /**
   * Long combining-mark sequences do not cause repeated backward scans at each mark.
   *
   * @param length The number of combining marks in the input.
   */
  @ParameterizedTest
  @ValueSource(ints = {1_024, 2_048, 4_096})
  void testCombiningMarkScanWork(int length) {
    final String text = "x" + "\u0301".repeat(length) + " 5kg; $5; today";
    Assertions.assertAll(
        () -> assertScanWork(new CursorMoneyExtractor()::extract, text),
        () -> assertScanWork(new CursorQuantityExtractor()::extract, text),
        () -> assertScanWork(value -> new CursorTemporalExtractor()
            .extract(value, LocalDate.of(2026, 7, 14)), text));
  }

  /**
   * Timestamp fractions and punctuation are scanned with linear character-access work.
   *
   * @param length The length of the repeated suffix.
   */
  @ParameterizedTest
  @ValueSource(ints = {1_024, 4_096, 16_384})
  void testTimestampScanWork(int length) {
    final CursorTemporalExtractor extractor = new CursorTemporalExtractor();
    Assertions.assertAll(
        () -> assertScanWork(extractor::extract, "2026-07-14T09:30:00."
            + "1".repeat(length) + "Z"),
        () -> assertScanWork(extractor::extract, "2026-07-14T09:30:00Z"
            + ".".repeat(length) + " Next event."),
        () -> assertScanWork(extractor::extract, "2026-07-14T09:30:00."
            + "1".repeat(length) + "x; 14 July 2026"));
  }

  /**
   * Requires a result and limits direct character accesses to a multiple of input length.
   *
   * @param extract The extraction operation.
   * @param text The test input containing one mention for that extractor.
   */
  private void assertScanWork(Function<CharSequence, List<?>> extract, String text) {
    final CountingSequence input = new CountingSequence(text);
    Assertions.assertEquals(1, extract.apply(input).size());
    Assertions.assertTrue(input.accesses <= 40 * text.length(),
        () -> input.accesses + " character accesses for " + text.length() + " code units");
  }

  /** Counts direct character accesses; subsequences use the original string. */
  private static final class CountingSequence implements CharSequence {

    private final String text;
    private int accesses;

    /**
     * Initializes the access counter.
     *
     * @param text The test input.
     */
    private CountingSequence(String text) {
      this.text = text;
    }

    /** {@inheritDoc} */
    @Override
    public int length() {
      return text.length();
    }

    /** {@inheritDoc} */
    @Override
    public char charAt(int index) {
      accesses++;
      return text.charAt(index);
    }

    /** {@inheritDoc} */
    @Override
    public CharSequence subSequence(int start, int end) {
      return text.subSequence(start, end);
    }
  }
}
