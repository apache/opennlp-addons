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

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks bounded decoding against original bytes encoded by the JDK. */
class Base64PayloadInputStreamTest {

  /** {@return payload lengths, transports and decoded byte limits} */
  private static Stream<Arguments> payloads() {
    return IntStream.of(0, 1, 2, 3, 4, 5, 23, 24, 25, 63, 64, 65, 256, 1024).boxed()
        .flatMap(length -> Stream.of("standard", "url", "mime64", "mime76")
            .flatMap(transport -> IntStream.of(0, 1, Math.max(0, length - 1), length, Integer.MAX_VALUE)
                .distinct().mapToObj(limit -> Arguments.of(length, transport, limit))));
  }

  /**
   * Reads the requested prefix without interpreting surrounding source text.
   *
   * @param length The original byte count.
   * @param transport The base64 alphabet and wrapping.
   * @param limit The maximum decoded byte count.
   * @throws IOException If the inherited bulk-read operation fails.
   */
  @ParameterizedTest
  @MethodSource("payloads")
  void testDecodedPrefix(int length, String transport, int limit) throws IOException {
    final byte[] expected = new byte[length];
    for (int i = 0; i < length; i++) {
      expected[i] = (byte) (i * 73 + 19);
    }
    final String encoded = AssetTestSupport.encode(expected, transport);
    final String before = "Other payload: ";
    final String text = before + encoded + " /AAAA";
    final Base64PayloadInputStream input = new Base64PayloadInputStream(
        text, before.length(), before.length() + encoded.length(), limit);
    assertArrayEquals(Arrays.copyOf(expected, Math.min(length, limit)), input.readAllBytes());
    assertEquals(-1, input.read());
    assertEquals(-1, input.read());
  }
}
