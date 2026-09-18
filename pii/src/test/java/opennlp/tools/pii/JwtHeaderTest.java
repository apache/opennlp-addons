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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Checks compact token syntax and JSON headers using synthetic unsigned byte sequences. */
class JwtHeaderTest {

  private static final SecretsPiiExtractor EXTRACTOR =
      new SecretsPiiExtractor(Set.of(PiiMention.TYPE_JWT));
  private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
  private static final String HEADER = "{\"alg\":\"HS256\"}";
  private static final String PAYLOAD = "eyJzdWIiOiJ0ZXN0In0";
  private static final String SIGNATURE = "dGVzdA";

  /**
   * Encodes a synthetic header and attaches an opaque payload/signature.
   *
   * @param json The header JSON.
   * @return The three-part candidate.
   */
  private static String token(String json) {
    return BASE64.encodeToString(json.getBytes(StandardCharsets.UTF_8))
        + "." + PAYLOAD + "." + SIGNATURE;
  }

  /**
   * Checks valid header syntax, escaped member names and algorithm placement.
   *
   * @param json The valid header.
   */
  @ParameterizedTest
  @ValueSource(strings = {HEADER, "{ \"alg\":\"HS256\"}", " \r\n\t{\"alg\":\"HS256\"} \r\n\t",
      "{\"\\u0061lg\":\"HS256\"}", "{\"a\\u006cg\":\"HS256\"}",
      "{\"al\\u0067\":\"HS256\"}", "{\"alg\":\"H\\u0053256\"}",
      "{\"kid\":\"é😀\",\"alg\":\"HS256\"}",
      "{\"other\":{\"alg\":null},\"alg\":\"RS256\"}",
      "{\"alg\":\"RS256\",\"meta\":[{},[],true,false,null,0,-1,1.5,1e1000000]}",
      "{\"meta\":\"\\\"alg\\\":null\",\"alg\":\"HS256\"}",
      "{\"kid\":\"\\b\\f\\n\\r\\t\\/\\\\\\\"\",\"alg\":\"HS256\"}"})
  void testValidHeader(String json) {
    final String value = token(json);
    final String prefix = "😀 Bearer ";
    Assertions.assertEquals(List.of(new PiiMention(
        new Span(prefix.length(), prefix.length() + value.length()), PiiMention.TYPE_JWT, value)),
        EXTRACTOR.extract(prefix + value + ";"));
  }

  /**
   * Checks that the algorithm can follow a long field, beyond a prefix scan.
   *
   * @param length The field length before the algorithm.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 32, 64, 65, 66, 67, 88, 128, 4096, 65536})
  void testLateAlgorithm(int length) {
    final String value = token("{\"kid\":\"" + "x".repeat(length) + "\",\"alg\":\"HS256\"}");
    Assertions.assertEquals(List.of(new PiiMention(new Span(0, value.length()),
        PiiMention.TYPE_JWT, value)), EXTRACTOR.extract(value));
  }

  /**
   * Provides every ASCII character before the header object.
   *
   * @return The ASCII character values.
   */
  private static IntStream leadingCharacters() {
    return IntStream.range(0, 128);
  }

  /**
   * Checks that only JSON whitespace can precede the object.
   *
   * @param character The leading ASCII character.
   */
  @ParameterizedTest
  @MethodSource("leadingCharacters")
  void testLeadingCharacter(int character) {
    final boolean whitespace = character == ' ' || character == '\t'
        || character == '\r' || character == '\n';
    Assertions.assertEquals(whitespace ? 1 : 0,
        EXTRACTOR.extract(token((char) character + HEADER)).size());
  }

  /**
   * Rejects invalid JSON and missing, nested, repeated or non-string algorithms.
   *
   * @param json The invalid header.
   */
  @ParameterizedTest
  @ValueSource(strings = {"{}", "[]", "null", "true", "123", "{\"meta\":{\"alg\":\"HS256\"}}",
      "{\"alg\":true}", "{\"alg\":false}", "{\"alg\":null}", "{\"alg\":123}",
      "{\"alg\":[]}", "{\"alg\":{}}", "{\"alg\":\"\"}", "{\"alg\":\"é\"}",
      "{\"alg\":\"\\u00e9\"}", "{\"alg\":\"HS256\"", "{\"alg\":\"HS256}",
      "{\"alg\":\"HS256\",}", "{\"alg\":\"HS256\"}garbage", "{\"alg\":\"HS256\"}{}",
      "{\"alg\":\"HS256\",\"alg\":\"RS256\"}",
      "{\"alg\":\"HS256\",\"\\u0061lg\":\"HS256\"}",
      "{\"alg\":\"HS256\" \"kid\":0}", "{\"alg\" \"HS256\"}",
      "{\"alg\":\"HS256\",\"x\":[1,]}", "{\"alg\":\"HS256\",\"x\":{\"a\":1,}}",
      "{\"alg\":\"HS256\",\"x\":[1 2]}", "{\"alg\":\"HS256\",\"x\":{a:1}}",
      "{\"alg\":\"HS256\",\"x\":\"\\x20\"}", "{\"alg\":\"HS256\",\"x\":\"\\u12\"}",
      "{\"alg\":\"HS256\",\"x\":\"\\u12g0\"}", "{\"alg\":\"HS256\",\"x\":NaN}",
      "{\"alg\":\"HS256\",\"x\":Infinity}", "{\"alg\":\"HS256\",\"x\":01}",
      "{\"alg\":\"HS256\",\"x\":-01}", "{\"alg\":\"HS256\",\"x\":+1}",
      "{\"alg\":\"HS256\",\"x\":1.}", "{\"alg\":\"HS256\",\"x\":.1}",
      "{\"alg\":\"HS256\",\"x\":1e}", "{\"alg\":\"HS256\",\"x\":1e+}",
      "{\"alg\":\"HS256\",\"x\":-}", "{\"alg\":\"HS256\",\"x\":TRUE}",
      "{\"alg\":\"HS256\",\"x\":nul}", "{\"alg\":\"HS256\",\"x\":falsee}"})
  void testInvalidHeader(String json) {
    Assertions.assertTrue(EXTRACTOR.extract(token(json)).isEmpty());
  }

  /**
   * Provides raw control characters forbidden in JSON strings.
   *
   * @return The control code points.
   */
  private static IntStream controls() {
    return IntStream.range(0, 32);
  }

  /**
   * Rejects control bytes inside an unescaped string after a valid algorithm.
   *
   * @param control The forbidden raw control.
   */
  @ParameterizedTest
  @MethodSource("controls")
  void testRawControl(int control) {
    Assertions.assertTrue(EXTRACTOR.extract(token(
        "{\"alg\":\"HS256\",\"x\":\"" + (char) control + "\"}")).isEmpty());
  }

  /**
   * Checks deeply nested fields without using the Java call stack for JSON parsing.
   *
   * @param depth The array nesting depth.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 32, 256, 4096, 32768})
  void testDeepArray(int depth) {
    final String value = token("{\"x\":" + "[".repeat(depth) + "0" + "]".repeat(depth)
        + ",\"alg\":\"HS256\"}");
    Assertions.assertEquals(1, EXTRACTOR.extract(value).size());
  }

  /**
   * Checks that nested object keys cannot replace the root algorithm.
   *
   * @param depth The nested object count.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 32, 1024, 8192})
  void testDeepObject(int depth) {
    final String nested = "{\"x\":".repeat(depth) + HEADER + "}".repeat(depth);
    Assertions.assertTrue(EXTRACTOR.extract(token(nested)).isEmpty());
    Assertions.assertEquals(1, EXTRACTOR.extract(token(
        "{\"x\":" + nested + ",\"alg\":\"HS256\"}")).size());
  }

  /**
   * Provides invalid UTF-8 sequences inside an otherwise valid header.
   *
   * @return The invalid encoded bytes.
   */
  private static Stream<Arguments> invalidUtf8() {
    return Stream.of(new byte[] {(byte) 0xff}, new byte[] {(byte) 0x80},
        new byte[] {(byte) 0xc0, (byte) 0xaf}, new byte[] {(byte) 0xc2},
        new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
        new byte[] {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80})
        .map(bytes -> Arguments.of((Object) bytes));
  }

  /**
   * Rejects invalid UTF-8 instead of replacing bytes during decoding.
   *
   * @param invalid The malformed UTF-8 bytes.
   */
  @ParameterizedTest
  @MethodSource("invalidUtf8")
  void testInvalidUtf8(byte[] invalid) {
    final byte[] prefix = "{\"alg\":\"HS256\",\"x\":\"".getBytes(StandardCharsets.UTF_8);
    final byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
    final byte[] header = new byte[prefix.length + invalid.length + suffix.length];
    System.arraycopy(prefix, 0, header, 0, prefix.length);
    System.arraycopy(invalid, 0, header, prefix.length, invalid.length);
    System.arraycopy(suffix, 0, header, prefix.length + invalid.length, suffix.length);
    Assertions.assertTrue(EXTRACTOR.extract(BASE64.encodeToString(header)
        + "." + PAYLOAD + "." + SIGNATURE).isEmpty());
  }

  /**
   * Checks short non-empty encodings and all base64 remainder lengths.
   *
   * @param length The synthetic signature byte count.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 32, 64, 256})
  void testSignatureLength(int length) {
    final String value = BASE64.encodeToString(HEADER.getBytes(StandardCharsets.UTF_8))
        + ".e30." + BASE64.encodeToString(new byte[length]);
    Assertions.assertEquals(1, EXTRACTOR.extract(value).size());
  }

  /**
   * Provides malformed encodings in each compact segment.
   *
   * @return The malformed token.
   */
  private static Stream<String> invalidSegments() {
    final List<String> values = new ArrayList<>();
    final String header = BASE64.encodeToString(HEADER.getBytes(StandardCharsets.UTF_8));
    for (int segment = 0; segment < 3; segment++) {
      for (final String bad : List.of("", "A", "AAAAA", "AB", "AAB", "AAAA=", "AAAA+", "AAAA/")) {
        final List<String> parts = new ArrayList<>(List.of(header, PAYLOAD, SIGNATURE));
        parts.set(segment, bad);
        values.add(String.join(".", parts));
      }
    }
    values.add(header + "A." + PAYLOAD + "." + SIGNATURE);
    values.add(header + "." + PAYLOAD + "." + SIGNATURE + "=");
    return values.stream();
  }

  /**
   * Rejects malformed encodings rather than reporting a valid-looking prefix.
   *
   * @param value The malformed compact token.
   */
  @ParameterizedTest
  @MethodSource("invalidSegments")
  void testInvalidSegment(String value) {
    Assertions.assertTrue(EXTRACTOR.extract(value).isEmpty());
  }

  /**
   * Checks that larger dotted structures are not reduced to three-part suffixes or prefixes.
   *
   * @param continuation The adjoining dotted content.
   */
  @ParameterizedTest
  @ValueSource(strings = {"a", "eyJx", "é", "𐐀", "_", "-", ".part"})
  void testDottedContinuation(String continuation) {
    final String value = token(HEADER);
    Assertions.assertTrue(EXTRACTOR.extract(value + "." + continuation).isEmpty());
    Assertions.assertTrue(EXTRACTOR.extract(continuation + "." + value).isEmpty());
  }

  /**
   * Retains surrounding prose punctuation outside the token span.
   *
   * @param suffix The sentence-ending punctuation.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", ".", "...", ". ", ")", "]", ",", ";", "?", "!"})
  void testPunctuation(String suffix) {
    final String value = token(HEADER);
    Assertions.assertEquals(List.of(new PiiMention(new Span(0, value.length()),
        PiiMention.TYPE_JWT, value)), EXTRACTOR.extract(value + suffix));
  }

  /** Checks the manual example with a spaced JSON header and exact masking span. */
  @Test
  void testManualExample() {
    final String value = "eyAiYWxnIjogIkhTMjU2IiB9.e30.dGVzdA";
    final Document doc = new PiiAnnotator(PiiPacks.secrets()).annotate(Document.of("Bearer " + value));
    Assertions.assertEquals("Bearer " + "*".repeat(value.length()),
        Masker.mask(doc, PiiAnnotator.PII, '*'));
    Assertions.assertEquals(value, doc.get(PiiAnnotator.PII).getFirst().value().normalized());
  }

  /**
   * Checks parser state is local to each concurrent request.
   *
   * @throws Exception If a worker fails or does not finish in time.
   */
  @Test
  @Timeout(60)
  void testConcurrentHeaders() throws Exception {
    final CountDownLatch ready = new CountDownLatch(8);
    final List<Callable<Void>> calls = new ArrayList<>();
    for (int worker = 0; worker < 8; worker++) {
      final String field = "x".repeat(100 + worker);
      final String value = token("{\"kid\":\"" + field + "\",\"alg\":\"HS256\"}");
      calls.add(() -> {
        ready.countDown();
        Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
        for (int run = 0; run < 32; run++) {
          Assertions.assertEquals(1, EXTRACTOR.extract(value).size());
          Assertions.assertTrue(EXTRACTOR.extract(token("{\"alg\":null}")).isEmpty());
        }
        return null;
      });
    }
    try (var executor = Executors.newFixedThreadPool(8)) {
      for (final var result : executor.invokeAll(calls, 30, TimeUnit.SECONDS)) {
        result.get();
      }
    }
  }
}
