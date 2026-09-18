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

import java.util.ArrayList;
import java.util.Collections;
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

/** Checks network address delimiters, reserved values and normalized forms. */
class NetworkAddressFormsTest {

  private static final String IPV4 = "192.0.2.1";
  private static final String IPV6 = "2001:db8::";
  private static final String MAC = "02:1b:44:11:3a:b7";
  private static final NetworkPiiExtractor ALL = new NetworkPiiExtractor();
  private static final NetworkPiiExtractor IPV6_ONLY =
      new NetworkPiiExtractor(Set.of(PiiMention.TYPE_IPV6));

  /**
   * Provides punctuation and whitespace after a trailing compressed IPv6 group.
   *
   * @return The text after the address.
   */
  private static Stream<String> endings() {
    return Stream.of("", " ", "\n", "\t", ".", ",", ";", ")", "]", "}", "!", "?",
        "\"", "'", "/64", "%eth0", "😀", "\u00a0", "...", ".. ");
  }

  /**
   * Checks that a trailing compression marker is included before surrounding punctuation.
   *
   * @param suffix The text after the address.
   */
  @ParameterizedTest
  @MethodSource("endings")
  void testTrailingCompression(String suffix) {
    final String prefix = "😀 [";
    Assertions.assertEquals(List.of(new PiiMention(
        new Span(prefix.length(), prefix.length() + IPV6.length()), PiiMention.TYPE_IPV6, IPV6)),
        IPV6_ONLY.extract(prefix + IPV6 + suffix));
  }

  /**
   * Checks malformed dotted continuations and incorrect embedded IPv4 forms.
   *
   * @param text The invalid IPv6 candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {"2001:db8::192.0.2.1.5", "2001:db8::192.0.2.256",
      "2001:db8::192.0.2.01", "2001:db8::192.0.2", "2001:db8::192..2.1",
      "2001:db8::1.example", "2001:db8::1.2", "2001:db8::1.é", "2001:db8::1.𐐀",
      "::ffff:192.0.2.1.5", "::ffff:192.0.2.999", "2001:db8::192.0.2.1:5",
      "2001:db8:::1", "2001:db8:::", "2001:db8::x", "2001:db8::é", "2001:db8::𐐀"})
  void testInvalidIpv6Continuation(String text) {
    Assertions.assertTrue(IPV6_ONLY.extract(text).isEmpty());
  }

  /**
   * Provides structured address endings with Unicode continuation characters.
   *
   * @return The type, address, delimiter and trailing character.
   */
  private static Stream<Arguments> structuredEndings() {
    final List<Arguments> cases = new ArrayList<>();
    for (final String next : List.of("a", "1", "é", "中", "𐐀", "١", "𝟙")) {
      cases.add(Arguments.of(PiiMention.TYPE_IPV4, IPV4, ".", next));
      cases.add(Arguments.of(PiiMention.TYPE_IPV6, IPV6 + "1", ".", next));
      cases.add(Arguments.of(PiiMention.TYPE_MAC, MAC, ":", next));
      cases.add(Arguments.of(PiiMention.TYPE_MAC, "02-1b-44-11-3a-b7", "-", next));
      cases.add(Arguments.of(PiiMention.TYPE_MAC, "021b.4411.3ab7", ".", next));
      cases.add(Arguments.of(PiiMention.TYPE_IPV4, IPV4, "..", next));
      cases.add(Arguments.of(PiiMention.TYPE_IPV6, IPV6 + "1", "..", next));
      cases.add(Arguments.of(PiiMention.TYPE_MAC, MAC, "::", next));
      cases.add(Arguments.of(PiiMention.TYPE_MAC, "02-1b-44-11-3a-b7", "--", next));
      cases.add(Arguments.of(PiiMention.TYPE_MAC, "021b.4411.3ab7", "..", next));
    }
    return cases.stream();
  }

  /**
   * Rejects address prefixes inside a longer structured token.
   *
   * @param type The address type.
   * @param address The candidate prefix.
   * @param separator The continuing delimiter.
   * @param next The character after that delimiter.
   */
  @ParameterizedTest
  @MethodSource("structuredEndings")
  void testStructuredEnding(String type, String address, String separator, String next) {
    Assertions.assertTrue(new NetworkPiiExtractor(Set.of(type))
        .extract(address + separator + next).isEmpty());
  }

  /**
   * Provides prefixes of larger dotted names and numeric values.
   *
   * @return The type, candidate address and preceding text.
   */
  private static Stream<Arguments> dottedPrefixes() {
    final List<Arguments> cases = new ArrayList<>();
    for (final String prefix : List.of("host.", "a.", "1.", "é.", "中.", "𐐀.", "١.", "𝟙.",
        "host..", "𐐀..", "1..")) {
      cases.add(Arguments.of(PiiMention.TYPE_IPV4, IPV4, prefix));
      cases.add(Arguments.of(PiiMention.TYPE_IPV6, IPV6 + "1", prefix));
      cases.add(Arguments.of(PiiMention.TYPE_MAC, "021b.4411.3ab7", prefix));
    }
    return cases.stream();
  }

  /**
   * Rejects addresses inside larger dotted words or numeric values.
   *
   * @param type The address type.
   * @param address The candidate suffix.
   * @param prefix The preceding dotted group.
   */
  @ParameterizedTest
  @MethodSource("dottedPrefixes")
  void testDottedPrefix(String type, String address, String prefix) {
    Assertions.assertTrue(new NetworkPiiExtractor(Set.of(type)).extract(prefix + address).isEmpty());
  }

  /**
   * Checks exclusion of every written form of the unspecified IPv6 address.
   *
   * @param text The unspecified address.
   */
  @ParameterizedTest
  @ValueSource(strings = {"::", "0:0:0:0:0:0:0:0", "0000:0000:0000:0000:0000:0000:0000:0000",
      "0:0:0:0:0:0:0.0.0.0", "0:0:0:0:0:0.0.0.0", "::0.0.0.0", "000:000::0"})
  void testUnspecifiedIpv6(String text) {
    Assertions.assertTrue(IPV6_ONLY.extract(text).isEmpty());
  }

  /**
   * Provides all patterns of zero and non-zero groups in an expanded IPv6 address.
   *
   * @return The zero-group bit mask.
   */
  private static IntStream zeroMasks() {
    return IntStream.range(0, 256);
  }

  /**
   * Checks lowercase output, single-zero handling and longest-run ties independently.
   *
   * @param mask The zero-group positions.
   */
  @ParameterizedTest
  @MethodSource("zeroMasks")
  void testIpv6ZeroRuns(int mask) {
    final List<String> groups = new ArrayList<>();
    final List<String> expanded = new ArrayList<>();
    final StringBuilder pattern = new StringBuilder();
    for (int index = 0; index < 8; index++) {
      final boolean zero = (mask & (1 << index)) != 0;
      groups.add(zero ? "0" : "ab" + index);
      expanded.add(zero ? "0000" : "0AB" + index);
      pattern.append(zero ? '0' : 'x');
    }
    String normalized = String.join(":", groups);
    for (int length = 8; length >= 2; length--) {
      final int start = pattern.indexOf("0".repeat(length));
      if (start >= 0) {
        normalized = String.join(":", groups.subList(0, start)) + "::"
            + String.join(":", groups.subList(start + length, groups.size()));
        break;
      }
    }
    final String source = String.join(":", expanded);
    final List<PiiMention> expected = mask == 255 ? List.of()
        : List.of(new PiiMention(new Span(0, source.length()), PiiMention.TYPE_IPV6, normalized));
    Assertions.assertEquals(expected, IPV6_ONLY.extract(source));
  }

  /**
   * Provides octet boundary and leading-zero checks at each IPv4 position.
   *
   * @return The octet position, spelling and expected acceptance.
   */
  private static Stream<Arguments> octets() {
    final List<Arguments> cases = new ArrayList<>();
    for (int position = 0; position < 4; position++) {
      for (final String value : List.of("0", "1", "9", "10", "99", "100", "254", "255")) {
        cases.add(Arguments.of(position, value, true));
      }
      for (final String value : List.of("00", "01", "001", "256", "999", "1000", "١", "𝟙")) {
        cases.add(Arguments.of(position, value, false));
      }
    }
    return cases.stream();
  }

  /**
   * Checks decimal octet limits without recognizing a suffix of a malformed address.
   *
   * @param position The octet position.
   * @param value The proposed octet.
   * @param accepted Whether the octet is valid.
   */
  @ParameterizedTest
  @MethodSource("octets")
  void testIpv4Octet(int position, String value, boolean accepted) {
    final List<String> groups = new ArrayList<>(List.of("192", "0", "2", "1"));
    groups.set(position, value);
    final String source = String.join(".", groups);
    final List<PiiMention> expected = accepted
        ? List.of(new PiiMention(new Span(0, source.length()), PiiMention.TYPE_IPV4, source)) : List.of();
    Assertions.assertEquals(expected,
        new NetworkPiiExtractor(Set.of(PiiMention.TYPE_IPV4)).extract(source));
  }

  /**
   * Checks that documented short-form false-positive filters stay unchanged.
   *
   * @param text The filtered IPv6 form.
   */
  @ParameterizedTest
  @ValueSource(strings = {"::1", "a::b", "aa::bb", "2001::", "::abcd"})
  void testShortFormPolicy(String text) {
    Assertions.assertTrue(IPV6_ONLY.extract(text).isEmpty());
  }

  /**
   * Keeps non-zero IPv6 values even when their embedded IPv4 portion is zero.
   *
   * @param text The non-zero address.
   */
  @ParameterizedTest
  @ValueSource(strings = {"::ffff:0.0.0.0", "2001:db8::0.0.0.0",
      "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"})
  void testNonZeroIpv6(String text) {
    final List<PiiMention> hits = IPV6_ONLY.extract(text);
    Assertions.assertEquals(1, hits.size());
    Assertions.assertEquals(new Span(0, text.length()), hits.getFirst().span());
  }

  /**
   * Checks the maximum expanded group count and rejection of long grouped input.
   *
   * @param count The number of hexadecimal groups.
   */
  @ParameterizedTest
  @ValueSource(ints = {8, 9, 64, 4096})
  void testIpv6GroupLimit(int count) {
    final String text = String.join(":", Collections.nCopies(count, "abcd"));
    Assertions.assertEquals(count == 8 ? 1 : 0, IPV6_ONLY.extract(text).size());
  }

  /** Checks the manual example's normalized values and original-text spans. */
  @Test
  void testDocumentExample() {
    final Document result = new PiiAnnotator(PiiPacks.network()).annotate(Document.of(
        "Peer [2001:db8::], host 192.0.2.1, MAC 02-1B-44-11-3A-B7."));
    final var layer = result.get(PiiAnnotator.PII);
    Assertions.assertEquals(List.of(IPV6, IPV4, MAC),
        layer.stream().map(a -> a.value().normalized()).toList());
    Assertions.assertEquals("Peer [**********], host *********, MAC *****************.",
        Masker.mask(result, PiiAnnotator.PII, '*'));
  }

  /**
   * Checks shared parsing state across concurrent IPv4, IPv6 and MAC requests.
   *
   * @throws Exception If a worker fails or does not finish in time.
   */
  @Test
  @Timeout(60)
  void testConcurrentExtraction() throws Exception {
    final CountDownLatch ready = new CountDownLatch(8);
    final List<Callable<Void>> calls = new ArrayList<>();
    for (int worker = 0; worker < 8; worker++) {
      final String prefix = "😀".repeat(worker) + " [";
      calls.add(() -> {
        ready.countDown();
        Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
        for (int run = 0; run < 32; run++) {
          final String text = prefix + IPV6 + "] " + IPV4 + " " + MAC;
          final List<PiiMention> hits = ALL.extract(text);
          Assertions.assertEquals(List.of(IPV6, IPV4, MAC),
              hits.stream().map(PiiMention::normalized).toList());
          Assertions.assertEquals(new Span(prefix.length(), prefix.length() + IPV6.length()),
              hits.getFirst().span());
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
