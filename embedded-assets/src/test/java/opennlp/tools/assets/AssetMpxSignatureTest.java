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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests MPX format identifiers independently of the producing program name. */
class AssetMpxSignatureTest {

  private static final String FORMAT = "mpx";
  private static final String MEDIA_TYPE = "application/x-project";
  private static final String URI = "data:;base64,";
  private static final String PROGRAM = "Fieldwork Planner";
  private static final List<String> TRANSPORTS = List.of("standard", "url", "mime64", "mime76", "uri");

  private final AssetDetector detector = new CursorAssetDetector();

  /** {@return producer-name variants with both common separators and each transport} */
  private static Stream<Arguments> producers() {
    return Stream.of("Microsoft Project for Windows", "Microsoft Project", "Primavera Systems",
        PROGRAM, "\tFieldwork Planner\t", "\"Survey, task planner\"", "\"Survey; task planner\"")
        .flatMap(program -> Stream.of(',', ';').flatMap(separator -> TRANSPORTS.stream()
            .map(transport -> Arguments.of(program, separator, transport))));
  }

  /**
   * A producing program is not part of the MPX identifier.
   *
   * @param program The program-name field.
   * @param separator The list separator.
   * @param transport The payload encoding.
   */
  @ParameterizedTest
  @MethodSource("producers")
  void testProducerAndTransport(String program, char separator, String transport) {
    final byte[] bytes = project(program, separator);
    final String standard = Base64.getEncoder().encodeToString(bytes);
    assertTrue(standard.contains("+") && standard.contains("/"));
    AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, transport);
  }

  /** {@return replacements for the first field's separating byte} */
  private static IntStream separators() {
    return IntStream.of(0, '\t', '\n', '\r', ' ', '"', ',', ';', ':', '|', '0', 'A', 0x7f, 0x80, 0xff);
  }

  /**
   * Automatic recognition supports comma and semicolon list separators.
   *
   * @param separator The byte immediately after MPX.
   */
  @ParameterizedTest
  @MethodSource("separators")
  void testSeparator(int separator) {
    final byte[] bytes = project(PROGRAM, ',');
    bytes[3] = (byte) separator;
    if (separator == ',' || separator == ';') {
      AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, "standard");
      AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, "uri");
    } else {
      AssetTestSupport.assertUnrecognized(detector, bytes);
    }
  }

  /** {@return changes to the MPX identifier, including its letter case} */
  private static Stream<Arguments> identifiers() {
    return IntStream.range(0, 3).boxed().flatMap(offset ->
        IntStream.of(0, 'x', 0x80, 0xff).mapToObj(value -> Arguments.of(offset, value)));
  }

  /**
   * A matching separator does not identify a different first field as MPX.
   *
   * @param offset The changed letter position.
   * @param value The replacement byte.
   */
  @ParameterizedTest
  @MethodSource("identifiers")
  void testChangedIdentifier(int offset, int value) {
    final byte[] bytes = project(PROGRAM, ',');
    bytes[offset] = (byte) value;
    AssetTestSupport.assertUnrecognized(detector, bytes);
  }

  /**
   * The initial separator must be present even for a short data URI.
   *
   * @param length The available byte count.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void testTruncatedIdentifier(int length) {
    AssetTestSupport.assertUnrecognized(detector, Arrays.copyOf(project(PROGRAM, ','), length));
  }

  /**
   * Recognition does not validate the version, code page or following project records.
   *
   * @param separator The supported separator.
   */
  @ParameterizedTest
  @ValueSource(chars = {',', ';'})
  void testIdentifierWithoutProject(char separator) {
    AssetTestSupport.assertIdentified(detector, Arrays.copyOf(project(PROGRAM, separator), 4),
        FORMAT, MEDIA_TYPE, "uri");
  }

  /**
   * A nonstandard separator can use a caller-supplied media type without inferred MPX metadata.
   *
   * @param separator The separator not supported by automatic recognition.
   */
  @ParameterizedTest
  @ValueSource(chars = {'|', ':'})
  void testSuppliedType(char separator) {
    final byte[] bytes = project(PROGRAM, separator);
    final String text = "data:" + MEDIA_TYPE + ";base64,"
        + Base64.getEncoder().encodeToString(bytes);
    final var assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals("x-project", assets.get(0).format());
    assertEquals(MEDIA_TYPE, assets.get(0).mediaType());
    assertArrayEquals(bytes, assets.get(0).decode(text));
  }

  /** Tests the manual's original project-header and comment example. */
  @Test
  void testManualExample() {
    final String project = "MPX;Fieldwork Planner;4.0;ANSI\r\n0;Survey schedule\r\n";
    final String text = URI + Base64.getEncoder().encodeToString(
        project.getBytes(StandardCharsets.US_ASCII));
    final var assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals(FORMAT, assets.get(0).format());
    assertEquals(MEDIA_TYPE, assets.get(0).mediaType());
    assertEquals(project, new String(assets.get(0).decode(text), StandardCharsets.US_ASCII));
  }

  /**
   * Creates an original file-creation record and comments for base64 transport checks.
   *
   * @param program The producing program's name.
   * @param separator The list separator.
   * @return The ASCII project bytes.
   */
  private byte[] project(String program, char separator) {
    return ("MPX" + separator + program + separator + "4.0" + separator + "ANSI\r\n"
        + '0' + separator + "Survey schedule\r\n"
        + '0' + separator + "??????~~~~~~\r\n").getBytes(StandardCharsets.US_ASCII);
  }
}
