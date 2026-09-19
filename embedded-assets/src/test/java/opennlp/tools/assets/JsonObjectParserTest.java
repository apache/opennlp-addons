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

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the dependency-free JSON cursor to the RFC 8259 grammar used by JWTs. */
public class JsonObjectParserTest {

  /** @return Valid complete JSON objects spanning each value production. */
  private static Stream<String> validObjects() {
    return Stream.of(
        "{}",
        " { \"n\" : null, \"b\" : true, \"f\" : false } ",
        "{\"array\":[0,-1,1.25,2e3,{\"nested\":\"yes\"}]}",
        "{\"escaped\":\"quote \\\" slash \\\\ unicode \\u0041\"}");
  }

  /** @return Malformed texts that look close to JSON objects. */
  private static Stream<String> invalidObjects() {
    return Stream.of(
        "",
        "[]",
        "{\"trailing\":true,}",
        "{\"leading\":01}",
        "{\"fraction\":1.}",
        "{\"escape\":\"\\x\"}",
        "{\"unicode\":\"\\uＦＦＦＦ\"}",
        "{\"unterminated\":\"value}",
        "{\"array\":[true,]}",
        "{\"missing\" true}");
  }

  /**
   * Every JSON value production is accepted inside a complete object.
   *
   * @param json The valid object to parse.
   */
  @ParameterizedTest
  @MethodSource("validObjects")
  void testValidObject(String json) {
    assertTrue(JsonObjectParser.parse(json, null).valid(), json);
  }

  /**
   * Common malformed lookalikes fail closed.
   *
   * @param json The malformed object to reject.
   */
  @ParameterizedTest
  @MethodSource("invalidObjects")
  void testInvalidObject(String json) {
    assertFalse(JsonObjectParser.parse(json, null).valid(), json);
  }

  /** Member capture is decoded, top-level only, and duplicate-intolerant. */
  @Test
  void testCapturesOnlyOneTopLevelStringMember() {
    final JsonObjectParser.Result parsed = JsonObjectParser.parse(
        "{\"nested\":{\"alg\":\"wrong\"},\"\\u0061lg\":\"HS256\"}", "alg");

    assertTrue(parsed.valid());
    assertEquals("HS256", parsed.member());
    assertNull(JsonObjectParser.parse("{\"nested\":{\"alg\":\"HS256\"}}", "alg")
        .member());
    assertFalse(JsonObjectParser.parse("{\"alg\":\"HS256\",\"alg\":\"RS256\"}",
        "alg").valid());
  }

  @Test
  void testNestingDepthBoundary() {
    final String permitted = "{\"v\":" + "[".repeat(63) + "0" + "]".repeat(63) + "}";
    final String excessive = "{\"v\":" + "[".repeat(64) + "0" + "]".repeat(64) + "}";
    assertTrue(JsonObjectParser.parse(permitted, null).valid());
    assertFalse(JsonObjectParser.parse(excessive, null).valid());
  }
}
