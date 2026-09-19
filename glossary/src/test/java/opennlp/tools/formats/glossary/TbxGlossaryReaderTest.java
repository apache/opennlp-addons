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

import opennlp.tools.glossary.GlossaryEntry;
import opennlp.tools.util.InvalidFormatException;

/** Tests TBX versions, language selection, entity handling, and entry validation. */
public class TbxGlossaryReaderTest {

  /**
   * Loads English terms and aliases from TBX 2 with an external DTD declaration.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testReadsV2TermEntriesForLanguage() throws IOException {
    final TbxGlossaryReader reader = new TbxGlossaryReader("en");
    final List<GlossaryEntry> entries;
    try (InputStream in = getClass().getResourceAsStream("glossary-v2.tbx")) {
      entries = reader.read(in);
    }

    Assertions.assertEquals(3, entries.size());
    Assertions.assertEquals("c1", entries.get(0).id());
    Assertions.assertEquals("hot dog", entries.get(0).term());
    Assertions.assertEquals("c1", entries.get(1).id());
    Assertions.assertEquals("frankfurter", entries.get(1).term());
    Assertions.assertEquals("c2", entries.get(2).id());
    Assertions.assertEquals("New York City", entries.get(2).term());
  }

  /**
   * Selects German terms from a multilingual file.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testReadsOtherLanguageFromSameFile() throws IOException {
    final TbxGlossaryReader reader = new TbxGlossaryReader("de");
    final List<GlossaryEntry> entries;
    try (InputStream in = getClass().getResourceAsStream("glossary-v2.tbx")) {
      entries = reader.read(in);
    }

    Assertions.assertEquals(1, entries.size());
    Assertions.assertEquals("c1", entries.get(0).id());
    Assertions.assertEquals("Wiener W\u00FCrstchen", entries.get(0).term());
  }

  /**
   * Loads terms and aliases from TBX 3 elements in the ISO 30042 namespace.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testReadsV3ConceptEntries() throws IOException {
    final TbxGlossaryReader reader = new TbxGlossaryReader("en");
    final List<GlossaryEntry> entries;
    try (InputStream in = getClass().getResourceAsStream("glossary-v3.tbx")) {
      entries = reader.read(in);
    }

    Assertions.assertEquals(3, entries.size());
    Assertions.assertEquals("ML", entries.get(0).id());
    Assertions.assertEquals("machine learning", entries.get(0).term());
    Assertions.assertEquals("NN", entries.get(1).id());
    Assertions.assertEquals("neural network", entries.get(1).term());
    Assertions.assertEquals("NN", entries.get(2).id());
    Assertions.assertEquals("neural net", entries.get(2).term());
  }

  /**
   * Matches language tags without case sensitivity and distinguishes regional subtags.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testLanguageMatchingIsCaseInsensitiveAndPrefixBased() throws IOException {
    try (InputStream in = getClass().getResourceAsStream("glossary-v2.tbx")) {
      Assertions.assertEquals(3, new TbxGlossaryReader("EN-us").read(in).size());
    }
    try (InputStream in = getClass().getResourceAsStream("glossary-v2.tbx")) {
      Assertions.assertTrue(new TbxGlossaryReader("en-GB").read(in).isEmpty());
    }
  }

  /**
   * Returns no entries when the requested language is not present.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testAbsentLanguageYieldsEmptyList() throws IOException {
    final TbxGlossaryReader reader = new TbxGlossaryReader("sv");
    try (InputStream in = getClass().getResourceAsStream("glossary-v2.tbx")) {
      Assertions.assertTrue(reader.read(in).isEmpty());
    }
  }

  /**
   * Expands internal entity references in terms.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testInternalDtdEntityResolves() throws IOException {
    final String doc = "<?xml version=\"1.0\"?>"
        + "<!DOCTYPE martif [<!ENTITY co \"Acme Corp\">]>"
        + "<martif type=\"TBX\"><text><body>"
        + "<termEntry id=\"c1\"><langSet xml:lang=\"en\">"
        + "<tig><term>&co; press</term></tig>"
        + "</langSet></termEntry>"
        + "</body></text></martif>";

    final List<GlossaryEntry> entries = new TbxGlossaryReader("en")
        .read(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8)));

    Assertions.assertEquals(1, entries.size());
    Assertions.assertEquals("Acme Corp press", entries.get(0).term());
  }

  /** Rejects an external entity in a term. */
  @Test
  void testExternalEntityFailsClosed() {
    final String doc = "<?xml version=\"1.0\"?>"
        + "<!DOCTYPE martif [<!ENTITY xxe SYSTEM \"file:///nonexistent-entity\">]>"
        + "<martif type=\"TBX\"><text><body>"
        + "<termEntry id=\"c1\"><langSet xml:lang=\"en\">"
        + "<tig><term>&xxe;</term></tig>"
        + "</langSet></termEntry>"
        + "</body></text></martif>";

    Assertions.assertThrows(InvalidFormatException.class, () -> new TbxGlossaryReader("en")
        .read(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8))));
  }

  /** Rejects a term entry without an identifier. */
  @Test
  void testMissingEntryIdFailsLoud() {
    final String doc = "<?xml version=\"1.0\"?>"
        + "<martif type=\"TBX\"><text><body>"
        + "<termEntry><langSet xml:lang=\"en\">"
        + "<tig><term>orphan</term></tig>"
        + "</langSet></termEntry>"
        + "</body></text></martif>";

    final InvalidFormatException ex = Assertions.assertThrows(InvalidFormatException.class,
        () -> new TbxGlossaryReader("en")
            .read(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8))));
    Assertions.assertTrue(ex.getMessage().contains("id"), ex.getMessage());
  }

  /** Rejects a blank term in a selected language. */
  @Test
  void testBlankTermFailsLoud() {
    final String doc = "<?xml version=\"1.0\"?>"
        + "<martif type=\"TBX\"><text><body>"
        + "<termEntry id=\"c1\"><langSet xml:lang=\"en\">"
        + "<tig><term>   </term></tig>"
        + "</langSet></termEntry>"
        + "</body></text></martif>";

    Assertions.assertThrows(InvalidFormatException.class, () -> new TbxGlossaryReader("en")
        .read(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8))));
  }

  /** Reports truncated XML as invalid content. */
  @Test
  void testMalformedXmlFailsAsInvalidFormat() {
    final String doc = "<?xml version=\"1.0\"?><martif type=\"TBX\"><text><body>"
        + "<termEntry id=\"c1\"><langSet xml:lang=\"en\"><tig><term>cut off";

    Assertions.assertThrows(InvalidFormatException.class, () -> new TbxGlossaryReader("en")
        .read(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8))));
  }

  /** Rejects an unsupported root element. */
  @Test
  void testWrongRootElementFailsLoud() {
    final String doc = "<?xml version=\"1.0\"?><html><body><p>not a termbase</p></body></html>";

    final InvalidFormatException ex = Assertions.assertThrows(InvalidFormatException.class,
        () -> new TbxGlossaryReader("en")
            .read(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8))));
    Assertions.assertTrue(ex.getMessage().contains("martif")
        || ex.getMessage().contains("tbx"), ex.getMessage());
  }

  /** Rejects missing language tags and a null input stream. */
  @Test
  void testInvalidArguments() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> new TbxGlossaryReader(null));
    Assertions.assertThrows(IllegalArgumentException.class, () -> new TbxGlossaryReader(""));
    Assertions.assertThrows(IllegalArgumentException.class, () -> new TbxGlossaryReader(" "));
    final TbxGlossaryReader reader = new TbxGlossaryReader("en");
    Assertions.assertThrows(IllegalArgumentException.class, () -> reader.read(null));
  }
}
