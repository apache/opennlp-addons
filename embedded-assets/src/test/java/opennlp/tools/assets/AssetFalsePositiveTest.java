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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Holds the cursor detector to a corpus of asset-shaped near misses. These are common
 * identifiers, transport fragments, malformed envelopes, and dotted tokens that must
 * remain ordinary text even as the accepted encoding families grow.
 */
public class AssetFalsePositiveTest {

  private static final CursorAssetDetector DETECTOR = new CursorAssetDetector();

  private static final String[] NEAR_MISSES = {
      "release 3.0.0 is available",
      "request id 550e8400-e29b-41d4-a716-446655440000",
      "GET /9j/abc/thumbnail HTTP/1.1",
      "sha256 d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2",
      "AlphanumericRunsLikeThisOne0123456789 are prose",
      "eyJ0eXAiOiJKV1QifQ.eyJzdWIiOiIxMjMifQ.signature",
      "e25vdCBqc29ufQ.eyJzdWIiOiIxMjMifQ.c2ln",
      "-----BEGIN CERTIFICATE-----\nnot base64!\n-----END CERTIFICATE-----",
      "-----BEGIN PUBLIC KEY-----\nQUJD\n-----END PRIVATE KEY-----",
      "data:text/plain,SGVsbG8=",
      "mailto:data:image/png;base64,not-an-image",
      "the token a.b.c is punctuation"
  };

  /** @return The near-miss fixtures, one per parameterized invocation. */
  private static List<String> nearMisses() {
    return List.of(NEAR_MISSES);
  }

  /**
   * Each asset-shaped near miss remains ordinary text in isolation.
   *
   * @param fixture The near miss to scan.
   */
  @ParameterizedTest
  @MethodSource("nearMisses")
  void testNearMissYieldsNothing(String fixture) {
    assertEquals(List.of(), DETECTOR.detect(fixture), fixture);
  }

  /** Corpus seams cannot combine two near misses into a finding. */
  @Test
  void testJoinedCorpusYieldsNothing() {
    assertEquals(List.of(), DETECTOR.detect(String.join(" | ", NEAR_MISSES)));
  }

  /** The corpus is not green because the new transport detectors are silent. */
  @Test
  void testControlTextStillFindsEachNewEncodingFamily() {
    final String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
    final String claims = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"sub\":\"123\"}".getBytes(StandardCharsets.UTF_8));
    final String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(
        new byte[32]);
    final String pemBody = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
        .encodeToString(new byte[96]);
    final String control = header + "." + claims + "." + signature + "\n"
        + "-----BEGIN CERTIFICATE-----\n" + pemBody + "\n-----END CERTIFICATE-----";

    assertEquals(2, DETECTOR.detect(control).size());
  }
}
