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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.wordnet.WordNetPOS;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Smoke tests against complete, pinned OMW 2.0 releases fetched by the developer script. */
class WnLmfOmwIntegrationTest {

  /**
   * Loads a complete release directly and as an extension base.
   *
   * @param language The lexicon language.
   * @param relativeFile The XML path within the downloaded release.
   * @param lemma The lookup input.
   * @param expectedSynset The expected synset identifier.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest(name = "OMW 2.0 {0}")
  @MethodSource("wordnets")
  void testCompleteOmwRelease(String language, String relativeFile, String lemma,
                              String expectedSynset) throws IOException {
    final String fixtureRoot = System.getProperty("opennlp.wordnet.omwDir");
    Assumptions.assumeTrue(fixtureRoot != null && !fixtureRoot.isBlank(),
        "Run dev/test-omw-wordnets.sh to fetch and verify the pinned releases");

    final Path dictionary = Path.of(fixtureRoot).resolve(relativeFile);
    final WnLmfResource resource = WnLmfReader.readResource(dictionary);
    assertEquals(1, resource.lexicons().size());
    final WnLmfLexicon lexicon = resource.lexicons().get(0);
    assertEquals(language, lexicon.language());
    assertEquals(List.of(new WnLmfDependency("omw-en", "2.0")),
        lexicon.dependencies());
    assertEquals(expectedSynset,
        lexicon.knowledgeBase().lookup(lemma, WordNetPOS.NOUN).get(0).id());

    final String extension = "<LexicalResource><LexiconExtension id=\"extension\""
        + " label=\"extension\" language=\"" + language + "\" version=\"1\">"
        + "<Extends ref=\"" + lexicon.id() + "\" version=\"" + lexicon.version() + "\"/>"
        + "</LexiconExtension></LexicalResource>";
    final WnLmfResource composed = WnLmfReader.readResource(
        new ByteArrayInputStream(extension.getBytes(StandardCharsets.UTF_8)),
        "extension.xml", reference -> {
          assertEquals(lexicon.id(), reference.ref());
          assertEquals(lexicon.version(), reference.version());
          return new WnLmfSource(dictionary.toString(), Files.newInputStream(dictionary));
        });
    assertEquals(1, composed.lexicons().size());
    final WnLmfLexicon result = composed.lexicons().get(0);
    assertEquals(language, result.language());
    assertEquals(new WnLmfDependency(lexicon.id(), lexicon.version()),
        result.extensionOf().orElseThrow());
    assertEquals(lexicon.knowledgeBase().lookup(lemma, WordNetPOS.NOUN),
        result.knowledgeBase().lookup(lemma, WordNetPOS.NOUN));
  }

  /**
   * Supplies the pinned OMW releases and representative noun lookups.
   *
   * @return The release arguments.
   */
  private static Stream<Arguments> wordnets() {
    return Stream.of(
        Arguments.of("it", "omw-it/omw-it.xml", "cane", "omw-it-02084071-n"),
        Arguments.of("es", "omw-es/omw-es.xml", "perro", "omw-es-02084071-n"),
        Arguments.of("sv", "omw-sv/omw-sv.xml", "hund", "omw-sv-02084071-n"));
  }
}
