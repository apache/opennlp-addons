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

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

public class NetworkPiiExtractorTest {

  private final NetworkPiiExtractor extractor = new NetworkPiiExtractor();

  /**
   * Checks accepted IPv4 values and exact spans.
   *
   * @param text The candidate text.
   * @param normalized The expected dotted decimal form.
   */
  @ParameterizedTest
  @CsvSource({
      "192.168.1.1, 192.168.1.1",
      "10.0.0.1, 10.0.0.1",
      "8.8.8.8, 8.8.8.8",
      "1.2.3.4, 1.2.3.4",
      "203.0.113.195, 203.0.113.195",
      "255.255.255.0, 255.255.255.0",
      "0.0.0.1, 0.0.0.1",
      "127.0.0.1, 127.0.0.1",
      "100.64.0.1, 100.64.0.1",
      "172.16.254.1, 172.16.254.1"
  })
  void testAcceptsIpv4Addresses(String text, String normalized) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_IPV4, mentions.get(0).type());
    Assertions.assertEquals(normalized, mentions.get(0).normalized());
    Assertions.assertEquals(0, mentions.get(0).span().getStart());
    Assertions.assertEquals(text.length(), mentions.get(0).span().getEnd());
  }

  /**
   * Checks invalid octets, lengths, boundaries and reserved IPv4 values.
   *
   * @param text The rejected form.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "256.1.1.1",
      "1.256.1.1",
      "1.1.1.256",
      "300.300.300.300",
      "1.2.3",
      "1.2.3.4.5",
      "1.2.3.4.5.6",
      "01.2.3.4",
      "1.02.3.4",
      "1.2.3.04",
      "1.2.3.4444",
      "1234.1.1.1",
      "0.0.0.0",
      "255.255.255.255",
      "1.2.3.-4",
      "1..2.3",
      "a1.2.3.4",
      "1.2.3.4a",
      "v1.2.3.4",
      "10.0.0.1.example.com"})
  void testRejectsIpv4NearMisses(String text) {
    Assertions.assertTrue(
        extractor.extract(text).stream()
            .noneMatch(m -> PiiMention.TYPE_IPV4.equals(m.type())), text);
  }

  /** Checks the original IPv4 span in surrounding prose. */
  @Test
  void testIpv4SpanInSentence() {
    final String text = "The host at 192.0.2.44, port 8080, timed out.";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals("192.0.2.44", text.substring(
        mentions.get(0).span().getStart(), mentions.get(0).span().getEnd()));
  }

  /** Checks consecutive addresses separated by whitespace. */
  @Test
  void testTwoAdjacentIpv4AddressesAreBothFound() {
    final String text = "route 10.1.2.3 10.1.2.4";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(2, mentions.size());
    Assertions.assertEquals("10.1.2.3", mentions.get(0).normalized());
    Assertions.assertEquals("10.1.2.4", mentions.get(1).normalized());
  }

  /** Checks that a CIDR prefix length remains outside the address span. */
  @Test
  void testIpv4WithCidrPrefixReportsTheAddressOnly() {
    final String text = "block 198.51.100.14/24 now";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals("198.51.100.14", mentions.get(0).normalized());
  }

  /**
   * Checks accepted IPv6 forms, normalized groups and exact retained mentions.
   *
   * @param text The candidate text.
   * @param normalized The expected hexadecimal form.
   */
  @ParameterizedTest
  @CsvSource({
      "2001:0db8:0000:0000:0000:ff00:0042:8329, 2001:db8::ff00:42:8329",
      "2001:db8:0:0:0:ff00:42:8329, 2001:db8::ff00:42:8329",
      "2001:db8::ff00:42:8329, 2001:db8::ff00:42:8329",
      "fe80::1, fe80::1",
      "2001:db8::, 2001:db8::",
      "fd00:1234:5678:9abc:def0:1234:5678:9abc, fd00:1234:5678:9abc:def0:1234:5678:9abc",
      "1:2:3:4:5:6:7:8, 1:2:3:4:5:6:7:8",
      "2001:DB8::1, 2001:db8::1",
      "::ffff:192.0.2.128, ::ffff:c000:280",
      "::192.0.2.128, ::c000:280",
      "2001:db8::192.0.2.128, 2001:db8::c000:280",
      "2606:4700:4700::1111, 2606:4700:4700::1111",
      "fe80::0202:b3ff:fe1e:8329, fe80::202:b3ff:fe1e:8329"
  })
  void testAcceptsIpv6Addresses(String text, String normalized) {
    final List<PiiMention> mentions = extractor.extract(text);

    final PiiMention ipv6 = new PiiMention(new Span(0, text.length()),
        PiiMention.TYPE_IPV6, normalized);
    final int ipv4Start = text.indexOf("192.0.2.128");
    final List<PiiMention> expected = ipv4Start < 0 ? List.of(ipv6) : List.of(ipv6,
        new PiiMention(new Span(ipv4Start, text.length()), PiiMention.TYPE_IPV4,
            "192.0.2.128"));
    Assertions.assertEquals(expected, mentions, text);
  }

  /**
   * Checks equivalent forms of the same IPv6 value.
   *
   * @param form The alternate form.
   */
  @ParameterizedTest
  @ValueSource(strings = {"2001:0db8:0000:0000:0000:0000:0000:0001", "2001:db8:0:0:0:0:0:1",
      "2001:db8::0:1", "2001:db8::1", "2001:0DB8::1"})
  void testEquivalentIpv6FormsShareOneNormalizedForm(String form) {
    final List<PiiMention> mentions = extractor.extract(form);
    Assertions.assertEquals(1, mentions.size(), form);
    Assertions.assertEquals("2001:db8::1", mentions.get(0).normalized(), form);
  }

  /**
   * Checks malformed IPv6 and the detector's short-form exclusions.
   *
   * @param text The rejected form.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "12:34:56",
      "10:30",
      "23:59:59",
      "a::b",
      "aa::bb",
      "::1",
      "::",
      "Foo::bar",
      "std::vector",
      "1:2:3:4:5:6:7:8:9",
      "2001:db8:::1",
      "2001:db8::1::2",
      "12345::1",
      "gggg::1",
      "2001:db8:0:0:0:0:0:0:1",
      "x2001:db8::1"})
  void testRejectsIpv6NearMisses(String text) {
    Assertions.assertTrue(
        extractor.extract(text).stream()
            .noneMatch(m -> PiiMention.TYPE_IPV6.equals(m.type())), text);
  }

  /** Checks the original IPv6 span in surrounding prose. */
  @Test
  void testIpv6SpanInSentence() {
    final String text = "Peer 2001:db8::dead:beef went away.";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals("2001:db8::dead:beef", text.substring(
        mentions.get(0).span().getStart(), mentions.get(0).span().getEnd()));
  }

  /**
   * Checks accepted MAC groupings, normalized pairs and exact spans.
   *
   * @param text The candidate text.
   * @param normalized The expected colon-separated form.
   */
  @ParameterizedTest
  @CsvSource({
      "00:1B:44:11:3A:B7, 00:1b:44:11:3a:b7",
      "00:1b:44:11:3a:b7, 00:1b:44:11:3a:b7",
      "00-1B-44-11-3A-B7, 00:1b:44:11:3a:b7",
      "001b.4411.3ab7, 00:1b:44:11:3a:b7",
      "3C:5A:B4:00:00:01, 3c:5a:b4:00:00:01",
      "de:ad:be:ef:00:01, de:ad:be:ef:00:01",
      "DE-AD-BE-EF-00-01, de:ad:be:ef:00:01",
      "0800.2b01.0203, 08:00:2b:01:02:03"
  })
  void testAcceptsMacAddresses(String text, String normalized) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_MAC, mentions.get(0).type(), text);
    Assertions.assertEquals(normalized, mentions.get(0).normalized(), text);
    Assertions.assertEquals(0, mentions.get(0).span().getStart());
    Assertions.assertEquals(text.length(), mentions.get(0).span().getEnd());
  }

  /**
   * Checks malformed groupings, invalid digits and reserved MAC values.
   *
   * @param text The rejected form.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "00:1b:44:11:3a",
      "00:1b:44:11:3a:b7:c9",
      "00:1b:44:11:3a:bg",
      "00:1b-44:11:3a:b7",
      "001:b44:113:ab7:00:11",
      "00:00:00:00:00:00",
      "ff:ff:ff:ff:ff:ff",
      "FF-FF-FF-FF-FF-FF",
      "001b.4411.3ab",
      "001b.4411.3ab7.1234",
      "z00:1b:44:11:3a:b7",
      "00:1b:44:11:3a:b77"})
  void testRejectsMacNearMisses(String text) {
    Assertions.assertTrue(
        extractor.extract(text).stream()
            .noneMatch(m -> PiiMention.TYPE_MAC.equals(m.type())), text);
  }

  /** Checks that the detector does not extract a suffix of a longer address. */
  @Test
  void testMacIsNotReportedFromInsideALongerAddress() {
    final String text = "aa:00:1b:44:11:3a:b7";
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /** Checks ordered, disjoint spans across all supported types. */
  @Test
  void testFindsAllThreeTypesInOneLogLine() {
    final String text = "host 10.1.2.3 mac 00:1b:44:11:3a:b7 peer 2001:db8::1 done";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(3, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_IPV4, mentions.get(0).type());
    Assertions.assertEquals(PiiMention.TYPE_MAC, mentions.get(1).type());
    Assertions.assertEquals(PiiMention.TYPE_IPV6, mentions.get(2).type());
    int lastEnd = 0;
    for (final PiiMention mention : mentions) {
      Assertions.assertTrue(mention.span().getStart() >= lastEnd);
      lastEnd = mention.span().getEnd();
    }
  }

  /** Checks type selection without changing recognition rules. */
  @Test
  void testTypeSubsetLimitsWhatIsReported() {
    final String text = "host 10.1.2.3 mac 00:1b:44:11:3a:b7 peer 2001:db8::1";

    Assertions.assertEquals(List.of(PiiMention.TYPE_IPV4),
        new NetworkPiiExtractor(Set.of(PiiMention.TYPE_IPV4)).extract(text)
            .stream().map(PiiMention::type).toList());
    Assertions.assertEquals(List.of(PiiMention.TYPE_MAC),
        new NetworkPiiExtractor(Set.of(PiiMention.TYPE_MAC)).extract(text)
            .stream().map(PiiMention::type).toList());
    Assertions.assertEquals(List.of(PiiMention.TYPE_IPV6),
        new NetworkPiiExtractor(Set.of(PiiMention.TYPE_IPV6)).extract(text)
            .stream().map(PiiMention::type).toList());
  }

  /**
   * Checks ordinary text without matching network addresses.
   *
   * @param text The input without a match.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "no address here",
      "version 3.9 released",
      "call (555) 123-4567",
      "jane@example.com",
      "",
      "1,234.56"})
  void testTextWithoutAnAddressYieldsNoMention(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /** Checks constructor and extraction argument validation. */
  @Test
  void testRejectsUnrecognizedTypeAndMissingArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new NetworkPiiExtractor(Set.of(PiiMention.TYPE_EMAIL)));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new NetworkPiiExtractor(Set.of()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new NetworkPiiExtractor(null));
    Assertions.assertThrows(IllegalArgumentException.class, () -> extractor.extract(null));
  }

  /** Checks original-text offsets after supplementary characters. */
  @Test
  void testSurrogatePairsDoNotShiftSpans() {
    final String text = "\uD83D\uDE00 host 192.0.2.1 \uD83D\uDE00";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals("192.0.2.1", text.substring(
        mentions.get(0).span().getStart(), mentions.get(0).span().getEnd()));
  }
}
