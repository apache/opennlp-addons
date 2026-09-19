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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.xml.namespace.QName;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.wordnet.LexicalKnowledgeBase;
import opennlp.tools.wordnet.Synset;
import opennlp.tools.wordnet.WordNetPOS;
import opennlp.tools.wordnet.WordNetRelation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests extension loading, validation, source ownership and query results. */
class WnLmfExtensionTest {

  private static final String RESOURCE_NAME = "extension-test.xml";
  private static final String BASE_FIXTURE = "extension-base.xml";
  private static final String EXTENSION_FIXTURE = "extension-computer-science.xml";
  private static final String BASE_REF = "ewn";
  private static final String BASE_VERSION = "2020";
  private static final int EXTENSION_DEPTH_LIMIT = 16;
  private static final String SOFTWARE_SYNSET_ID = "ewn-06581154-n";
  private static final String PROCESS_SYNSET_ID = "ewn-08056231-n";
  private static final String EMPTY_SYNSET_ID = "ewn-90000001-n";

  @TempDir
  Path tempDir;

  /**
   * Adds computer-science senses while retaining base members and hypernyms.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testComputerScienceExampleAddsSenseToBaseEntryAndNewSynsetWithExternalHypernym()
      throws IOException {
    final WnLmfLexicon composed = composeExample();
    final LexicalKnowledgeBase kb = composed.knowledgeBase();

    assertEquals(List.of(PROCESS_SYNSET_ID, "ewn-05984756-n", "ewn-20000123-n"),
        kb.lookup("process", WordNetPOS.NOUN).stream().map(Synset::id).toList());

    final Synset computerProcess = kb.synset("ewn-20000123-n").orElseThrow();
    assertEquals(List.of("process"), computerProcess.lemmas());
    assertEquals("a running instance of a computer program", computerProcess.gloss());
    assertEquals(List.of(SOFTWARE_SYNSET_ID), computerProcess.related(WordNetRelation.HYPERNYM));
    final Synset software = kb.synset(SOFTWARE_SYNSET_ID).orElseThrow();
    assertEquals("a written version of a program", software.gloss());
    assertEquals(List.of("software"), software.lemmas());

    final List<Synset> daemon = kb.lookup("daemon", WordNetPOS.NOUN);
    assertEquals(List.of("ewn-20000124-n"), daemon.stream().map(Synset::id).toList());
    assertEquals(List.of("ewn-20000123-n"), daemon.get(0).related(WordNetRelation.HYPERNYM));

    assertEquals(List.of(SOFTWARE_SYNSET_ID),
        kb.lookup("software", WordNetPOS.NOUN).stream().map(Synset::id).toList());
    assertEquals(6, ((InMemoryWordNetLexicon) kb).size());
  }

  /**
   * Retains the extension identity, base reference and dependency metadata.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testComposedDescriptorPreservesIdentityMetadataExtendsAndRequires() throws IOException {
    final WnLmfLexicon composed = composeExample();

    assertEquals("ewn-cs-example", composed.id());
    assertEquals("English WordNet Computer Science Terms (example)", composed.label());
    assertEquals("en", composed.language());
    assertEquals("1.0", composed.version());
    assertEquals(Optional.of(new WnLmfDependency(BASE_REF, BASE_VERSION)),
        composed.extensionOf());
    assertEquals(List.of(new WnLmfDependency("omw-fr", "1.4",
        Optional.of("https://example.org/omw-fr-1.4.xml"))), composed.dependencies());
    assertEquals("https://creativecommons.org/publicdomain/zero/1.0/",
        composed.metadata().get(new QName("license")));
    assertEquals("OpenNLP test fixture", composed.metadata()
        .get(new QName("https://globalwordnet.github.io/schemas/dc/", "publisher")));
  }

  /**
   * Retains the base URL and distinguishes ordinary lexicons from extensions.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testExtendsUrlIsPreservedAndOrdinaryLexiconHasEmptyExtensionOf() throws IOException {
    final String document = "<LexicalResource><LexiconExtension id=\"ext\" label=\"ext\" "
        + "language=\"en\" version=\"1.0\">"
        + "<Extends ref=\"ewn\" version=\"2020\" url=\"https://example.org/ewn-2020.xml\"/>"
        + "</LexiconExtension></LexicalResource>";
    final WnLmfLexicon composed = compose(document, baseResolver());
    assertEquals(Optional.of(new WnLmfDependency(BASE_REF, BASE_VERSION,
        Optional.of("https://example.org/ewn-2020.xml"))), composed.extensionOf());

    final WnLmfLexicon ordinary = WnLmfReader
        .readResource(bytes(fixture(BASE_FIXTURE)), RESOURCE_NAME, baseResolver())
        .lexicons().get(0);
    assertEquals(Optional.empty(), ordinary.extensionOf());
  }

  /**
   * Preserves a separately loaded base after extension composition.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testSeparatelyLoadedBaseLexiconRemainsUnchangedByComposition() throws IOException {
    final LexicalKnowledgeBase base;
    try (InputStream in = bytes(fixture(BASE_FIXTURE))) {
      base = WnLmfReader.read(in, BASE_FIXTURE);
    }
    composeExample();

    assertEquals(2, base.lookup("process", WordNetPOS.NOUN).size());
    assertTrue(base.synset("ewn-20000123-n").isEmpty());
    assertEquals(List.of(PROCESS_SYNSET_ID),
        base.synset(SOFTWARE_SYNSET_ID).orElseThrow().related(WordNetRelation.HYPERNYM));
    assertEquals(4, ((InMemoryWordNetLexicon) base).size());
  }

  /**
   * Appends a new sense's lemma after the base synset members.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testNewSenseOnExternalBaseSynsetAddsItsLemmaAfterBaseMembers() throws IOException {
    final String document = extension(
        "<LexicalEntry id=\"ext-workflow-n\">"
        + "<Lemma writtenForm=\"workflow\" partOfSpeech=\"n\"/>"
        + "<Sense id=\"ext-workflow-n-1\" synset=\"ewn-08056231-n\"/></LexicalEntry>"
        + "<ExternalSynset id=\"ewn-08056231-n\"/>");
    final LexicalKnowledgeBase kb = compose(document, baseResolver()).knowledgeBase();

    assertEquals(List.of("process", "workflow"),
        kb.synset(PROCESS_SYNSET_ID).orElseThrow().lemmas());
    assertEquals(List.of(PROCESS_SYNSET_ID),
        kb.lookup("workflow", WordNetPOS.NOUN).stream().map(Synset::id).toList());
  }

  /**
   * Appends extension relation targets after base targets.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testExternalSynsetAddsTypedRelationAfterBaseTargets() throws IOException {
    final String document = extension(
        "<LexicalEntry id=\"ext-code-n\"><Lemma writtenForm=\"code\" partOfSpeech=\"n\"/>"
        + "<Sense id=\"ext-code-n-1\" synset=\"ext-1-n\"/></LexicalEntry>"
        + "<ExternalSynset id=\"ewn-06581154-n\">"
        + "<SynsetRelation relType=\"hypernym\" target=\"ext-1-n\"/></ExternalSynset>"
        + "<Synset id=\"ext-1-n\" partOfSpeech=\"n\"/>");
    final LexicalKnowledgeBase kb = compose(document, baseResolver()).knowledgeBase();

    assertEquals(List.of(PROCESS_SYNSET_ID, "ext-1-n"),
        kb.synset(SOFTWARE_SYNSET_ID).orElseThrow().related(WordNetRelation.HYPERNYM));
  }

  /**
   * Exposes an added sense relation through the containing synset.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testExternalSenseRelationIsLiftedToTheSynsetGraph() throws IOException {
    final String document = extension(
        "<ExternalLexicalEntry id=\"ewn-software-n\">"
        + "<ExternalSense id=\"ewn-software-n-06581154\">"
        + "<SenseRelation relType=\"derivation\" target=\"ext-programmer-n-1\"/>"
        + "</ExternalSense></ExternalLexicalEntry>"
        + "<LexicalEntry id=\"ext-programmer-n\">"
        + "<Lemma writtenForm=\"programmer\" partOfSpeech=\"n\"/>"
        + "<Sense id=\"ext-programmer-n-1\" synset=\"ext-2-n\"/></LexicalEntry>"
        + "<Synset id=\"ext-2-n\" partOfSpeech=\"n\"/>");
    final LexicalKnowledgeBase kb = compose(document, baseResolver()).knowledgeBase();

    assertEquals(List.of("ext-2-n"), kb.synset(SOFTWARE_SYNSET_ID).orElseThrow()
        .related(WordNetRelation.DERIVATIONALLY_RELATED));
  }

  /**
   * Retains the first occurrence of each relation target.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testDuplicateAddedRelationTargetsAreStablyDeduplicated() throws IOException {
    final String document = extension(
        "<LexicalEntry id=\"ext-x-n\"><Lemma writtenForm=\"xterm\" partOfSpeech=\"n\"/>"
        + "<Sense id=\"ext-x-n-1\" synset=\"ext-3-n\"/></LexicalEntry>"
        + "<ExternalSynset id=\"ewn-06581154-n\">"
        // Duplicate of the base (hypernym, ewn-08056231-n) pair, then a new pair twice.
        + "<SynsetRelation relType=\"hypernym\" target=\"ewn-08056231-n\"/>"
        + "<SynsetRelation relType=\"hypernym\" target=\"ext-3-n\"/>"
        + "<SynsetRelation relType=\"hypernym\" target=\"ext-3-n\"/>"
        + "</ExternalSynset>"
        + "<ExternalSynset id=\"ewn-08056231-n\"/>"
        + "<Synset id=\"ext-3-n\" partOfSpeech=\"n\"/>");
    final LexicalKnowledgeBase kb = compose(document, baseResolver()).knowledgeBase();

    assertEquals(List.of(PROCESS_SYNSET_ID, "ext-3-n"),
        kb.synset(SOFTWARE_SYNSET_ID).orElseThrow().related(WordNetRelation.HYPERNYM));
  }

  /**
   * Adds a definition to an empty base gloss and preserves an existing definition.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testExtensionDefinitionFillsEmptyBaseGlossButNeverReplacesANonEmptyOne()
      throws IOException {
    final String document = extension(
        "<ExternalSynset id=\"ewn-90000001-n\">"
        + "<Definition>a placeholder definition</Definition></ExternalSynset>"
        + "<ExternalSynset id=\"ewn-08056231-n\">"
        + "<Definition>must not replace the base definition</Definition></ExternalSynset>");
    final LexicalKnowledgeBase kb = compose(document, baseResolver()).knowledgeBase();

    assertEquals("a placeholder definition",
        kb.synset(EMPTY_SYNSET_ID).orElseThrow().gloss());
    assertEquals("a phenomenon that unfolds over time",
        kb.synset(PROCESS_SYNSET_ID).orElseThrow().gloss());
  }

  /**
   * Selects only the requested base from a resource containing several lexicons.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testMultiLexiconBaseResourceResolvesTheExactRequestedIdAndVersion() throws IOException {
    final String multiBase = "<LexicalResource>" + lexicon("other", "3.1", "cat")
        + lexicon(BASE_REF, BASE_VERSION, "process") + "</LexicalResource>";
    final String document = extension(
        "<ExternalLexicalEntry id=\"ewn-process-n\">"
        + "<Sense id=\"ext-process-n-1\" synset=\"ext-4-n\"/></ExternalLexicalEntry>"
        + "<Synset id=\"ext-4-n\" partOfSpeech=\"n\"/>");
    final LexicalKnowledgeBase kb = compose(document,
        new CatalogResolver().with(BASE_REF, BASE_VERSION, multiBase)).knowledgeBase();

    assertEquals(List.of("ewn-1-n", "ext-4-n"),
        kb.lookup("process", WordNetPOS.NOUN).stream().map(Synset::id).toList());
    assertTrue(kb.lookup("cat", WordNetPOS.NOUN).isEmpty());
    assertTrue(kb.synset("other-1-n").isEmpty());
  }

  /**
   * Shares base resolution without sharing extension entries.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testSiblingExtensionsComposeIndependentlyAndShareOneResolverCall() throws IOException {
    final String document = "<LexicalResource>"
        + extensionElement("ext-a", "1.0", BASE_REF, BASE_VERSION,
            "<LexicalEntry id=\"ext-a-alpha-n\"><Lemma writtenForm=\"alpha\" partOfSpeech=\"n\"/>"
            + "<Sense id=\"ext-a-alpha-n-1\" synset=\"ext-a-1-n\"/></LexicalEntry>"
            + "<Synset id=\"ext-a-1-n\" partOfSpeech=\"n\"/>")
        + extensionElement("ext-b", "1.0", BASE_REF, BASE_VERSION,
            "<LexicalEntry id=\"ext-b-beta-n\"><Lemma writtenForm=\"beta\" partOfSpeech=\"n\"/>"
            + "<Sense id=\"ext-b-beta-n-1\" synset=\"ext-b-1-n\"/></LexicalEntry>"
            + "<Synset id=\"ext-b-1-n\" partOfSpeech=\"n\"/>")
        + "</LexicalResource>";
    final CatalogResolver resolver = baseResolver();
    final WnLmfResource resource = WnLmfReader.readResource(bytes(document),
        RESOURCE_NAME, resolver);

    assertEquals(1, resolver.calls);
    assertEquals(List.of("ext-a", "ext-b"),
        resource.lexicons().stream().map(WnLmfLexicon::id).toList());
    final LexicalKnowledgeBase first = resource.lexicon("ext-a").orElseThrow().knowledgeBase();
    final LexicalKnowledgeBase second = resource.lexicon("ext-b").orElseThrow().knowledgeBase();
    assertEquals(1, first.lookup("alpha", WordNetPOS.NOUN).size());
    assertTrue(first.lookup("beta", WordNetPOS.NOUN).isEmpty());
    assertEquals(1, second.lookup("beta", WordNetPOS.NOUN).size());
    assertTrue(second.lookup("alpha", WordNetPOS.NOUN).isEmpty());
    assertEquals(2, first.lookup("process", WordNetPOS.NOUN).size());
    assertEquals(2, second.lookup("process", WordNetPOS.NOUN).size());
  }

  /**
   * Isolates extension changes to shared base members, relations and definitions.
   *
   * @param reverse Whether to reverse extension declaration order.
   * @throws IOException If composition fails.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testSiblingAugmentationsDoNotChangeCachedBase(boolean reverse) throws IOException {
    final String alpha = extensionElement("alpha", "1.0", "alpha-base", "1.0", "");
    final String beta = extensionElement("beta", "1.0", "beta-base", "1.0", "");
    final CatalogResolver resolver = baseResolver()
        .with("alpha-base", "1.0", "<LexicalResource>"
            + extensionElement("alpha-base", "1.0", BASE_REF, BASE_VERSION, augmentation("alpha"))
            + "</LexicalResource>")
        .with("beta-base", "1.0", "<LexicalResource>"
            + extensionElement("beta-base", "1.0", BASE_REF, BASE_VERSION, augmentation("beta"))
            + "</LexicalResource>");
    final WnLmfResource resource = WnLmfReader.readResource(bytes("<LexicalResource>"
        + (reverse ? beta + alpha : alpha + beta) + "</LexicalResource>"),
        RESOURCE_NAME, resolver);

    assertEquals(3, resolver.calls);
    assertTrue(resolver.allStreamsClosed());
    assertSiblingAugmentation(resource.lexicon("alpha").orElseThrow().knowledgeBase(),
        "alpha", "beta");
    assertSiblingAugmentation(resource.lexicon("beta").orElseThrow().knowledgeBase(),
        "beta", "alpha");
  }

  /**
   * Rejects repeated external IDs across extensions in one XML document.
   *
   * @param content The repeated external declaration.
   */
  @ParameterizedTest
  @ValueSource(strings = {"<ExternalLexicalEntry id=\"ewn-software-n\"/>",
      "<ExternalSynset id=\"ewn-06581154-n\"/>"})
  void testDuplicateExternalIdsAcrossSiblingsAreRejected(String content) {
    final String document = "<LexicalResource>"
        + extensionElement("alpha", "1.0", BASE_REF, BASE_VERSION, content)
        + extensionElement("beta", "1.0", BASE_REF, BASE_VERSION, content)
        + "</LexicalResource>";
    final InvalidFormatException error = assertThrows(InvalidFormatException.class,
        () -> compose(document, baseResolver()));
    assertMessageContains(error, "Duplicate external");
    assertMessageContains(error, RESOURCE_NAME);
    assertMessageContains(error, " at line ");
  }

  /**
   * Appends inherited members and relations across extension levels without replacing a gloss.
   *
   * @throws IOException If composition fails.
   */
  @Test
  void testChainedAugmentationsPreserveBaseOrderAndFirstDefinition() throws IOException {
    final String alpha = "<LexicalResource>"
        + extensionElement("alpha", "1.0", BASE_REF, BASE_VERSION, augmentation("alpha"))
        + "</LexicalResource>";
    final String beta = "<LexicalResource>"
        + extensionElement("beta", "1.0", "alpha", "1.0", augmentation("beta"))
        + "</LexicalResource>";
    final CatalogResolver resolver = baseResolver().with("alpha", "1.0", alpha);
    final LexicalKnowledgeBase knowledgeBase = compose(beta, resolver).knowledgeBase();
    final Synset software = knowledgeBase.synset(SOFTWARE_SYNSET_ID).orElseThrow();

    assertEquals(2, resolver.calls);
    assertTrue(resolver.allStreamsClosed());
    assertEquals(List.of("software", "alpha", "beta"), software.lemmas());
    assertEquals(List.of(PROCESS_SYNSET_ID), software.related(WordNetRelation.HYPERNYM));
    assertEquals(List.of("alpha-synset", "beta-synset"), software.related(WordNetRelation.ALSO_SEE));
    assertEquals(List.of("alpha-synset", "beta-synset"),
        software.related(WordNetRelation.DERIVATIONALLY_RELATED));
    assertEquals("alpha definition", knowledgeBase.synset(EMPTY_SYNSET_ID)
        .orElseThrow().gloss());
    assertEquals(List.of(software), knowledgeBase.lookup("alpha", WordNetPOS.NOUN));
    assertEquals(List.of(software), knowledgeBase.lookup("beta", WordNetPOS.NOUN));
    assertEquals(List.of("alpha-term"), knowledgeBase.synset("alpha-synset").orElseThrow().lemmas());
    assertEquals(List.of("beta-term"), knowledgeBase.synset("beta-synset").orElseThrow().lemmas());
  }

  /**
   * Retains entries and cross-level relations through an extension chain.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testTwoLevelExtensionChainComposesBasePlusBothExtensions() throws IOException {
    final String level1 = "<LexicalResource>"
        + extensionElement("ewn-cs", "1.0", BASE_REF, BASE_VERSION,
            "<LexicalEntry id=\"cs-daemon-n\"><Lemma writtenForm=\"daemon\" partOfSpeech=\"n\"/>"
            + "<Sense id=\"cs-daemon-n-1\" synset=\"cs-1-n\"/></LexicalEntry>"
            + "<Synset id=\"cs-1-n\" partOfSpeech=\"n\">"
            + "<Definition>a background process</Definition></Synset>")
        + "</LexicalResource>";
    final String top = "<LexicalResource>"
        + extensionElement("ewn-cs-more", "1.0", "ewn-cs", "1.0",
            "<LexicalEntry id=\"more-cron-n\"><Lemma writtenForm=\"cron\" partOfSpeech=\"n\"/>"
            + "<Sense id=\"more-cron-n-1\" synset=\"more-1-n\"/></LexicalEntry>"
            + "<ExternalSynset id=\"cs-1-n\"/>"
            + "<Synset id=\"more-1-n\" partOfSpeech=\"n\">"
            + "<SynsetRelation relType=\"hypernym\" target=\"cs-1-n\"/></Synset>")
        + "</LexicalResource>";
    final CatalogResolver resolver = baseResolver().with("ewn-cs", "1.0", level1);
    final WnLmfLexicon composed = compose(top, resolver);

    assertEquals(2, resolver.calls);
    assertEquals(Optional.of(new WnLmfDependency("ewn-cs", "1.0")), composed.extensionOf());
    final LexicalKnowledgeBase kb = composed.knowledgeBase();
    assertEquals(2, kb.lookup("process", WordNetPOS.NOUN).size());
    assertEquals(1, kb.lookup("daemon", WordNetPOS.NOUN).size());
    assertEquals(List.of("cs-1-n"),
        kb.lookup("cron", WordNetPOS.NOUN).get(0).related(WordNetRelation.HYPERNYM));
  }

  /**
   * Closes resolved sources on success and on each loading failure.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testResolverSourcesAreClosedOnSuccessParseValidationAndCompositionFailure()
      throws IOException {
    // Success.
    final CatalogResolver success = baseResolver();
    try (InputStream in = bytes(fixture(EXTENSION_FIXTURE))) {
      WnLmfReader.readResource(in, EXTENSION_FIXTURE, success);
    }
    assertTrue(success.allStreamsClosed());

    // Parse failure inside the resolved base document.
    final CatalogResolver parseFailure = new CatalogResolver()
        .with(BASE_REF, BASE_VERSION, "<LexicalResource><Lexicon");
    assertThrows(InvalidFormatException.class,
        () -> compose(extension(""), parseFailure));
    assertTrue(parseFailure.allStreamsClosed());

    // Validation failure: the resolved resource has no exact version match.
    final CatalogResolver wrongVersion = new CatalogResolver().with(BASE_REF, BASE_VERSION,
        "<LexicalResource>" + lexicon(BASE_REF, "2021", "process") + "</LexicalResource>");
    assertThrows(InvalidFormatException.class,
        () -> compose(extension(""), wrongVersion));
    assertTrue(wrongVersion.allStreamsClosed());

    // Composition failure: a new id collides with a base id.
    final CatalogResolver collision = baseResolver();
    final String colliding = extension(
        "<LexicalEntry id=\"ewn-process-n\"><Lemma writtenForm=\"process\" partOfSpeech=\"n\"/>"
        + "<Sense id=\"ext-p-n-1\" synset=\"ext-5-n\"/></LexicalEntry>"
        + "<Synset id=\"ext-5-n\" partOfSpeech=\"n\"/>");
    assertThrows(InvalidFormatException.class, () -> compose(colliding, collision));
    assertTrue(collision.allStreamsClosed());
  }

  /**
   * Rejects collection changes and checks concurrent query results.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testComposedLexiconAndCollectionsAreImmutableAndSafeForConcurrentReads()
      throws IOException {
    final WnLmfLexicon composed = composeExample();
    final LexicalKnowledgeBase kb = composed.knowledgeBase();

    assertThrows(UnsupportedOperationException.class,
        () -> composed.metadata().put(new QName("x"), "y"));
    assertThrows(UnsupportedOperationException.class, () -> composed.dependencies().clear());
    assertThrows(UnsupportedOperationException.class,
        () -> kb.lookup("process", WordNetPOS.NOUN).clear());
    final Synset computerProcess = kb.synset("ewn-20000123-n").orElseThrow();
    assertThrows(UnsupportedOperationException.class,
        () -> computerProcess.relations().clear());
    assertThrows(UnsupportedOperationException.class,
        () -> computerProcess.lemmas().add("x"));

    assertTrue(IntStream.range(0, 64).parallel()
        .allMatch(i -> kb.lookup("process", WordNetPOS.NOUN).size() == 3
            && kb.synset("ewn-20000123-n").isPresent()));
  }

  /**
   * Checks extension loading through stream and path overloads.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testSingleLexiconResolverOverloadsReturnTheComposedKnowledgeBase() throws IOException {
    final LexicalKnowledgeBase fromStream;
    try (InputStream in = bytes(fixture(EXTENSION_FIXTURE))) {
      fromStream = WnLmfReader.read(in, EXTENSION_FIXTURE, baseResolver());
    }
    assertEquals(3, fromStream.lookup("process", WordNetPOS.NOUN).size());

    final Path file = tempDir.resolve("extension.xml");
    Files.writeString(file, fixture(EXTENSION_FIXTURE), StandardCharsets.UTF_8);
    final LexicalKnowledgeBase fromPath = WnLmfReader.read(file, baseResolver());
    assertEquals(3, fromPath.lookup("process", WordNetPOS.NOUN).size());

    final WnLmfResource fromResourcePath = WnLmfReader.readResource(file, baseResolver());
    assertEquals("ewn-cs-example", fromResourcePath.lexicons().get(0).id());
  }

  /**
   * Rejects invalid extension content with the relevant identifiers.
   *
   * @param document The invalid XML.
   * @param expectedMessageFragments The required error details.
   */
  @ParameterizedTest
  @MethodSource("rejectedExtensionDocuments")
  void testRejectsInvalidExtensionDocument(String document,
      List<String> expectedMessageFragments) {
    final InvalidFormatException e = assertThrows(InvalidFormatException.class,
        () -> compose(document, baseResolver()));
    for (final String fragment : expectedMessageFragments) {
      assertMessageContains(e, fragment);
    }
  }

  /** Reports the requested base identity when resolution fails. */
  @Test
  void testBaseAbsentFromTheResolverFailsWithTheRequestedIdAndVersion() {
    final IOException error = assertThrows(IOException.class,
        () -> compose(extension(""), new CatalogResolver()));
    assertMessageContains(error, BASE_REF);
    assertMessageContains(error, BASE_VERSION);
  }

  /** Rejects a resolved document with a different lexicon ID or version. */
  @Test
  void testResolvedResourceWithoutTheExactIdAndVersionIsRejected() {
    final CatalogResolver wrongVersion = new CatalogResolver().with(BASE_REF, BASE_VERSION,
        "<LexicalResource>" + lexicon(BASE_REF, "2021", "process") + "</LexicalResource>");
    final InvalidFormatException versionError = assertThrows(InvalidFormatException.class,
        () -> compose(extension(""), wrongVersion));
    assertMessageContains(versionError, BASE_REF);
    assertMessageContains(versionError, BASE_VERSION);

    final CatalogResolver wrongId = new CatalogResolver().with(BASE_REF, BASE_VERSION,
        "<LexicalResource>" + lexicon("wrong", BASE_VERSION, "process") + "</LexicalResource>");
    final InvalidFormatException idError = assertThrows(InvalidFormatException.class,
        () -> compose(extension(""), wrongId));
    assertMessageContains(idError, BASE_REF);
  }

  /** Rejects direct and indirect dependency cycles. */
  @Test
  void testSelfCycleAndTwoNodeCycleAreDetected() {
    final String selfCycle = "<LexicalResource>"
        + extensionElement("loop", "1.0", "loop", "1.0", "") + "</LexicalResource>";
    assertMessageContains(assertThrows(InvalidFormatException.class,
        () -> compose(selfCycle, new CatalogResolver().with("loop", "1.0", selfCycle))),
        "cycle");

    final String extensionA = "<LexicalResource>"
        + extensionElement("cycle-a", "1.0", "cycle-b", "1.0", "") + "</LexicalResource>";
    final String extensionB = "<LexicalResource>"
        + extensionElement("cycle-b", "1.0", "cycle-a", "1.0", "") + "</LexicalResource>";
    final CatalogResolver resolver = new CatalogResolver()
        .with("cycle-a", "1.0", extensionA)
        .with("cycle-b", "1.0", extensionB);
    final InvalidFormatException error = assertThrows(InvalidFormatException.class,
        () -> compose(extensionA, resolver));
    assertMessageContains(error, "cycle");
    assertMessageContains(error, "cycle-a");
    assertMessageContains(error, "cycle-b");
  }

  /**
   * Accepts the maximum extension depth and rejects the next level.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testExtensionChainDepthBoundary() throws IOException {
    assertEquals(1, compose(chainTop(EXTENSION_DEPTH_LIMIT),
        chainResolver(EXTENSION_DEPTH_LIMIT))
        .knowledgeBase().lookup("process", WordNetPOS.NOUN).size());

    final int tooDeep = EXTENSION_DEPTH_LIMIT + 1;
    final InvalidFormatException error = assertThrows(InvalidFormatException.class,
        () -> compose(chainTop(tooDeep), chainResolver(tooDeep)));
    assertMessageContains(error, String.valueOf(EXTENSION_DEPTH_LIMIT));
  }

  /**
   * Rejects null resolver results and reused sources.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testResolverContractViolationsFailLoud() throws IOException {
    final WnLmfResolver returnsNull = reference -> null;
    final IllegalStateException nullError = assertThrows(IllegalStateException.class,
        () -> compose(extension(""), returnsNull));
    assertMessageContains(nullError, BASE_REF);

    // A source can be consumed only once, even for different references.
    final WnLmfSource shared = new WnLmfSource("shared.xml", bytes(fixture(BASE_FIXTURE)));
    final WnLmfResolver reusesSource = reference -> shared;
    final String siblings = "<LexicalResource>"
        + extensionElement("ext-a", "1.0", BASE_REF, BASE_VERSION, "")
        + extensionElement("ext-b", "1.0", "base-two", "1.0", "")
        + "</LexicalResource>";
    final IllegalStateException reuseError = assertThrows(IllegalStateException.class,
        () -> WnLmfReader.readResource(bytes(siblings), RESOURCE_NAME, reusesSource));
    assertMessageContains(reuseError, "shared.xml");
  }

  /**
   * Rejects null arguments consistently across resolver-aware overloads.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testResolverAwareOverloadsValidateTheirArguments() throws IOException {
    final CatalogResolver resolver = baseResolver();
    final Path file = tempDir.resolve("valid.xml");
    Files.writeString(file, fixture(BASE_FIXTURE), StandardCharsets.UTF_8);

    assertAll(
        () -> assertThrows(IllegalArgumentException.class,
            () -> WnLmfReader.read((Path) null, resolver)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> WnLmfReader.read(file, (WnLmfResolver) null)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> WnLmfReader.read((InputStream) null, RESOURCE_NAME, resolver)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> WnLmfReader.read(bytes("<LexicalResource/>"), null, resolver)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> WnLmfReader.read(bytes("<LexicalResource/>"), RESOURCE_NAME, null)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> WnLmfReader.readResource((Path) null, resolver)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> WnLmfReader.readResource(file, (WnLmfResolver) null)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> WnLmfReader.readResource((InputStream) null, RESOURCE_NAME, resolver)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> WnLmfReader.readResource(bytes("<LexicalResource/>"), null, resolver)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> WnLmfReader.readResource(bytes("<LexicalResource/>"), RESOURCE_NAME, null)));
  }

  /**
   * Requires a resolver for extension documents.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testNoResolverOverloadsStillRejectExtensionsWithoutAnyResolution() throws IOException {
    try (InputStream in = bytes(fixture(EXTENSION_FIXTURE))) {
      assertMessageContains(assertThrows(InvalidFormatException.class,
          () -> WnLmfReader.read(in, EXTENSION_FIXTURE)), "LexiconExtension");
    }
    try (InputStream in = bytes(fixture(EXTENSION_FIXTURE))) {
      assertMessageContains(assertThrows(InvalidFormatException.class,
          () -> WnLmfReader.readResource(in, EXTENSION_FIXTURE)), "LexiconExtension");
    }
    final Path file = tempDir.resolve("no-resolver.xml");
    Files.writeString(file, fixture(EXTENSION_FIXTURE), StandardCharsets.UTF_8);
    assertMessageContains(assertThrows(InvalidFormatException.class,
        () -> WnLmfReader.read(file)), "LexiconExtension");
  }

  /**
   * Preserves ordinary lexicon dependencies as metadata without resolving them.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testOrdinaryLexiconsNeverConsultTheResolver() throws IOException {
    final CatalogResolver resolver = baseResolver();
    final WnLmfResource resource;
    try (InputStream in = bytes(fixture("omw-multilingual.xml"))) {
      resource = WnLmfReader.readResource(in, "omw-multilingual.xml", resolver);
    }
    assertEquals(0, resolver.calls);
    // Requires stays descriptive metadata even when a resolver is available.
    assertEquals(List.of(new WnLmfDependency("omw-en", "2.0")),
        resource.lexicon("omw-it").orElseThrow().dependencies());
  }

  /** Checks source names, streams and optional dependency URLs. */
  @Test
  void testSourceAndDependencyContractsRejectInvalidComponents() {
    assertThrows(IllegalArgumentException.class,
        () -> new WnLmfSource(null, bytes("x")));
    assertThrows(IllegalArgumentException.class,
        () -> new WnLmfSource("", bytes("x")));
    assertThrows(IllegalArgumentException.class,
        () -> new WnLmfSource("name.xml", null));
    assertEquals("name.xml", new WnLmfSource("name.xml", bytes("x")).name());

    assertEquals(Optional.empty(), new WnLmfDependency("base", "1").url());
    assertEquals(Optional.of("https://example.org/base.xml"),
        new WnLmfDependency("base", "1", Optional.of("https://example.org/base.xml")).url());
    assertThrows(IllegalArgumentException.class,
        () -> new WnLmfDependency("base", "1", null));
    assertThrows(IllegalArgumentException.class,
        () -> new WnLmfDependency("base", "1", Optional.of("")));
  }

  /** Reports the constructor parameter name when the extension reference is null. */
  @Test
  void testNullExtensionReferenceNamesArgument() {
    final LexicalKnowledgeBase knowledgeBase = new InMemoryWordNetLexicon(Map.of(), Map.of());
    final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new WnLmfLexicon("ext", "ext", "en", "1", Map.of(), List.of(), null,
            knowledgeBase));
    assertEquals("extensionOf must not be null; use Optional.empty()", error.getMessage());
  }

  /**
   * Closes the source stream through try-with-resources.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testSourceCloseClosesItsStreamOnce() throws IOException {
    final int[] closes = {0};
    final InputStream tracked = new ByteArrayInputStream(new byte[0]) {
      /** {@inheritDoc} */
      @Override
      public void close() throws IOException {
        closes[0]++;
        super.close();
      }
    };
    try (WnLmfSource source = new WnLmfSource("close.xml", tracked)) {
      assertEquals("close.xml", source.name());
    }
    assertEquals(1, closes[0]);
  }

  /**
   * Checks members and relations across 2500 base and 1000 added synsets.
   *
   * @throws IOException If the test document cannot be read.
   */
  @Test
  void testLargeBaseWithThousandsOfExternalReferencesComposesCompletely() throws IOException {
    final int baseSynsets = 2500;
    final int extensionSynsets = 1000;

    final StringBuilder base = new StringBuilder(1 << 20);
    base.append("<LexicalResource><Lexicon id=\"big\" label=\"big\" language=\"en\" ")
        .append("version=\"1.0\">");
    for (int i = 0; i < baseSynsets; i++) {
      base.append("<LexicalEntry id=\"big-e").append(i).append("\">")
          .append("<Lemma writtenForm=\"base").append(i).append("\" partOfSpeech=\"n\"/>")
          .append("<Sense id=\"big-w").append(i).append("\" synset=\"big-s").append(i)
          .append("\"/></LexicalEntry>");
    }
    for (int i = 0; i < baseSynsets; i++) {
      base.append("<Synset id=\"big-s").append(i).append("\" partOfSpeech=\"n\">");
      if (i > 0) {
        base.append("<SynsetRelation relType=\"hypernym\" target=\"big-s")
            .append(i - 1).append("\"/>");
      }
      base.append("</Synset>");
    }
    base.append("</Lexicon></LexicalResource>");

    final StringBuilder extension = new StringBuilder(1 << 20);
    extension.append("<LexicalResource><LexiconExtension id=\"big-ext\" label=\"big-ext\" ")
        .append("language=\"en\" version=\"1.0\"><Extends ref=\"big\" version=\"1.0\"/>");
    for (int i = 0; i < extensionSynsets; i++) {
      extension.append("<LexicalEntry id=\"ext-e").append(i).append("\">")
          .append("<Lemma writtenForm=\"new").append(i).append("\" partOfSpeech=\"n\"/>")
          .append("<Sense id=\"ext-w").append(i).append("\" synset=\"ext-s").append(i)
          .append("\"/></LexicalEntry>");
    }
    for (int i = 0; i < extensionSynsets; i++) {
      extension.append("<ExternalSynset id=\"big-s").append(i).append("\">")
          .append("<SynsetRelation relType=\"also\" target=\"ext-s").append(i)
          .append("\"/></ExternalSynset>")
          .append("<Synset id=\"ext-s").append(i).append("\" partOfSpeech=\"n\">")
          .append("<SynsetRelation relType=\"hypernym\" target=\"big-s").append(i)
          .append("\"/></Synset>");
    }
    extension.append("</LexiconExtension></LexicalResource>");

    final CatalogResolver resolver =
        new CatalogResolver().with("big", "1.0", base.toString());
    final WnLmfLexicon composed = compose(extension.toString(), resolver);
    final LexicalKnowledgeBase kb = composed.knowledgeBase();

    assertEquals(1, resolver.calls);
    assertEquals(baseSynsets + extensionSynsets, ((InMemoryWordNetLexicon) kb).size());
    for (int i = 0; i < baseSynsets; i++) {
      final Synset synset = kb.synset("big-s" + i).orElseThrow();
      assertEquals(List.of("base" + i), synset.lemmas());
      if (i > 0) {
        assertEquals(List.of("big-s" + (i - 1)), synset.related(WordNetRelation.HYPERNYM));
      }
      if (i < extensionSynsets) {
        assertEquals(List.of("ext-s" + i), synset.related(WordNetRelation.ALSO_SEE));
      } else {
        assertTrue(synset.related(WordNetRelation.ALSO_SEE).isEmpty());
      }
    }
    for (int i = 0; i < extensionSynsets; i++) {
      final Synset synset = kb.synset("ext-s" + i).orElseThrow();
      assertEquals(List.of("new" + i), synset.lemmas());
      assertEquals(List.of("big-s" + i), synset.related(WordNetRelation.HYPERNYM));
      assertEquals("ext-s" + i,
          kb.lookup("new" + i, WordNetPOS.NOUN).get(0).id());
    }
  }

  /**
   * Supplies invalid extension documents and the expected error details.
   *
   * @return The rejected documents and their expected message fragments.
   */
  private static Stream<Arguments> rejectedExtensionDocuments() {
    return Stream.of(
        Arguments.of(Named.of("LexiconExtension without Extends",
            "<LexicalResource><LexiconExtension id=\"ext\" label=\"ext\" language=\"en\" "
                + "version=\"1.0\"></LexiconExtension></LexicalResource>"),
            List.of("Extends")),
        Arguments.of(Named.of("Extends with an empty ref",
            "<LexicalResource><LexiconExtension id=\"ext\" label=\"ext\" language=\"en\" "
                + "version=\"1.0\"><Extends ref=\"\" version=\"2020\"/>"
                + "</LexiconExtension></LexicalResource>"),
            List.of("ref")),
        Arguments.of(Named.of("Extends without a version",
            "<LexicalResource><LexiconExtension id=\"ext\" label=\"ext\" language=\"en\" "
                + "version=\"1.0\"><Extends ref=\"ewn\"/>"
                + "</LexiconExtension></LexicalResource>"),
            List.of("version")),
        Arguments.of(Named.of("repeated Extends",
            "<LexicalResource><LexiconExtension id=\"ext\" label=\"ext\" language=\"en\" "
                + "version=\"1.0\"><Extends ref=\"ewn\" version=\"2020\"/>"
                + "<Extends ref=\"ewn\" version=\"2020\"/></LexiconExtension></LexicalResource>"),
            List.of("Extends")),
        Arguments.of(Named.of("Extends inside an ordinary Lexicon",
            "<LexicalResource><Lexicon id=\"plain\" label=\"plain\" language=\"en\" "
                + "version=\"1\"><Extends ref=\"ewn\" version=\"2020\"/>"
                + "<LexicalEntry id=\"p-cat-n\"><Lemma writtenForm=\"cat\" partOfSpeech=\"n\"/>"
                + "<Sense id=\"p-cat-n-1\" synset=\"p-1-n\"/></LexicalEntry>"
                + "<Synset id=\"p-1-n\" partOfSpeech=\"n\"/></Lexicon></LexicalResource>"),
            List.of("Extends")),
        Arguments.of(Named.of("Extends outside any lexicon",
            "<LexicalResource><Extends ref=\"ewn\" version=\"2020\"/>"
                + lexicon("plain", "1", "cat") + "</LexicalResource>"),
            List.of("Extends")),
        Arguments.of(Named.of("ExternalLexicalEntry inside an ordinary Lexicon",
            "<LexicalResource><Lexicon id=\"plain\" label=\"plain\" language=\"en\" "
                + "version=\"1\"><ExternalLexicalEntry id=\"ewn-process-n\"/>"
                + "<LexicalEntry id=\"p-cat-n\"><Lemma writtenForm=\"cat\" partOfSpeech=\"n\"/>"
                + "<Sense id=\"p-cat-n-1\" synset=\"p-1-n\"/></LexicalEntry>"
                + "<Synset id=\"p-1-n\" partOfSpeech=\"n\"/></Lexicon></LexicalResource>"),
            List.of("ExternalLexicalEntry")),
        Arguments.of(Named.of("ExternalSynset inside an ordinary Lexicon",
            "<LexicalResource><Lexicon id=\"plain\" label=\"plain\" language=\"en\" "
                + "version=\"1\"><LexicalEntry id=\"p-cat-n\">"
                + "<Lemma writtenForm=\"cat\" partOfSpeech=\"n\"/>"
                + "<Sense id=\"p-cat-n-1\" synset=\"p-1-n\"/></LexicalEntry>"
                + "<ExternalSynset id=\"ewn-08056231-n\"/>"
                + "<Synset id=\"p-1-n\" partOfSpeech=\"n\"/></Lexicon></LexicalResource>"),
            List.of("ExternalSynset")),
        Arguments.of(Named.of("ExternalSense inside a new LexicalEntry",
            extension("<LexicalEntry id=\"ext-y-n\">"
                + "<Lemma writtenForm=\"y\" partOfSpeech=\"n\"/>"
                + "<ExternalSense id=\"ewn-process-n-08056231\"/></LexicalEntry>")),
            List.of("ExternalSense")),
        Arguments.of(Named.of("ExternalSense outside an ExternalLexicalEntry",
            extension("<ExternalSense id=\"ewn-process-n-08056231\"/>")),
            List.of("ExternalSense")),
        Arguments.of(Named.of("base declared before its extension in one document",
            "<LexicalResource>" + lexicon(BASE_REF, BASE_VERSION, "process")
                + extensionElement("ext", "1.0", BASE_REF, BASE_VERSION, "")
                + "</LexicalResource>"),
            List.of("same document")),
        Arguments.of(Named.of("extension declared before its base in one document",
            "<LexicalResource>" + extensionElement("ext", "1.0", BASE_REF, BASE_VERSION, "")
                + lexicon(BASE_REF, BASE_VERSION, "process") + "</LexicalResource>"),
            List.of("same document")),
        Arguments.of(Named.of("new lexical entry id colliding with a base entry",
            extension("<LexicalEntry id=\"ewn-process-n\">"
                + "<Lemma writtenForm=\"process\" partOfSpeech=\"n\"/>"
                + "<Sense id=\"ext-p-n-1\" synset=\"ext-6-n\"/></LexicalEntry>"
                + "<Synset id=\"ext-6-n\" partOfSpeech=\"n\"/>")),
            List.of("ewn-process-n")),
        Arguments.of(Named.of("new sense id colliding with a base sense",
            extension("<LexicalEntry id=\"ext-q-n\">"
                + "<Lemma writtenForm=\"q\" partOfSpeech=\"n\"/>"
                + "<Sense id=\"ewn-process-n-08056231\" synset=\"ext-7-n\"/></LexicalEntry>"
                + "<Synset id=\"ext-7-n\" partOfSpeech=\"n\"/>")),
            List.of("ewn-process-n-08056231")),
        Arguments.of(Named.of("new synset id colliding with a base synset",
            extension("<LexicalEntry id=\"ext-r-n\">"
                + "<Lemma writtenForm=\"r\" partOfSpeech=\"n\"/>"
                + "<Sense id=\"ext-r-n-1\" synset=\"ewn-08056231-n\"/></LexicalEntry>"
                + "<Synset id=\"ewn-08056231-n\" partOfSpeech=\"n\"/>")),
            List.of(PROCESS_SYNSET_ID)),
        Arguments.of(Named.of("ExternalLexicalEntry absent from the base",
            extension("<ExternalLexicalEntry id=\"ewn-missing-n\"/>")),
            List.of("ewn-missing-n")),
        Arguments.of(Named.of("ExternalSynset naming a base sense",
            extension("<ExternalSynset id=\"ewn-process-n-08056231\"/>")),
            List.of("ewn-process-n-08056231")),
        Arguments.of(Named.of("ExternalLexicalEntry naming a base synset",
            extension("<ExternalLexicalEntry id=\"ewn-08056231-n\"/>")),
            List.of(PROCESS_SYNSET_ID)),
        Arguments.of(Named.of("ExternalSense naming a base lexical entry",
            extension("<ExternalLexicalEntry id=\"ewn-process-n\">"
                + "<ExternalSense id=\"ewn-process-n\"/></ExternalLexicalEntry>")),
            List.of("ewn-process-n")),
        Arguments.of(Named.of("ExternalSense under the wrong ExternalLexicalEntry",
            extension("<ExternalLexicalEntry id=\"ewn-process-n\">"
                + "<ExternalSense id=\"ewn-software-n-06581154\"/></ExternalLexicalEntry>")),
            List.of("ewn-software-n-06581154", "ewn-process-n")),
        Arguments.of(Named.of("new synset relation to an undeclared base synset",
            extension("<LexicalEntry id=\"ext-s-n\">"
                + "<Lemma writtenForm=\"s\" partOfSpeech=\"n\"/>"
                + "<Sense id=\"ext-s-n-1\" synset=\"ext-8-n\"/></LexicalEntry>"
                + "<Synset id=\"ext-8-n\" partOfSpeech=\"n\">"
                + "<SynsetRelation relType=\"hypernym\" target=\"ewn-90000001-n\"/></Synset>")),
            List.of(EMPTY_SYNSET_ID)),
        Arguments.of(Named.of("new sense on an undeclared base synset",
            extension("<LexicalEntry id=\"ext-t-n\">"
                + "<Lemma writtenForm=\"t\" partOfSpeech=\"n\"/>"
                + "<Sense id=\"ext-t-n-1\" synset=\"ewn-90000001-n\"/></LexicalEntry>")),
            List.of(EMPTY_SYNSET_ID)),
        Arguments.of(Named.of("added sense relation to a missing sense",
            extension("<ExternalLexicalEntry id=\"ewn-software-n\">"
                + "<ExternalSense id=\"ewn-software-n-06581154\">"
                + "<SenseRelation relType=\"derivation\" target=\"ewn-nowhere-n-1\"/>"
                + "</ExternalSense></ExternalLexicalEntry>")),
            List.of("ewn-nowhere-n-1")),
        Arguments.of(Named.of("new synset relation to a missing ID",
            extension("<LexicalEntry id=\"ext-u-n\">"
                + "<Lemma writtenForm=\"u\" partOfSpeech=\"n\"/>"
                + "<Sense id=\"ext-u-n-1\" synset=\"ext-9-n\"/></LexicalEntry>"
                + "<Synset id=\"ext-9-n\" partOfSpeech=\"n\">"
                + "<SynsetRelation relType=\"hypernym\" target=\"no-such-id\"/></Synset>")),
            List.of("no-such-id")),
        Arguments.of(Named.of("entry and target synset have different parts of speech",
            extension("<LexicalEntry id=\"ext-v-v\">"
                + "<Lemma writtenForm=\"vet\" partOfSpeech=\"v\"/>"
                + "<Sense id=\"ext-v-v-1\" synset=\"ewn-08056231-n\"/></LexicalEntry>"
                + "<ExternalSynset id=\"ewn-08056231-n\"/>")),
            List.of("part of speech")));
  }

  /**
   * Loads the computer-science test document with the base lexicon.
   *
   * @return The composed extension lexicon.
   * @throws IOException Thrown if a fixture read fails.
   */
  private WnLmfLexicon composeExample() throws IOException {
    try (InputStream in = bytes(fixture(EXTENSION_FIXTURE))) {
      final WnLmfResource resource =
          WnLmfReader.readResource(in, EXTENSION_FIXTURE, baseResolver());
      assertEquals(1, resource.lexicons().size());
      return resource.lexicons().get(0);
    }
  }

  /**
   * Adds content to the software synset and a definition to the empty base synset.
   *
   * @param prefix The unique prefix for the new identifiers and lemmas.
   * @return The extension body.
   */
  private String augmentation(String prefix) {
    return "<LexicalEntry id=\"" + prefix + "-alias\"><Lemma writtenForm=\"" + prefix
        + "\" partOfSpeech=\"n\"/><Sense id=\"" + prefix
        + "-alias-sense\" synset=\"ewn-06581154-n\"/></LexicalEntry>"
        + "<LexicalEntry id=\"" + prefix + "-entry\"><Lemma writtenForm=\"" + prefix
        + "-term\" partOfSpeech=\"n\"/><Sense id=\"" + prefix + "-sense\" synset=\""
        + prefix + "-synset\"/></LexicalEntry>"
        + "<ExternalLexicalEntry id=\"ewn-software-n\">"
        + "<ExternalSense id=\"ewn-software-n-06581154\">"
        + "<SenseRelation relType=\"derivation\" target=\"" + prefix + "-sense\"/>"
        + "</ExternalSense></ExternalLexicalEntry>"
        + "<ExternalSynset id=\"ewn-06581154-n\">"
        + "<SynsetRelation relType=\"also\" target=\"" + prefix + "-synset\"/></ExternalSynset>"
        + "<ExternalSynset id=\"ewn-90000001-n\"><Definition>" + prefix
        + " definition</Definition></ExternalSynset>"
        + "<Synset id=\"" + prefix + "-synset\" partOfSpeech=\"n\"/>";
  }

  /**
   * Checks base content and changes from only the specified extension.
   *
   * @param knowledgeBase The loaded extension.
   * @param ownPrefix The prefix of the expected changes.
   * @param otherPrefix The prefix of the excluded changes.
   */
  private void assertSiblingAugmentation(LexicalKnowledgeBase knowledgeBase,
      String ownPrefix, String otherPrefix) {
    final Synset software = knowledgeBase.synset(SOFTWARE_SYNSET_ID).orElseThrow();
    assertEquals(List.of("software", ownPrefix), software.lemmas());
    assertEquals(List.of(PROCESS_SYNSET_ID), software.related(WordNetRelation.HYPERNYM));
    assertEquals(List.of(ownPrefix + "-synset"), software.related(WordNetRelation.ALSO_SEE));
    assertEquals(List.of(ownPrefix + "-synset"),
        software.related(WordNetRelation.DERIVATIONALLY_RELATED));
    assertEquals("a written version of a program", software.gloss());
    assertEquals(ownPrefix + " definition", knowledgeBase.synset(EMPTY_SYNSET_ID)
        .orElseThrow().gloss());
    assertEquals(List.of(software), knowledgeBase.lookup(ownPrefix, WordNetPOS.NOUN));
    assertTrue(knowledgeBase.lookup(otherPrefix, WordNetPOS.NOUN).isEmpty());
    assertTrue(knowledgeBase.synset(otherPrefix + "-synset").isEmpty());
    assertEquals(List.of(ownPrefix + "-term"), knowledgeBase.synset(ownPrefix + "-synset")
        .orElseThrow().lemmas());
  }

  /**
   * Reads a one-lexicon resource document with a resolver and returns its only lexicon.
   *
   * @param document The document text.
   * @param resolver The resolver for base lexicons.
   * @return The only lexicon of the parsed resource.
   * @throws IOException Thrown if reading fails.
   */
  private WnLmfLexicon compose(String document, WnLmfResolver resolver)
      throws IOException {
    return WnLmfReader.readResource(bytes(document), RESOURCE_NAME, resolver)
        .lexicons().get(0);
  }

  /**
   * Wraps an extension of the base fixture in a resource element.
   *
   * @param body The extension content after the Extends element.
   * @return The document text.
   */
  private static String extension(String body) {
    return "<LexicalResource>"
        + extensionElement("ext", "1.0", BASE_REF, BASE_VERSION, body) + "</LexicalResource>";
  }

  /**
   * Builds one LexiconExtension element.
   *
   * @param id          The extension id, also used as the label.
   * @param version     The extension version.
   * @param baseRef     The Extends ref.
   * @param baseVersion The Extends version.
   * @param body        The content after the Extends element.
   * @return The element text.
   */
  private static String extensionElement(String id, String version, String baseRef,
      String baseVersion, String body) {
    return "<LexiconExtension id=\"" + id + "\" label=\"" + id + "\" language=\"en\" version=\""
        + version + "\"><Extends ref=\"" + baseRef + "\" version=\"" + baseVersion + "\"/>"
        + body + "</LexiconExtension>";
  }

  /**
   * Builds a Lexicon element with one entry and lexicon-prefixed IDs.
   *
   * @param id      The lexicon id, also used as the label.
   * @param version The lexicon version.
   * @param lemma   The single lemma.
   * @return The element text.
   */
  private static String lexicon(String id, String version, String lemma) {
    return "<Lexicon id=\"" + id + "\" label=\"" + id + "\" language=\"en\" version=\""
        + version + "\"><LexicalEntry id=\"" + id + "-" + lemma + "-n\">"
        + "<Lemma writtenForm=\"" + lemma + "\" partOfSpeech=\"n\"/>"
        + "<Sense id=\"" + id + "-" + lemma + "-n-1\" synset=\"" + id + "-1-n\"/>"
        + "</LexicalEntry><Synset id=\"" + id + "-1-n\" partOfSpeech=\"n\"/></Lexicon>";
  }

  /**
   * Builds the top document of an extension chain of the given depth.
   *
   * @param depth The number of extension hops above the plain base.
   * @return The document text of the deepest extension.
   */
  private String chainTop(int depth) {
    return "<LexicalResource>"
        + extensionElement("chain-" + depth, "1.0", chainRef(depth - 1), "1.0", "")
        + "</LexicalResource>";
  }

  /**
   * Builds a resolver holding every level of an extension chain: level 0 is a plain
   * one-entry lexicon named {@code chain-0}, and level {@code i} extends level
   * {@code i - 1}.
   *
   * @param depth The number of extension hops above the plain base.
   * @return The catalog resolver for the chain.
   */
  private CatalogResolver chainResolver(int depth) {
    final CatalogResolver resolver = new CatalogResolver().with("chain-0", "1.0",
        "<LexicalResource>" + lexicon("chain-0", "1.0", "process") + "</LexicalResource>");
    for (int i = 1; i < depth; i++) {
      resolver.with("chain-" + i, "1.0", "<LexicalResource>"
          + extensionElement("chain-" + i, "1.0", chainRef(i - 1), "1.0", "")
          + "</LexicalResource>");
    }
    return resolver;
  }

  /**
   * Names one link of the extension chain.
   *
   * @param level The chain level, zero for the plain base.
   * @return The lexicon id of the level.
   */
  private String chainRef(int level) {
    return "chain-" + level;
  }

  /**
   * Reads a classpath fixture into a string.
   *
   * @param name The resource name next to this class.
   * @return The fixture text.
   * @throws IOException Thrown if reading fails.
   */
  private String fixture(String name) throws IOException {
    try (InputStream in = WnLmfExtensionTest.class.getResourceAsStream(name)) {
      assertNotNull(in, "Fixture " + name + " must be on the test classpath");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * Registers the base fixture by lexicon ID and version.
   *
   * @return The catalog resolver.
   * @throws IOException Thrown if the fixture cannot be read.
   */
  private CatalogResolver baseResolver() throws IOException {
    return new CatalogResolver().with(BASE_REF, BASE_VERSION, fixture(BASE_FIXTURE));
  }

  /**
   * Opens a document text as a UTF-8 stream.
   *
   * @param document The document text.
   * @return A stream over the document's UTF-8 bytes.
   */
  private ByteArrayInputStream bytes(String document) {
    return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Asserts that a failure message names the expected fragment.
   *
   * @param error    The thrown failure.
   * @param expected The fragment the message must contain.
   */
  private void assertMessageContains(Exception error, String expected) {
    assertTrue(error.getMessage() != null && error.getMessage().contains(expected),
        "Expected message containing \"" + expected + "\" but was: " + error.getMessage());
  }

  /** An offline in-memory resolver over a (ref, version) keyed catalog of documents. */
  private static final class CatalogResolver implements WnLmfResolver {

    private final Map<String, String> catalog = new HashMap<>();
    private final List<TrackedStream> streams = new ArrayList<>();
    private int calls;

    /**
     * Registers a document under an exact id and version.
     *
     * @param ref      The lexicon id.
     * @param version  The lexicon version.
     * @param document The document text.
     * @return This resolver, for chaining.
     */
    private CatalogResolver with(String ref, String version, String document) {
      catalog.put(ref + "@" + version, document);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public WnLmfSource resolve(WnLmfDependency reference) throws IOException {
      calls++;
      final String document = catalog.get(reference.ref() + "@" + reference.version());
      if (document == null) {
        throw new IOException("No source for lexicon " + reference.ref()
            + " version " + reference.version());
      }
      final TrackedStream stream = new TrackedStream(document);
      streams.add(stream);
      return new WnLmfSource(reference.ref() + "-" + reference.version() + ".xml", stream);
    }

    /** {@return whether all opened streams are closed, with at least one opened} */
    private boolean allStreamsClosed() {
      return !streams.isEmpty() && streams.stream().allMatch(s -> s.closed);
    }
  }

  /** A byte stream that records whether it was closed. */
  private static final class TrackedStream extends ByteArrayInputStream {

    private boolean closed;

    /**
     * Opens a stream over the test XML.
     *
     * @param document The XML text.
     */
    private TrackedStream(String document) {
      super(document.getBytes(StandardCharsets.UTF_8));
    }

    /** {@inheritDoc} */
    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
