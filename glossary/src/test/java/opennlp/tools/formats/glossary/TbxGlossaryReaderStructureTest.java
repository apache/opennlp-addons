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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.glossary.GlossaryEntry;
import opennlp.tools.util.InvalidFormatException;

import static opennlp.tools.formats.glossary.GlossaryTestSupport.utf8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks entry identifiers and the structural scope of extracted terms. */
public class TbxGlossaryReaderStructureTest {

  /** A namespaced metadata attribute cannot supply the term-entry identifier. */
  @Test
  void testNamespacedIdDoesNotReplaceEntryId() {
    assertThrows(InvalidFormatException.class,
        () -> new TbxGlossaryReader("en").read(input("meta:id=\"META\"")));
  }

  /**
   * Selects the unqualified identifier regardless of attribute order.
   *
   * @param attributes The identifier and metadata attributes.
   * @throws IOException If reading fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {"meta:id=\"META\" id=\"Q1\"", "id=\"Q1\" meta:id=\"META\""})
  void testUnqualifiedIdTakesPrecedence(String attributes) throws IOException {
    assertEquals(List.of(new GlossaryEntry("Q1", "term")),
        new TbxGlossaryReader("en").read(input(attributes)));
  }

  /**
   * Ignores metadata that contains term, language, or concept element names.
   *
   * @param metadata The metadata subtree between two actual terms.
   * @throws IOException If reading fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "<note><term>metadata</term></note>",
      "<note><langSet xml:lang='de'/></note>",
      "<note><termEntry id='OTHER'/></note>",
      "<note><langSet xml:lang='en'><tig><term>metadata</term></tig></langSet></note>",
      "<note><termEntry><langSet xml:lang='en'/></termEntry></note>"
  })
  void testMetadataCannotChangeEntryOrLanguage(String metadata) throws IOException {
    final String xml = "<martif><text><body><termEntry id='Q1'><langSet xml:lang='en'>"
        + "<tig><term>first</term></tig>" + metadata + "<tig><term>second</term></tig>"
        + "</langSet></termEntry></body></text></martif>";
    assertEquals(List.of(new GlossaryEntry("Q1", "first"), new GlossaryEntry("Q1", "second")),
        new TbxGlossaryReader("en").read(utf8(xml)));
  }

  /**
   * Reads terms only from the body, not from headers, back matter, or wrappers.
   *
   * @param location The path around an entry, with its content represented by %s.
   * @throws IOException If reading fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "<martifHeader>%s</martifHeader><text><body/></text>",
      "<text><body/><back>%s</back></text>",
      "<text><body><note>%s</note></body></text>",
      "<text>%s</text>",
      "%s"
  })
  void testEntriesOutsideBodyAreIgnored(String location) throws IOException {
    final String entry = "<termEntry id='Q1'><langSet xml:lang='en'>"
        + "<tig><term>metadata</term></tig></langSet></termEntry>";
    final String xml = "<martif>" + location.formatted(entry) + "</martif>";
    assertEquals(List.of(), new TbxGlossaryReader("en").read(utf8(xml)));
  }

  /**
   * Requires a term container instead of accepting terms directly in a language.
   *
   * @param root The document element.
   * @param concept The concept element.
   * @param language The language element.
   * @param container The term container element.
   * @throws IOException If reading fails.
   */
  @ParameterizedTest
  @CsvSource({"martif,termEntry,langSet,tig", "tbx,conceptEntry,langSec,termSec"})
  void testTermContainerRequired(String root, String concept, String language,
      String container) throws IOException {
    final String xml = "<" + root + "><text><body><" + concept + " id='Q1'><" + language
        + " xml:lang='en'><term>metadata</term><" + container + "><term>actual</term></"
        + container + "></" + language + "></" + concept + "></body></text></" + root + ">";
    assertEquals(List.of(new GlossaryEntry("Q1", "actual")),
        new TbxGlossaryReader("en").read(utf8(xml)));
  }

  /**
   * Preserves inline character data in each supported term container.
   *
   * @param container The markup around a term, with its content represented by %s.
   * @throws IOException If reading fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {"<tig>%s</tig>", "<ntig><termGrp>%s</termGrp></ntig>",
      "<termSec>%s</termSec>"})
  void testInlineTermMarkup(String container) throws IOException {
    final String term = "<term>New <hi>York</hi><![CDATA[ City]]></term>";
    final String xml = "<tbx><text><body><conceptEntry id='Q1'><langSec xml:lang='en'>"
        + container.formatted(term) + "</langSec></conceptEntry></body></text></tbx>";
    assertEquals(List.of(new GlossaryEntry("Q1", "New York City")),
        new TbxGlossaryReader("en").read(utf8(xml)));
  }

  /** XML inside ignored metadata must still be well formed. */
  @Test
  void testMalformedIgnoredContentRejected() {
    assertThrows(InvalidFormatException.class, () -> new TbxGlossaryReader("en")
        .read(utf8("<tbx><tbxHeader><note></tbxHeader></tbx>")));
  }

  /**
   * Does not interpret a data category's local name as a TBX structural element.
   *
   * @param metadata The foreign-namespace data category.
   * @throws IOException If reading fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {"<meta:term>metadata</meta:term>",
      "<meta:termSec><term>metadata</term></meta:termSec>",
      "<meta:langSec xml:lang='de'/>", "<meta:conceptEntry id='OTHER'/>"})
  void testForeignMetadataNamesIgnored(String metadata) throws IOException {
    final String xml = "<tbx xmlns='urn:iso:std:iso:30042:ed-2' xmlns:meta='urn:metadata'>"
        + "<text><body><conceptEntry id='Q1'><langSec xml:lang='en'><termSec>"
        + "<term>first</term>" + metadata + "</termSec>" + metadata
        + "<termSec><term>second</term></termSec></langSec></conceptEntry></body></text></tbx>";
    assertEquals(List.of(new GlossaryEntry("Q1", "first"), new GlossaryEntry("Q1", "second")),
        new TbxGlossaryReader("en").read(utf8(xml)));
  }

  /** An unrelated XML vocabulary cannot identify itself as TBX by its local root name. */
  @Test
  void testUnsupportedRootNamespaceRejected() {
    assertThrows(InvalidFormatException.class, () -> new TbxGlossaryReader("en")
        .read(utf8("<tbx xmlns='urn:unrelated'><text><body/></text></tbx>")));
  }

  /**
   * Handles an explicit prefix on the ISO namespace as well as default namespaces.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testPrefixedCoreElements() throws IOException {
    final String xml = "<t:tbx xmlns:t='urn:iso:std:iso:30042:ed-2'><t:text><t:body>"
        + "<t:conceptEntry id='Q1'><t:langSec xml:lang='en'><t:termSec>"
        + "<t:term>actual</t:term></t:termSec></t:langSec></t:conceptEntry>"
        + "</t:body></t:text></t:tbx>";
    assertEquals(List.of(new GlossaryEntry("Q1", "actual")),
        new TbxGlossaryReader("en").read(utf8(xml)));
  }

  /**
   * Builds an entry with a metadata namespace.
   *
   * @param attributes The entry attributes.
   * @return UTF-8 input with one English term.
   */
  private ByteArrayInputStream input(String attributes) {
    final String xml = "<martif><text><body><termEntry xmlns:meta=\"urn:metadata\" "
        + attributes + "><langSet xml:lang=\"en\"><tig><term>term</term></tig>"
        + "</langSet></termEntry></body></text></martif>";
    return utf8(xml);
  }
}
