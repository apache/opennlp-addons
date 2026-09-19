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

package opennlp.tools.coref;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.chunker.ChunkerAnnotator;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;
import opennlp.tools.parser.ParserAnnotator;
import opennlp.tools.parser.ParserAnnotator.Phrase;
import opennlp.tools.util.Span;

/**
 * Pins the resolution decisions of the entity-centric sieves: agreement, nominal
 * mentions from the chunk layer, non-referential {@code it}, and the precise
 * constructs. Fixtures use Penn Treebank tags, the tag set the attribute rules read.
 */
public class CorefResolutionTest {

  /** A fixture under construction: whitespace-tokenized sentences with tags. */
  private static final class Fixture {
    private final StringBuilder text = new StringBuilder();
    private final List<Annotation<String>> sentences = new ArrayList<>();
    private final List<Annotation<String>> tokens = new ArrayList<>();
    private final List<Annotation<String>> tags = new ArrayList<>();
    private final List<Annotation<String>> entities = new ArrayList<>();
    private final List<Annotation<String>> chunks = new ArrayList<>();
    private final List<Annotation<Phrase>> phrases = new ArrayList<>();

    /** Appends one sentence of {@code form/TAG} tokens. */
    Fixture sentence(String... taggedTokens) {
      if (text.length() > 0) {
        text.append(' ');
      }
      final int start = text.length();
      for (int i = 0; i < taggedTokens.length; i++) {
        if (i > 0) {
          text.append(' ');
        }
        final int slash = taggedTokens[i].lastIndexOf('/');
        final String form = taggedTokens[i].substring(0, slash);
        final Span span = new Span(text.length(), text.length() + form.length());
        text.append(form);
        tokens.add(new Annotation<>(span, form));
        tags.add(new Annotation<>(span, taggedTokens[i].substring(slash + 1)));
      }
      sentences.add(new Annotation<>(new Span(start, text.length()), "s"));
      return this;
    }

    /** Marks the first occurrence of a phrase after the cursor as an entity. */
    Fixture entity(String phrase, String type) {
      entities.add(new Annotation<>(find(phrase, entities), type));
      return this;
    }

    /** Marks the first occurrence of a phrase after the cursor as a chunk. */
    Fixture chunk(String phrase, String type) {
      chunks.add(new Annotation<>(find(phrase, chunks), type));
      return this;
    }

    /** Adds a parse phrase over a text occurrence, headed by the given token. */
    Fixture phrase(String label, int start, String text, String head) {
      final int from = this.text.indexOf(text, start);
      Assertions.assertTrue(from >= 0, text + " is not in " + this.text);
      final int headStart = this.text.indexOf(head, from);
      phrases.add(new Annotation<>(new Span(from, from + text.length()),
          new Phrase(label, new Span(headStart, headStart + head.length()))));
      return this;
    }

    Document parsed() {
      return document(false).with(ParserAnnotator.PHRASES, phrases);
    }

    /** Builds a document with both chunk and parse phrase layers. */
    Document parsedWithChunks() {
      return document(true).with(ParserAnnotator.PHRASES, phrases);
    }

    private Span find(String phrase, List<Annotation<String>> after) {
      final int from = after.isEmpty() ? 0 : after.get(after.size() - 1).span().getEnd();
      final int start = text.indexOf(phrase, from);
      Assertions.assertTrue(start >= 0, phrase + " is not in " + text);
      return new Span(start, start + phrase.length());
    }

    Document document(boolean withChunks) {
      Document document = Document.of(text.toString())
          .with(Layers.SENTENCES, sentences)
          .with(Layers.TOKENS, tokens)
          .with(Layers.POS_TAGS, tags)
          .with(Layers.ENTITIES, entities);
      if (withChunks) {
        document = document.with(ChunkerAnnotator.CHUNKS, chunks);
      }
      return document;
    }
  }

  /** Resolves and renders every chain of two or more mentions as its surface forms. */
  private List<List<String>> chains(Document document) {
    final Document resolved = new CorefAnnotator().annotate(document);
    final List<Annotation<CorefMention>> layer = resolved.get(CorefAnnotator.CHAINS);
    final List<List<String>> chains = new ArrayList<>();
    for (final Annotation<CorefMention> mention : layer) {
      while (chains.size() <= mention.value().chain()) {
        chains.add(new ArrayList<>());
      }
      chains.get(mention.value().chain()).add(resolved.text().subSequence(
          mention.span().getStart(), mention.span().getEnd()).toString());
    }
    return chains.stream().filter(chain -> chain.size() > 1).toList();
  }

  @Test
  void testGenderedPronounSkipsNearerNameOfOtherGender() {
    final Document document = new Fixture()
        .sentence("John/NNP", "greeted/VBD", "Mary/NNP", "./.")
        .sentence("He/PRP", "smiled/VBD", "./.")
        .entity("John", "person").entity("Mary", "person")
        .document(false);
    Assertions.assertEquals(List.of(List.of("John", "He")), chains(document));
  }

  @Test
  void testFemininePronounSkipsNearerMaleName() {
    final Document document = new Fixture()
        .sentence("Mary/NNP", "greeted/VBD", "John/NNP", "./.")
        .sentence("She/PRP", "smiled/VBD", "./.")
        .entity("Mary", "person").entity("John", "person")
        .document(false);
    Assertions.assertEquals(List.of(List.of("Mary", "She")), chains(document));
  }

  @Test
  void testTitleSetsGenderWhenFirstNameIsAbsent() {
    final Document document = new Fixture()
        .sentence("Mr./NNP", "Kowalczyk/NNP", "met/VBD", "Mrs./NNP", "Nowak/NNP", "./.")
        .sentence("She/PRP", "spoke/VBD", "./.")
        .entity("Mr. Kowalczyk", "person").entity("Mrs. Nowak", "person")
        .document(false);
    Assertions.assertEquals(List.of(List.of("Mrs. Nowak", "She")), chains(document));
  }

  @Test
  void testFirstNameSetsGenderWhenNameFinderMissesBothPeople() {
    final Document document = new Fixture()
        .sentence("Alice/NNP", "met/VBD", "John/NNP", "./.")
        .sentence("She/PRP", "smiled/VBD", "./.")
        .chunk("Alice", "NP").chunk("John", "NP").chunk("She", "NP")
        .document(true);
    Assertions.assertEquals(List.of(List.of("Alice", "She")), chains(document));
  }

  @Test
  void testTitleSetsGenderWhenNameFinderMissesBothPeople() {
    final Document document = new Fixture()
        .sentence("Mrs./NNP", "Nowak/NNP", "met/VBD", "Mr./NNP", "Kowalczyk/NNP", "./.")
        .sentence("She/PRP", "spoke/VBD", "./.")
        .chunk("Mrs. Nowak", "NP").chunk("Mr. Kowalczyk", "NP").chunk("She", "NP")
        .document(true);
    Assertions.assertEquals(List.of(List.of("Mrs. Nowak", "She")), chains(document));
  }

  @Test
  void testUnknownProperNounPhraseRejectsNeutralPronoun() {
    final Document document = new Fixture()
        .sentence("Kowalczyk/NNP", "arrived/VBD", "./.")
        .sentence("It/PRP", "expanded/VBD", "./.")
        .chunk("Kowalczyk", "NP").chunk("It", "NP")
        .document(true);
    Assertions.assertTrue(chains(document).isEmpty());
  }

  @Test
  void testUnknownProperNounPhraseAcceptsAnimatePronoun() {
    final Document document = new Fixture()
        .sentence("Kowalczyk/NNP", "arrived/VBD", "./.")
        .sentence("She/PRP", "spoke/VBD", "./.")
        .chunk("Kowalczyk", "NP").chunk("She", "NP")
        .document(true);
    Assertions.assertEquals(List.of(List.of("Kowalczyk", "She")), chains(document));
  }

  @Test
  void testUnknownGenderStaysCompatibleWithBothPronouns() {
    final Document document = new Fixture()
        .sentence("Kowalczyk/NNP", "arrived/VBD", "./.")
        .sentence("She/PRP", "spoke/VBD", "./.")
        .entity("Kowalczyk", "person")
        .document(false);
    Assertions.assertEquals(List.of(List.of("Kowalczyk", "She")), chains(document));
  }

  @Test
  void testPluralPronounSkipsSingularPerson() {
    final Document document = new Fixture()
        .sentence("The/DT", "senators/NNS", "met/VBD", "John/NNP", "./.")
        .sentence("They/PRP", "voted/VBD", "./.")
        .entity("John", "person")
        .chunk("The senators", "NP").chunk("John", "NP").chunk("They", "NP")
        .document(true);
    Assertions.assertEquals(List.of(List.of("The senators", "They")), chains(document));
  }

  @Test
  void testCompoundPlaceNameIsNotTheBareName() {
    final Document document = new Fixture()
        .sentence("Kansas/NNP", "City/NNP", "grew/VBD", "./.")
        .sentence("Kansas/NNP", "voted/VBD", "./.")
        .sentence("Kansas/NNP", "City/NNP", "won/VBD", "./.")
        .entity("Kansas City", "location").entity("Kansas", "location")
        .entity("Kansas City", "location")
        .document(false);
    Assertions.assertEquals(List.of(List.of("Kansas City", "Kansas City")),
        chains(document));
  }

  @Test
  void testSurnameFindsFullPersonName() {
    final Document document = new Fixture()
        .sentence("Mary/NNP", "Jones/NNP", "spoke/VBD", "./.")
        .sentence("Jones/NNP", "left/VBD", "./.")
        .sentence("Mary/NNP", "returned/VBD", "./.")
        .entity("Mary Jones", "person").entity("Jones", "person").entity("Mary", "person")
        .document(false);
    Assertions.assertEquals(List.of(List.of("Mary Jones", "Jones", "Mary")),
        chains(document));
  }

  @Test
  void testNominalMentionLinksByHeadToEntityCluster() {
    final Document document = new Fixture()
        .sentence("Aberdeen/NNP", "Grammar/NNP", "School/NNP", "opened/VBD", "./.")
        .sentence("The/DT", "school/NN", "grew/VBD", "./.")
        .entity("Aberdeen Grammar School", "organization")
        .chunk("Aberdeen Grammar School", "NP").chunk("The school", "NP")
        .document(true);
    Assertions.assertEquals(List.of(List.of("Aberdeen Grammar School", "The school")),
        chains(document));
  }

  @Test
  void testChunkHeadedByEntityWidensTheMention() {
    final Document document = new Fixture()
        .sentence("The/DT", "poet/NN", "Byron/NNP", "wrote/VBD", "./.")
        .sentence("He/PRP", "travelled/VBD", "./.")
        .entity("Byron", "person")
        .chunk("The poet Byron", "NP").chunk("He", "NP")
        .document(true);
    final Document resolved = new CorefAnnotator().annotate(document);
    final List<Annotation<CorefMention>> layer = resolved.get(CorefAnnotator.CHAINS);
    Assertions.assertEquals(2, layer.size());
    Assertions.assertEquals(new Span(0, 14), layer.get(0).span());
    Assertions.assertEquals(CorefMention.KIND_ENTITY, layer.get(0).value().kind());
    Assertions.assertEquals(0, layer.get(0).value().entity());
    Assertions.assertEquals(List.of(List.of("The poet Byron", "He")), chains(document));
  }

  @Test
  void testStandaloneDemonstrativeIsAPronounMentionTheRulesLeaveAlone() {
    final Document document = new Fixture()
        .sentence("The/DT", "plan/NN", "failed/VBD", "./.")
        .sentence("That/DT", "was/VBD", "costly/JJ", "./.")
        .sentence("That/DT", "plan/NN", "hurt/VBD", "./.")
        .chunk("The plan", "NP").chunk("That", "NP").chunk("That plan", "NP")
        .document(true);
    final List<Annotation<CorefMention>> layer =
        new CorefAnnotator().annotate(document).get(CorefAnnotator.CHAINS);
    final List<String> forms = layer.stream().map(m -> document.text().subSequence(
        m.span().getStart(), m.span().getEnd()).toString()).toList();
    Assertions.assertEquals(List.of("The plan", "That", "That plan"), forms);
    Assertions.assertEquals(CorefMention.KIND_PRONOUN, layer.get(1).value().kind());
    // The rule-based pronoun sieve does not guess what a demonstrative refers to; the
    // head match links the two plans.
    Assertions.assertEquals(List.of(List.of("The plan", "That plan")), chains(document));
  }

  @Test
  void testNounPhraseWithOfPhraseIsACandidateBesideItsParts() {
    final Document document = new Fixture()
        .sentence("The/DT", "law/NN", "of/IN", "negligence/NN", "applies/VBZ", "./.")
        .sentence("The/DT", "law/NN", "of/IN", "negligence/NN", "is/VBZ", "old/JJ", "./.")
        .chunk("The law", "NP").chunk("of", "PP").chunk("negligence", "NP")
        .chunk("The law", "NP").chunk("of", "PP").chunk("negligence", "NP")
        .document(true);
    final List<List<String>> chains = chains(document);
    Assertions.assertTrue(chains.contains(
        List.of("The law of negligence", "The law of negligence")), chains.toString());
    Assertions.assertTrue(chains.contains(List.of("The law", "The law")), chains.toString());
  }

  @Test
  void testParserAndChunkNounPhrasesAreCombinedWithoutDuplicates() {
    final Document document = new Fixture()
        .sentence("The/DT", "cat/NN", "saw/VBD", "Alice/NNP", "./.")
        .chunk("The cat", "NP").chunk("Alice", "NP")
        .phrase("NP", 0, "Alice", "Alice")
        .parsedWithChunks();
    final Document resolved = new CorefAnnotator().annotate(document);

    Assertions.assertEquals(List.of("The cat", "Alice"),
        resolved.get(CorefAnnotator.CHAINS).stream()
            .map(mention -> resolved.text().subSequence(
                mention.span().getStart(), mention.span().getEnd()).toString())
            .toList());
  }

  @Test
  void testParserAppositiveKeepsDistinctChunkNounPhrases() {
    final Document document = new Fixture()
        .sentence("Joshua/NNP", "Norton/NNP", ",/,", "known/VBN", "as/IN",
            "Emperor/NNP", "Norton/NNP", "spoke/VBD", "./.")
        .chunk("Joshua Norton", "NP").chunk("Emperor Norton", "NP")
        .phrase("NP", 0, "Joshua Norton , known as Emperor Norton", "Norton")
        .phrase("NP", 2, "Emperor Norton", "Norton")
        .parsedWithChunks();
    final Document resolved = new CorefAnnotator().annotate(document);

    Assertions.assertEquals(List.of(
            "Joshua Norton , known as Emperor Norton", "Joshua Norton", "Emperor Norton"),
        resolved.get(CorefAnnotator.CHAINS).stream()
            .map(mention -> resolved.text().subSequence(
                mention.span().getStart(), mention.span().getEnd()).toString())
            .toList());
  }

  @Test
  void testCoordinatedNounPhrasesFormAPluralMention() {
    final Document document = new Fixture()
        .sentence("Kim/NNP", "and/CC", "Lee/NNP", "arrived/VBD", "./.")
        .sentence("They/PRP", "left/VBD", "./.")
        .entity("Kim", "person").entity("Lee", "person")
        .chunk("Kim", "NP").chunk("Lee", "NP").chunk("They", "NP")
        .document(true);
    Assertions.assertEquals(List.of(List.of("Kim and Lee", "They")), chains(document));
  }

  @Test
  void testIndefiniteNounPhraseIsNeverAnaphoric() {
    final Document document = new Fixture()
        .sentence("A/DT", "student/NN", "arrived/VBD", "./.")
        .sentence("A/DT", "student/NN", "left/VBD", "./.")
        .sentence("The/DT", "student/NN", "waved/VBD", "./.")
        .chunk("A student", "NP").chunk("A student", "NP").chunk("The student", "NP")
        .document(true);
    // The definite phrase links to the nearest indefinite antecedent; the two
    // indefinites stay apart.
    Assertions.assertEquals(List.of(List.of("A student", "The student")),
        chains(document));
  }

  @Test
  void testPleonasticItIsNoMention() {
    final Document document = new Fixture()
        .sentence("Acme/NNP", "grew/VBD", "./.")
        .sentence("It/PRP", "is/VBZ", "clear/JJ", "that/IN", "it/PRP", "won/VBD", "./.")
        .entity("Acme", "organization")
        .document(false);
    final Document resolved = new CorefAnnotator().annotate(document);
    final List<Annotation<CorefMention>> layer = resolved.get(CorefAnnotator.CHAINS);
    Assertions.assertEquals(2, layer.size());
    Assertions.assertEquals("it", resolved.text().subSequence(
        layer.get(1).span().getStart(), layer.get(1).span().getEnd()).toString());
    Assertions.assertEquals(List.of(List.of("Acme", "it")), chains(document));
  }

  @Test
  void testPronounNeverLinksToPhraseContainingIt() {
    final Document document = new Fixture()
        .sentence("Byron/NNP", "wrote/VBD", "./.")
        .sentence("His/PRP$", "mother/NN", "read/VBD", "./.")
        .entity("Byron", "person")
        .chunk("Byron", "NP").chunk("His mother", "NP")
        .document(true);
    Assertions.assertEquals(List.of(List.of("Byron", "His")), chains(document));
  }

  @Test
  void testReflexiveStaysInItsSentence() {
    final Document document = new Fixture()
        .sentence("Mary/NNP", "left/VBD", "./.")
        .sentence("John/NNP", "hurt/VBD", "himself/PRP", "./.")
        .entity("Mary", "person").entity("John", "person")
        .document(false);
    Assertions.assertEquals(List.of(List.of("John", "himself")), chains(document));
  }

  @Test
  void testAcronymFindsItsExpansion() {
    final Document document = new Fixture()
        .sentence("The/DT", "World/NNP", "Health/NNP", "Organization/NNP", "met/VBD", "./.")
        .sentence("WHO/NNP", "agreed/VBD", "./.")
        .entity("World Health Organization", "organization").entity("WHO", "organization")
        .document(false);
    Assertions.assertEquals(List.of(List.of("World Health Organization", "WHO")),
        chains(document));
  }

  @Test
  void testAcronymSupportsSupplementaryUppercaseInitials() {
    final String firstInitial = "\uD801\uDC00";
    final String secondInitial = "\uD801\uDC01";
    final String firstName = firstInitial + "lpha";
    final String secondName = secondInitial + "eta";
    final String fullName = firstName + " " + secondName;
    final String acronym = firstInitial + secondInitial;
    final Document document = new Fixture()
        .sentence((firstName + "/NNP"), (secondName + "/NNP"), "met/VBD", "./.")
        .sentence((acronym + "/NNP"), "agreed/VBD", "./.")
        .entity(fullName, "organization").entity(acronym, "organization")
        .document(false);
    Assertions.assertEquals(List.of(List.of(fullName, acronym)), chains(document));
  }

  @Test
  void testSharedInstitutionHeadWithDifferentNamesStaysApart() {
    final Document document = new Fixture()
        .sentence("Harvard/NNP", "University/NNP", "grew/VBD", "./.")
        .sentence("Stanford/NNP", "University/NNP", "grew/VBD", "./.")
        .sentence("President/NNP", "Obama/NNP", "spoke/VBD", "./.")
        .sentence("Barack/NNP", "Obama/NNP", "left/VBD", "./.")
        .entity("Harvard University", "organization")
        .entity("Stanford University", "organization")
        .entity("President Obama", "person").entity("Barack Obama", "person")
        .document(false);
    Assertions.assertEquals(List.of(List.of("President Obama", "Barack Obama")),
        chains(document));
  }

  @Test
  void testPronounReachesThreeSentencesBack() {
    final Document document = new Fixture()
        .sentence("Acme/NNP", "won/VBD", "./.")
        .sentence("Time/NN", "passed/VBD", "./.")
        .sentence("Markets/NNS", "moved/VBD", "./.")
        .sentence("It/PRP", "changed/VBD", "./.")
        .entity("Acme", "organization")
        .document(false);
    Assertions.assertEquals(List.of(List.of("Acme", "It")), chains(document));
  }

  @Test
  void testParsePhrasesGiveMaximalMentionsWithParserHeads() {
    final Document document = new Fixture()
        .sentence("The/DT", "school/NN", "of/IN", "Dr./NNP", "Glennie/NNP", "opened/VBD", "./.")
        .sentence("The/DT", "school/NN", "grew/VBD", "./.")
        .sentence("Glennie/NNP", "left/VBD", "./.")
        .entity("Dr. Glennie", "person").entity("Glennie", "person")
        .phrase("NP", 0, "The school of Dr. Glennie", "school")
        .phrase("NP", 0, "The school", "school")
        .phrase("NP", 0, "Dr. Glennie", "Glennie")
        .phrase("NP", 26, "The school", "school")
        .phrase("NP", 38, "Glennie", "Glennie")
        .parsed();
    final Document resolved = new CorefAnnotator().annotate(document);
    final List<Annotation<CorefMention>> layer = resolved.get(CorefAnnotator.CHAINS);
    // The inner "The school" shares its head with the maximal phrase and is dropped;
    // the nested person entity stays a mention of its own.
    Assertions.assertEquals(List.of("The school of Dr. Glennie", "Dr. Glennie",
            "The school", "Glennie"),
        layer.stream().map(a -> resolved.text().subSequence(
            a.span().getStart(), a.span().getEnd()).toString()).toList());
    Assertions.assertEquals(List.of(
            List.of("The school of Dr. Glennie", "The school"),
            List.of("Dr. Glennie", "Glennie")),
        chains(document));
  }

  @Test
  void testFirstPersonChainsPerQuotedSpeaker() {
    final Document document = new Fixture()
        .sentence("\"/``", "I/PRP", "am/VBP", "tired/JJ", ",/,", "\"/''", "said/VBD",
            "Mary/NNP", "./.")
        .sentence("\"/``", "I/PRP", "agree/VBP", "./.", "\"/''")
        .sentence("I/PRP", "left/VBD", "./.")
        .entity("Mary", "person")
        .document(false);
    // The attributed quotation joins Mary; the unattributed one is its own speaker; the
    // narrator's I is a third.
    Assertions.assertEquals(List.of(List.of("I", "Mary")), chains(document));
  }

  @Test
  void testSpeakersLayerChainsFirstAndSecondPersonPerSpeaker() {
    final Fixture fixture = new Fixture()
        .sentence("I/PRP", "think/VBP", "you/PRP", "know/VBP", "./.")
        .sentence("I/PRP", "do/VBP", "./.")
        .sentence("We/PRP", "agree/VBP", "./.")
        .sentence("I/PRP", "hope/VBP", "so/RB", "./.");
    final Document plain = fixture.document(false);
    final List<Annotation<String>> sentences = plain.get(Layers.SENTENCES);
    final Document document = plain.with(CorefAnnotator.SPEAKERS, List.of(
        new Annotation<>(sentences.get(0).span(), "A"),
        new Annotation<>(sentences.get(1).span(), "B"),
        new Annotation<>(sentences.get(2).span(), "A"),
        new Annotation<>(sentences.get(3).span(), "A")));
    Assertions.assertEquals(List.of(List.of("I", "I")), chains(document));
    final List<Annotation<CorefMention>> layer =
        new CorefAnnotator().annotate(document).get(CorefAnnotator.CHAINS);
    // A's two I mentions share a chain; B's I, A's you, and A's We stay apart.
    Assertions.assertEquals(5, layer.size());
    Assertions.assertEquals(layer.get(0).value().chain(), layer.get(4).value().chain());
    Assertions.assertNotEquals(layer.get(0).value().chain(), layer.get(2).value().chain());
    Assertions.assertNotEquals(layer.get(0).value().chain(), layer.get(3).value().chain());
  }

  @Test
  void testNarratorFirstPersonChainsWithoutSpeakers() {
    final Document document = new Fixture()
        .sentence("I/PRP", "wrote/VBD", "./.")
        .sentence("My/PRP$", "friend/NN", "read/VBD", "it/PRP", "./.")
        .sentence("I/PRP", "smiled/VBD", "./.")
        .document(false);
    Assertions.assertEquals(List.of(List.of("I", "My", "I")), chains(document));
  }

  @Test
  void testNeutralPronounSkipsPeopleAndAnimateNouns() {
    final Document document = new Fixture()
        .sentence("The/DT", "company/NN", "hired/VBD", "the/DT", "teacher/NN", "./.")
        .sentence("It/PRP", "grew/VBD", "./.")
        .chunk("The company", "NP").chunk("the teacher", "NP").chunk("It", "NP")
        .document(true);
    Assertions.assertEquals(List.of(List.of("The company", "It")), chains(document));
  }
}
