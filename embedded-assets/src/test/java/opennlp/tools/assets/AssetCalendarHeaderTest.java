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
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests calendar opening lines without interpreting event properties. */
class AssetCalendarHeaderTest {

  private static final String IDENTIFIER = "BEGIN:VCALENDAR";
  private static final String CRLF = "\r\n";
  private static final String FORMAT = "ics";
  private static final String MEDIA_TYPE = "text/calendar";
  private static final String URI = "data:;base64,";
  private static final List<String> TRANSPORTS = List.of("standard", "url", "mime64", "mime76", "uri");
  private static final String BODY = "PRODID:-//Fieldwork Planner//EN\r\n"
      + "VERSION:2.0\r\nBEGIN:VEVENT\r\nUID:survey@example.org\r\n"
      + "DTSTAMP:20260907T090000Z\r\nDTSTART:20260908T100000Z\r\n"
      + "SUMMARY:Survey schedule\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

  private final AssetDetector detector = new CursorAssetDetector();

  /** {@return letter-case and line-continuation variants in each payload transport} */
  private static Stream<Arguments> openings() {
    final Stream<String> cases = Stream.of(IDENTIFIER, "begin:vcalendar", "BeGiN:vCaLeNdAr");
    final Stream<String> continuations = IntStream.range(1, IDENTIFIER.length()).boxed()
        .flatMap(at -> Stream.of(" ", "\t").map(space ->
            IDENTIFIER.substring(0, at) + CRLF + space + IDENTIFIER.substring(at)));
    return Stream.concat(cases, continuations).flatMap(line -> TRANSPORTS.stream()
        .map(transport -> Arguments.of(line + CRLF + BODY, transport)));
  }

  /**
   * Content-line case and folding do not change the calendar identifier.
   *
   * @param calendar The original event fixture.
   * @param transport The encoding to check.
   */
  @ParameterizedTest
  @MethodSource("openings")
  void testOpening(String calendar, String transport) {
    AssetTestSupport.assertIdentified(detector, calendar.getBytes(StandardCharsets.US_ASCII),
        FORMAT, MEDIA_TYPE, transport);
  }

  /**
   * A longer component name or malformed line is not a calendar opening.
   *
   * @param opening The invalid first content line.
   */
  @ParameterizedTest
  @ValueSource(strings = {"BEGIN:VCALENDAR-NOTES\r\n", "BEGIN:VCALENDARS\r\n",
      "BEGIN:VCALENDAR notes\r\n", "BEGIN:VCALENDAR\u0000\r\n",
      "BEGIN:VCALENDAR\r\n -NOTES\r\n", "BEGIN:VCALENDAR\r\n\tS\r\n",
      "BEGIN:VCALENDAR\n", "BEGIN:VCALENDAR\r", "BEGIN:VCALENDAR\rX\n",
      "BEGIN:VCAL\r\n  ENDAR\r\n", "BEGIN:VCAL\n ENDAR\r\n",
      "BEGIN:VCALENDAR;\r\n", "BEGIN;X=1:VCALENDAR\r\n", "BEGIN:VCALENDAR:\r\n"})
  void testInvalidOpening(String opening) {
    AssetTestSupport.assertUnrecognized(detector,
        (opening + BODY).getBytes(StandardCharsets.US_ASCII));
  }

  /** {@return control and non-ASCII substitutions in every opening-line position} */
  private static Stream<Arguments> changedBytes() {
    return IntStream.range(0, IDENTIFIER.length()).boxed().flatMap(at ->
        IntStream.of(0, 0x1a, 0x3f, 0x7f, 0x80, 0xfe, 0xff)
            .mapToObj(value -> Arguments.of(at, value)));
  }

  /**
   * Case handling does not accept other bytes, including either base64 alphabet's final digits.
   *
   * @param at The changed byte position.
   * @param value The replacement byte.
   */
  @ParameterizedTest
  @MethodSource("changedBytes")
  void testChangedByte(int at, int value) {
    final byte[] bytes = (IDENTIFIER + CRLF + BODY).getBytes(StandardCharsets.US_ASCII);
    bytes[at] = (byte) value;
    AssetTestSupport.assertUnrecognized(detector, bytes);
    assertEquals(List.of(), detector.detect(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)));
  }

  /**
   * Both bytes of the opening line's CRLF are required.
   *
   * @param length The available prefix length.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
  void testTruncation(int length) {
    AssetTestSupport.assertUnrecognized(detector,
        Arrays.copyOf((IDENTIFIER + CRLF).getBytes(StandardCharsets.US_ASCII), length));
  }

  /** Recognition identifies a complete opening line without validating the event body. */
  @Test
  void testOpeningOnly() {
    AssetTestSupport.assertIdentified(detector,
        (IDENTIFIER + CRLF).getBytes(StandardCharsets.US_ASCII), FORMAT, MEDIA_TYPE, "uri");
  }

  /** The version property does not have to be in the decoded prefix. */
  @Test
  void testLaterVersion() {
    final String calendar = IDENTIFIER + CRLF + "X-NOTE:" + "survey ".repeat(100) + CRLF + BODY;
    AssetTestSupport.assertIdentified(detector, calendar.getBytes(StandardCharsets.US_ASCII),
        FORMAT, MEDIA_TYPE, "mime76");
  }

  /** Line continuations are checked without a fixed decoded-header length limit. */
  @Test
  void testRepeatedFolding() {
    final String calendar = "B" + "\r\n ".repeat(1000) + "EGIN:VCALENDAR" + CRLF + BODY;
    AssetTestSupport.assertIdentified(detector, calendar.getBytes(StandardCharsets.US_ASCII),
        FORMAT, MEDIA_TYPE, "mime64");
  }

  /** Repeated line continuations do not hide an extended component name after the identifier. */
  @Test
  void testFoldedSuffix() {
    final String calendar = IDENTIFIER + "\r\n\t".repeat(1000) + "-NOTES" + CRLF + BODY;
    AssetTestSupport.assertUnrecognized(detector, calendar.getBytes(StandardCharsets.US_ASCII));
  }

  /** A trailing line continuation is not the terminating content-line break. */
  @Test
  void testUnterminatedFold() {
    AssetTestSupport.assertUnrecognized(detector,
        (IDENTIFIER + "\r\n ").getBytes(StandardCharsets.US_ASCII));
  }

  /** A supplied type is retained when the payload has no calendar opening. */
  @Test
  void testDeclaredType() {
    final byte[] bytes = "BEGIN:VCALENDAR-NOTES\r\nSurvey schedule".getBytes(StandardCharsets.US_ASCII);
    final String text = "data:" + MEDIA_TYPE + ";base64,"
        + Base64.getEncoder().encodeToString(bytes);
    final var assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals("calendar", assets.get(0).format());
    assertEquals(MEDIA_TYPE, assets.get(0).mediaType());
    assertArrayEquals(bytes, assets.get(0).decode(text));
  }

  /** Tests the manual's event attachment without altering its text. */
  @Test
  void testManualExample() {
    final String calendar = "begin:vcalendar\r\n" + BODY;
    final String text = URI + Base64.getEncoder().encodeToString(
        calendar.getBytes(StandardCharsets.US_ASCII));
    final var assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals(FORMAT, assets.get(0).format());
    assertEquals(MEDIA_TYPE, assets.get(0).mediaType());
    assertEquals(calendar, new String(assets.get(0).decode(text), StandardCharsets.US_ASCII));
  }
}
