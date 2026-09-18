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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Document;
import opennlp.tools.numeric.NumericPacks;
import opennlp.tools.temporal.TemporalExpression.Granularity;
import opennlp.tools.temporal.TemporalExpression.Origin;
import opennlp.tools.util.Span;

/** Tests date extraction from clock suffixes and numeric offsets. */
class CursorTemporalTimestampTest {

  private static final String DATE = "2026-07-14";

  private final CursorTemporalExtractor extractor = new CursorTemporalExtractor();

  /**
   * Clock fields, fractions, and offsets must form a complete supported suffix.
   *
   * @param suffix The malformed time suffix.
   */
  @ParameterizedTest
  @ValueSource(strings = {"T25:00", "T99:00", "T12:60", "T12:99", "T24:01",
      "T24:00:01", "T24:00:00.1", "T24:00.1", "T12:30:61", "T12:30:99",
      "T09:30:00:01", "T09:30:1", "T09:30:000", "T09:30+", "T09:30-",
      "T09:30+05", "T09:30+5:30", "T09:30+05:3", "T09:30+05:300",
      "T09:30+24:00", "T09:30+05:60", "T09:30-99:00", "T09:30+05:30:00",
      "T09:30+05:30Z", "T09:30Z+05:30", "T09:30+05:30+01:00",
      "T09:30:00.1.2", "T09:30:00.1x", "T09:30:00.1_", "T09:30:00.1\u0301",
      "T09:30:00.Z", "T09:30:00..1", "T09:30:00,12,3"})
  void testInvalidTimestampSuffix(String suffix) {
    Assertions.assertEquals(List.of(), extractor.extract(DATE + suffix));
  }

  /**
   * Valid clock suffixes produce only the original date span.
   *
   * @param suffix The supported time suffix.
   */
  @ParameterizedTest
  @ValueSource(strings = {"T00:00", "T23:59", "T09:30:00", "T23:59:59Z", "T24:00",
      "T24:00:00.000Z", "T09:30.5", "T09:30,5", "T09:30:00.125",
      "T09:30:00,125+05:30", "T12:00-00:00", "T12:00+23:59",
      "T09:30+05:30", "T09:30-04:00", "t09:30z",
      "T09:30:00.123456789012345678901234567890Z"})
  void testSupportedTimestampSuffix(String suffix) {
    final String prefix = "Log \uD83D\uDCDD ";
    final String text = prefix + DATE + suffix + " recorded.";
    final List<TemporalExpression> mentions = extractor.extract(text);
    Assertions.assertEquals(1, mentions.size());
    final TemporalExpression mention = mentions.getFirst();
    Assertions.assertEquals(DATE, mention.value());
    Assertions.assertEquals(new Span(prefix.length(), prefix.length() + DATE.length()), mention.span());
    Assertions.assertEquals(DATE, mention.span().getCoveredText(text).toString());
    Assertions.assertEquals(Granularity.DAY, mention.granularity());
    Assertions.assertEquals(Origin.ABSOLUTE, mention.origin());
  }

  /**
   * Sentence punctuation remains separate from the clock suffix.
   *
   * @param punctuation The separator after a valid timestamp.
   */
  @ParameterizedTest
  @ValueSource(strings = {".", ",", ":", ";", "!", "?", ")", "]"})
  void testTimestampPunctuation(String punctuation) {
    final String text = DATE + "T09:30:00Z" + punctuation + " Next event.";
    final List<TemporalExpression> mentions = extractor.extract(text);
    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(new Span(0, DATE.length()), mentions.getFirst().span());
  }

  /** An invalid timestamp cannot supply the reference for relative dates. */
  @Test
  void testInvalidTimestampDoesNotSetDocumentDate() {
    final String text = "Bad 2026-07-14T99:99; good 2026-08-20T12:00Z; yesterday.";
    final Document document = NumericPacks.temporal().analyze(text);
    Assertions.assertEquals(List.of("2026-08-20", "2026-08-19"),
        document.get(TemporalAnnotator.TEMPORALS).stream()
            .map(mention -> mention.value().value()).toList());
    Assertions.assertEquals(LocalDate.of(2026, 8, 20),
        document.get(DocumentDateAnnotator.DOCUMENT_DATE).getFirst().value());
  }

  /** A leap-second suffix does not change the date extracted from the text. */
  @Test
  void testLeapSecondDate() {
    final List<TemporalExpression> mentions = extractor.extract("1990-12-31T23:59:60Z");
    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals("1990-12-31", mentions.getFirst().value());
    Assertions.assertEquals(new Span(0, DATE.length()), mentions.getFirst().span());
  }

  /**
   * Each clock field accepts its documented range and rejects larger two-digit values.
   *
   * @param field The clock field varied from 0 through 99.
   */
  @ParameterizedTest
  @ValueSource(strings = {"hour", "minute", "second", "offsetHour", "offsetMinute"})
  void testClockFieldRanges(String field) {
    final String template = switch (field) {
      case "hour" -> "T%02d:00:00Z";
      case "minute" -> "T12:%02d:00Z";
      case "second" -> "T12:00:%02dZ";
      case "offsetHour" -> "T12:00:00+%02d:00";
      case "offsetMinute" -> "T12:00:00+05:%02d";
      default -> throw new IllegalArgumentException(field);
    };
    final int maximum = switch (field) {
      case "hour" -> 24;
      case "second" -> 60;
      case "offsetHour" -> 23;
      default -> 59;
    };
    for (int value = 0; value < 100; value++) {
      final String text = DATE.concat(String.format(Locale.ROOT, template, value));
      Assertions.assertEquals(value <= maximum ? 1 : 0, extractor.extract(text).size(), text);
    }
  }

  /** JDK-generated offset timestamps retain their local date and date-only span. */
  @Test
  void testGeneratedOffsetTimestamps() {
    final Random random = new Random(20260905L);
    for (int i = 0; i < 500; i++) {
      final LocalDate date = LocalDate.of(2000, 1, 1).plusDays(random.nextInt(300_000));
      final ZoneOffset offset = ZoneOffset.ofTotalSeconds(random.nextInt(-72, 73) * 900);
      final OffsetDateTime time = date.atTime(random.nextInt(24), random.nextInt(60),
          random.nextInt(60), random.nextInt(1_000_000_000)).atOffset(offset);
      final String text = time.toString();
      final List<TemporalExpression> mentions = extractor.extract(text);
      Assertions.assertEquals(1, mentions.size(), text);
      Assertions.assertEquals(date.toString(), mentions.getFirst().value(), text);
      Assertions.assertEquals(new Span(0, DATE.length()), mentions.getFirst().span(), text);
    }
  }
}
