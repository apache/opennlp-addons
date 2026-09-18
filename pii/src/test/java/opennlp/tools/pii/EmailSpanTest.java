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
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Checks email offsets at punctuation and Unicode text boundaries. */
class EmailSpanTest {

  private static final String ADDRESS = "User+tag@Example.COM";
  private static final String NORMALIZED = "User+tag@example.com";
  private static final String PREFIX = "Contact: ";
  private final PiiExtractor extractor = new CursorPiiExtractor(Set.of(PiiMention.TYPE_EMAIL));

  /**
   * Provides Unicode letters and digits adjacent to domain punctuation.
   *
   * @return The characters following the ASCII domain.
   */
  private static Stream<Arguments> domainContinuations() {
    return Stream.of("", ".", "-", "..", "--", ".-", "-.").flatMap(separator ->
        Stream.of("é", "\u0661", "\uD801\uDC00", "\uD835\uDFD8")
            .map(character -> Arguments.of(separator + character)));
  }

  /**
   * Rejects partial domains continued by Unicode letters or digits.
   *
   * @param continuation The domain continuation.
   */
  @ParameterizedTest
  @MethodSource("domainContinuations")
  void testRejectsPartialDomain(String continuation) {
    final String text = PREFIX + ADDRESS + continuation;
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
    Assertions.assertTrue(extractor.extract(new StringBuilder(text)).isEmpty(), text);
  }

  /**
   * Recognizes complete addresses followed by punctuation or separated text.
   *
   * @param suffix The text outside the email span.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", ".", "-", "..", "--", ".-", "-.", ". é", "- é",
      ". \u0661", ".\uD83D\uDE00", "-\uD83D\uDE00", ", next", "; next"})
  void testExcludesTrailingPunctuation(String suffix) {
    assertCompleteAddress(PREFIX, suffix);
  }

  /**
   * Provides wrappers and field separators used around email addresses.
   *
   * @return The text before and after an address.
   */
  private static Stream<Arguments> contexts() {
    return Stream.of(
        Arguments.of("<", ">"),
        Arguments.of("(", ")"),
        Arguments.of("\"", "\""),
        Arguments.of("'", "'"),
        Arguments.of("email=", ""),
        Arguments.of("mailto:", ""),
        Arguments.of("/contacts/", ""),
        Arguments.of("\uD83D\uDE00 ", "!"),
        Arguments.of("e\u0301 ", "."),
        Arguments.of("\t", "\r\n"));
  }

  /**
   * Preserves wrappers, whitespace and field names during masking and replacement.
   *
   * @param prefix The text before the address.
   * @param suffix The text after the address.
   */
  @ParameterizedTest
  @MethodSource("contexts")
  void testContextOffsets(String prefix, String suffix) {
    assertCompleteAddress(prefix, suffix);
  }

  /**
   * Rejects Unicode letters and digits directly before the local part.
   *
   * @param prefix The character attached to the local part.
   */
  @ParameterizedTest
  @ValueSource(strings = {"é", "\u0661", "\uD801\uDC00", "\uD835\uDFD8"})
  void testRejectsPartialLocalPart(String prefix) {
    Assertions.assertTrue(extractor.extract(prefix + ADDRESS).isEmpty());
  }

  /** Checks extraction after a rejected domain in the manual's example. */
  @Test
  void testContinuesAfterRejectedDomain() {
    final String text = "Skip user@example.com.é; contact User+tag@Example.COM.";
    final Document document = new PiiAnnotator(PiiPacks.contact()).annotate(Document.of(text));
    final int start = text.indexOf(ADDRESS);

    Assertions.assertEquals(List.of(new PiiMention(new Span(start, start + ADDRESS.length()),
        PiiMention.TYPE_EMAIL, NORMALIZED)), extractor.extract(text));
    Assertions.assertEquals("Skip user@example.com.é; contact "
        + "*".repeat(ADDRESS.length()) + ".", Masker.mask(document, PiiAnnotator.PII, '*'));
    Assertions.assertEquals("Skip user@example.com.é; contact EMAIL-1.",
        new Pseudonymizer().rewrite(document).text());
  }

  /**
   * Checks extraction, normalization, masking and replacement offsets.
   *
   * @param prefix The text before the address.
   * @param suffix The text after the address.
   */
  private void assertCompleteAddress(String prefix, String suffix) {
    final String text = prefix + ADDRESS + suffix;
    final PiiMention expected = new PiiMention(
        new Span(prefix.length(), prefix.length() + ADDRESS.length()),
        PiiMention.TYPE_EMAIL, NORMALIZED);
    Assertions.assertEquals(List.of(expected), extractor.extract(text));
    Assertions.assertEquals(List.of(expected), extractor.extract(new StringBuilder(text)));

    final Document document = new PiiAnnotator(extractor).annotate(Document.of(text));
    Assertions.assertEquals(List.of(expected), document.get(PiiAnnotator.PII).stream()
        .map(annotation -> annotation.value()).toList());
    Assertions.assertEquals(prefix + "*".repeat(ADDRESS.length()) + suffix,
        Masker.mask(document, PiiAnnotator.PII, '*'));
    Assertions.assertEquals(prefix + "EMAIL-1" + suffix, new Pseudonymizer().rewrite(document).text());
    Assertions.assertEquals(text, document.text());
  }
}
