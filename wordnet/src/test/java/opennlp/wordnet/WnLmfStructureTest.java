/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package opennlp.wordnet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.wordnet.Synset;
import opennlp.tools.wordnet.WordNetPOS;
import opennlp.tools.wordnet.WordNetRelation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests element ownership and skipped subtrees in ordinary and extended WN-LMF. */
class WnLmfStructureTest {

  private static final String RESOURCE_NAME = "structure.xml";
  private static final String EXTENDS = "<Extends ref=\"ewn\" version=\"2020\"/>";
  private static final String NEW_SYNSET = "<Synset id=\"ext-new\" partOfSpeech=\"n\"/>";
  private static final String PLAIN_LEXICON = """
      <Lexicon id="plain" label="plain" language="en" version="1">
        <LexicalEntry id="plain-entry">
          <Lemma writtenForm="task" partOfSpeech="n"/>
          <Sense id="plain-sense" synset="plain-synset"/>
        </LexicalEntry>
        <Synset id="plain-synset" partOfSpeech="n"/>
      </Lexicon>
      """;

  /**
   * Rejects misplaced elements before composition can use or discard them.
   *
   * @param content The invalid extension content.
   */
  @ParameterizedTest
  @MethodSource("invalidNesting")
  void testRejectsInvalidNesting(String content) {
    assertMalformed(extension(EXTENDS + content));
  }

  /**
   * Supplies XML with content under an unexpected WN-LMF parent.
   *
   * @return The named extension contents.
   */
  private static Stream<Arguments> invalidNesting() {
    return Stream.of(
        Arguments.of(Named.of("nested external entry", """
            <ExternalLexicalEntry id="ewn-process-n">
              <ExternalLexicalEntry id="ewn-software-n"/>
            </ExternalLexicalEntry>
            """)),
        Arguments.of(Named.of("nested external sense", """
            <ExternalLexicalEntry id="ewn-process-n">
              <ExternalSense id="ewn-process-n-08056231">
                <ExternalSense id="ewn-process-n-05984756"/>
              </ExternalSense>
            </ExternalLexicalEntry>
            """)),
        Arguments.of(Named.of("nested external synset", """
            <ExternalSynset id="ewn-08056231-n">
              <ExternalSynset id="ewn-06581154-n"/>
            </ExternalSynset>
            """)),
        Arguments.of(Named.of("new entry in external entry", """
            <ExternalLexicalEntry id="ewn-process-n">
              <LexicalEntry id="ext-entry">
                <Lemma writtenForm="task" partOfSpeech="n"/>
                <Sense id="ext-sense" synset="ext-new"/>
              </LexicalEntry>
            </ExternalLexicalEntry>
            """ + NEW_SYNSET)),
        Arguments.of(Named.of("external entry in new entry", """
            <LexicalEntry id="ext-entry">
              <Lemma writtenForm="task" partOfSpeech="n"/>
              <ExternalLexicalEntry id="ewn-process-n"/>
            </LexicalEntry>
            """)),
        Arguments.of(Named.of("new sense in external sense", """
            <ExternalLexicalEntry id="ewn-process-n">
              <ExternalSense id="ewn-process-n-08056231">
                <Sense id="ext-sense" synset="ext-new"/>
              </ExternalSense>
            </ExternalLexicalEntry>
            """ + NEW_SYNSET)),
        Arguments.of(Named.of("external sense in new sense", """
            <ExternalLexicalEntry id="ewn-process-n">
              <Sense id="ext-sense" synset="ext-new">
                <ExternalSense id="ewn-process-n-08056231"/>
              </Sense>
            </ExternalLexicalEntry>
            """ + NEW_SYNSET)),
        Arguments.of(Named.of("new synset in external synset", """
            <ExternalSynset id="ewn-08056231-n">
              <Synset id="ext-new" partOfSpeech="n"/>
            </ExternalSynset>
            """)),
        Arguments.of(Named.of("external synset in new synset", """
            <Synset id="ext-new" partOfSpeech="n">
              <ExternalSynset id="ewn-08056231-n"/>
            </Synset>
            """)),
        Arguments.of(Named.of("requires in external entry", """
            <ExternalLexicalEntry id="ewn-process-n">
              <Requires ref="unrelated" version="1"/>
            </ExternalLexicalEntry>
            """)),
        Arguments.of(Named.of("nested sense relation", """
            <ExternalLexicalEntry id="ewn-process-n">
              <ExternalSense id="ewn-process-n-08056231">
                <SenseRelation relType="also" target="ewn-process-n-08056231">
                  <SenseRelation relType="antonym" target="ewn-process-n-08056231"/>
                </SenseRelation>
              </ExternalSense>
            </ExternalLexicalEntry>
            """)),
        Arguments.of(Named.of("nested synset relation", """
            <ExternalSynset id="ewn-08056231-n">
              <SynsetRelation relType="also" target="ewn-08056231-n">
                <SynsetRelation relType="antonym" target="ewn-08056231-n"/>
              </SynsetRelation>
            </ExternalSynset>
            """)),
        Arguments.of(Named.of("definition in relation", """
            <ExternalSynset id="ewn-08056231-n">
              <SynsetRelation relType="also" target="ewn-08056231-n">
                <Definition>not a direct child</Definition>
              </SynsetRelation>
            </ExternalSynset>
            """)),
        Arguments.of(Named.of("synset in entry", """
            <LexicalEntry id="ext-entry">
              <Lemma writtenForm="task" partOfSpeech="n"/>
              <Sense id="ext-sense" synset="ext-new"/>
              <Synset id="ext-new" partOfSpeech="n"/>
            </LexicalEntry>
            """)),
        Arguments.of(Named.of("entry in synset", """
            <Synset id="ext-new" partOfSpeech="n">
              <LexicalEntry id="ext-entry">
                <Lemma writtenForm="task" partOfSpeech="n"/>
                <Sense id="ext-sense" synset="ext-new"/>
              </LexicalEntry>
            </Synset>
            """)),
        Arguments.of(Named.of("sense in lemma", """
            <LexicalEntry id="ext-entry">
              <Lemma writtenForm="task" partOfSpeech="n">
                <Sense id="ext-sense" synset="ext-new"/>
              </Lemma>
            </LexicalEntry>
            """ + NEW_SYNSET)),
        Arguments.of(Named.of("entry without lemma", "<LexicalEntry id=\"ext-entry\"/>")));
  }

  /**
   * Requires a base declaration to belong directly to the extension.
   *
   * @param parent The incorrect parent element.
   */
  @ParameterizedTest
  @ValueSource(strings = {"ExternalLexicalEntry id=\"ewn-process-n\"",
      "Requires ref=\"unrelated\" version=\"1\""})
  void testRejectsExtendsUnderAnotherElement(String parent) {
    assertMalformed(extension(element(parent, EXTENDS)));
  }

  /** Rejects nested dependency declarations. */
  @Test
  void testRejectsRequiresInsideExtends() {
    assertMalformed(extension("""
        <Extends ref="ewn" version="2020">
          <Requires ref="unrelated" version="1"/>
        </Extends>
        """));
  }

  /** Requires the WN-LMF resource root even for one ordinary lexicon. */
  @Test
  void testRejectsLexiconWithoutResourceRoot() {
    assertMalformed(PLAIN_LEXICON);
  }

  /** Prevents resource containers from appearing inside an extension. */
  @Test
  void testRejectsNestedLexicalResource() {
    assertMalformed(extension(EXTENDS + "<LexicalResource/>"));
  }

  /**
   * Excludes lexicons in ignored content from the returned resource.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testSkippedSubtreeCannotAddALexicon() throws IOException {
    final WnLmfResource resource = resource("<LexicalResource>"
        + element("Metadata", PLAIN_LEXICON)
        + PLAIN_LEXICON.replace("plain", "visible") + "</LexicalResource>");
    assertEquals(List.of("visible"), resource.lexicons().stream().map(WnLmfLexicon::id).toList());
  }

  /**
   * Accepts ordinary documents without a resolver.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testPlainLexiconLoadsWithoutResolver() throws IOException {
    final WnLmfResource resource = WnLmfReader.readResource(new ByteArrayInputStream(
        ("<LexicalResource>" + PLAIN_LEXICON + "</LexicalResource>")
            .getBytes(StandardCharsets.UTF_8)), RESOURCE_NAME);
    assertEquals(List.of("plain-synset"), resource.lexicons().get(0).knowledgeBase()
        .lookup("task", WordNetPOS.NOUN).stream().map(Synset::id).toList());
  }

  /**
   * Preserves the owning entry across ignored elements.
   *
   * @param ignored The ignored element.
   * @throws IOException If reading fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {"Form writtenForm=\"processes\"", "ExternalForm id=\"base-form\"",
      "ExternalLemma", "SyntacticBehaviour subcategorizationFrame=\"frame\"", "Metadata"})
  void testSkippedEntrySubtreeCannotAddASense(String ignored) throws IOException {
    final String hidden = "<Sense id=\"hidden\" synset=\"missing\"/>";
    final WnLmfLexicon lexicon = read(extension(EXTENDS
        + "<ExternalLexicalEntry id=\"ewn-process-n\">" + element(ignored, hidden)
        + "<Sense id=\"ext-sense\" synset=\"ext-new\"/></ExternalLexicalEntry>"
        + NEW_SYNSET));
    assertEquals(List.of("ewn-08056231-n", "ewn-05984756-n", "ext-new"),
        lexicon.knowledgeBase().lookup("process", WordNetPOS.NOUN).stream()
            .map(Synset::id).toList());
    assertEquals(List.of("process"),
        lexicon.knowledgeBase().synset("ext-new").orElseThrow().lemmas());
  }

  /**
   * Excludes skipped definitions and relations from the surrounding synset.
   *
   * @param ignored The ignored element.
   * @throws IOException If reading fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {"Example", "ILIDefinition", "Metadata"})
  void testSkippedSynsetSubtreeCannotAddDefinitionsOrRelations(String ignored) throws IOException {
    final String hidden = """
        <Definition>ignored definition</Definition>
        <SynsetRelation relType="unknown" target="missing"/>
        """;
    final WnLmfLexicon lexicon = read(extension(EXTENDS
        + "<ExternalSynset id=\"ewn-90000001-n\">" + element(ignored, hidden)
        + "<Definition>kept definition</Definition></ExternalSynset>"));
    final Synset synset = lexicon.knowledgeBase().synset("ewn-90000001-n").orElseThrow();
    assertEquals("kept definition", synset.gloss());
    assertTrue(synset.related(WordNetRelation.ALSO_SEE).isEmpty());
  }

  /**
   * Checks definitions, relations, lemmas and dependency metadata together.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testValidChildrenAndSkippedMetadataPreserveTheirOwners() throws IOException {
    final WnLmfLexicon lexicon = read(extension(EXTENDS + """
        <Requires ref="metadata-only" version="2"/>
        <ExternalLexicalEntry id="ewn-process-n">
          <ExternalLemma><Pronunciation>process</Pronunciation><Tag category="usage">x</Tag></ExternalLemma>
          <ExternalSense id="ewn-process-n-08056231">
            <SenseRelation relType="also" target="ewn-process-n-05984756"/>
            <Example>some example</Example><Count>1</Count>
          </ExternalSense>
          <ExternalSense id="ewn-process-n-05984756"/>
          <Sense id="ext-sense" synset="ext-new"/>
        </ExternalLexicalEntry>
        <LexicalEntry id="ext-entry">
          <Lemma writtenForm="task" partOfSpeech="n"><Pronunciation>task</Pronunciation></Lemma>
          <Form writtenForm="tasks"><Tag category="number">plural</Tag></Form>
          <Sense id="ext-task" synset="ext-new"/>
        </LexicalEntry>
        <Synset id="ext-new" partOfSpeech="n">
          <Definition>first</Definition><Definition>second</Definition>
          <ILIDefinition>not a gloss</ILIDefinition>
          <SynsetRelation relType="hypernym" target="ewn-08056231-n"/>
          <Example>an example</Example>
        </Synset>
        <ExternalSynset id="ewn-08056231-n"/>
        """));
    final Synset added = lexicon.knowledgeBase().synset("ext-new").orElseThrow();
    assertEquals(List.of("process", "task"), added.lemmas());
    assertEquals("first; second", added.gloss());
    assertEquals(List.of("ewn-08056231-n"), added.related(WordNetRelation.HYPERNYM));
    assertEquals(List.of("ewn-05984756-n"), lexicon.knowledgeBase()
        .synset("ewn-08056231-n").orElseThrow().related(WordNetRelation.ALSO_SEE));
    assertEquals("metadata-only", lexicon.dependencies().get(0).ref());
  }

  /**
   * Rejects malformed XML even when the lexical content would be ignored.
   *
   * @param ignored The malformed XML.
   */
  @ParameterizedTest
  @ValueSource(strings = {"<Metadata><child></Metadata>", "<Metadata><child>",
      "<Metadata>&undeclared;</Metadata>"})
  void testSkippedSubtreeMustBeWellFormed(String ignored) {
    assertMalformed(extension(EXTENDS + ignored));
  }

  /**
   * Checks nested skipped elements without losing the following lexicon.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testNestedSkippedElementsKeepFollowingLexicon() throws IOException {
    final int depth = 16;
    final WnLmfResource resource = resource("<LexicalResource>"
        + "<Metadata>".repeat(depth) + PLAIN_LEXICON + "</Metadata>".repeat(depth)
        + PLAIN_LEXICON + "</LexicalResource>");
    assertEquals(List.of("plain"), resource.lexicons().stream().map(WnLmfLexicon::id).toList());
    assertEquals(List.of("plain-synset"), resource.lexicons().get(0).knowledgeBase()
        .lookup("task", WordNetPOS.NOUN).stream().map(Synset::id).toList());
  }

  /**
   * Checks that a document fails with the resource name and source line.
   *
   * @param document The invalid document.
   */
  private void assertMalformed(String document) {
    final InvalidFormatException error = assertThrows(InvalidFormatException.class,
        () -> read(document));
    assertTrue(error.getMessage().contains(RESOURCE_NAME), error::getMessage);
    assertTrue(error.getMessage().contains("line"), error::getMessage);
  }

  /**
   * Loads a resource using the existing base fixture.
   *
   * @param document The XML text.
   * @return The only lexicon.
   * @throws IOException If reading fails.
   */
  private WnLmfLexicon read(String document) throws IOException {
    return resource(document).lexicons().get(0);
  }

  /**
   * Loads all lexicons using the existing base fixture for dependencies.
   *
   * @param document The XML text.
   * @return The parsed resource.
   * @throws IOException If reading fails.
   */
  private WnLmfResource resource(String document) throws IOException {
    return WnLmfReader.readResource(new ByteArrayInputStream(
        document.getBytes(StandardCharsets.UTF_8)), RESOURCE_NAME, dependency -> {
          assertEquals("ewn", dependency.ref());
          assertEquals("2020", dependency.version());
          return new WnLmfSource("extension-base.xml",
              getClass().getResourceAsStream("extension-base.xml"));
        });
  }

  /**
   * Wraps content and the dependency declaration in an extension document.
   *
   * @param content The extension content.
   * @return The XML document.
   */
  private String extension(String content) {
    return "<LexicalResource><LexiconExtension id=\"ext\" label=\"ext\" language=\"en\" "
        + "version=\"1\">\n" + content + "</LexiconExtension></LexicalResource>";
  }

  /**
   * Wraps content in an element with optional attributes.
   *
   * @param opening The element name and attributes.
   * @param content The element content.
   * @return The complete element.
   */
  private String element(String opening, String content) {
    final int space = opening.indexOf(' ');
    final String name = space < 0 ? opening : opening.substring(0, space);
    return "<" + opening + ">" + content + "</" + name + ">";
  }
}
