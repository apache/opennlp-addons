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

package opennlp.tools.formats.glossary;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.glossary.AhoCorasickGlossaryMatcher;
import opennlp.tools.glossary.GlossaryEntry;
import opennlp.tools.glossary.GlossaryMatch;
import opennlp.tools.util.Span;

/** Verifies the manual's glossary-loading and matching examples. */
public class GlossaryLoadingExampleTest {

  /**
   * Loads English terms from TBX and matches an alias to a concept identifier.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testTbxManualExample() throws IOException {
    final TbxGlossaryReader reader = new TbxGlossaryReader("en");
    final List<GlossaryEntry> glossary;
    try (InputStream in = getClass().getResourceAsStream("glossary-v2.tbx")) {
      glossary = reader.read(in);
    }

    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(glossary, true);
    final String text = "A frankfurter, please.";
    final List<GlossaryMatch> hits = matcher.match(text);

    Assertions.assertEquals(1, hits.size());
    Assertions.assertEquals("c1", hits.get(0).id());
    Assertions.assertEquals("frankfurter",
        text.substring(hits.get(0).span().getStart(), hits.get(0).span().getEnd()));
  }

  /**
   * Loads a CSV term list with a header and matches the listed places.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testCsvManualExample() throws IOException {
    final String csv = "id,term\nQ60,New York City\nQ11299,Manhattan\n";
    final CsvGlossaryReader reader = new CsvGlossaryReader(',', true);
    final List<GlossaryEntry> glossary =
        reader.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(glossary, true);
    final List<GlossaryMatch> hits = matcher.match("From Manhattan to New York City.");

    Assertions.assertEquals(2, hits.size());
    Assertions.assertEquals("Q11299", hits.get(0).id());
    Assertions.assertEquals("Q60", hits.get(1).id());
  }

  /**
   * Loads a quoted term and an alias with the same identifier.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testQuotedAliasExample() throws IOException {
    final String csv = "id,term\nNY,\"New York, NY\"\nNY,NYC\n";
    final List<GlossaryEntry> glossary = new CsvGlossaryReader(',', true)
        .read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(glossary, true);
    final List<GlossaryMatch> hits = matcher.match("Visit New York, NY or NYC.");

    Assertions.assertEquals(List.of(
        new GlossaryMatch(new Span(6, 18), "NY", "New York, NY"),
        new GlossaryMatch(new Span(22, 25), "NY", "NYC")), hits);
  }

  /**
   * Loads inline TBX markup as term text and matches it in the original input.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testInlineTbxExample() throws IOException {
    final String tbx = """
        <tbx xmlns="urn:iso:std:iso:30042:ed-2">
          <text><body><conceptEntry id="NY">
            <langSec xml:lang="en"><termSec>
              <term>New <hi>York</hi></term>
              <note>Place name</note>
            </termSec></langSec>
          </conceptEntry></body></text>
        </tbx>
        """;
    final List<GlossaryEntry> glossary = new TbxGlossaryReader("en").read(
        new ByteArrayInputStream(tbx.getBytes(StandardCharsets.UTF_8)));
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(glossary, true);
    final List<GlossaryMatch> hits = matcher.match("From New York to Boston.");

    Assertions.assertEquals(List.of(new GlossaryMatch(new Span(5, 13), "NY", "New York")), hits);
  }
}
