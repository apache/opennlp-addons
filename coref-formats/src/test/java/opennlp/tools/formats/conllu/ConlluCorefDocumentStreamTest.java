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

package opennlp.tools.formats.conllu;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.coref.CorefAnnotator;
import opennlp.tools.coref.CorefMention;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;
import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.Span;

public class ConlluCorefDocumentStreamTest {

  /** Two documents: nested and rich entity brackets, a speaker, a multiword range. */
  private static final String CONLLU = """
      # newdoc id = doc1
      # sent_id = doc1-1
      # speaker = Ann
      1\tByron\tByron\tPROPN\tNNP\t_\t2\tnsubj\t_\tEntity=(1)
      2\tmet\tmeet\tVERB\tVBD\t_\t0\troot\t_\t_
      3\this\the\tPRON\tPRP$\t_\t4\tnmod:poss\t_\tEntity=(2-person-new(1)
      4\tmother\tmother\tNOUN\tNN\t_\t2\tobj\t_\tEntity=2)|SpaceAfter=No
      5\t.\t.\tPUNCT\t.\t_\t2\tpunct\t_\t_

      # sent_id = doc1-2
      1-2\tShe's\t_\t_\t_\t_\t_\t_\t_\t_
      1\tShe\tshe\tPRON\tPRP\t_\t3\tnsubj\t_\tEntity=(2)
      2\t's\tbe\tAUX\tVBZ\t_\t3\tcop\t_\t_
      3\thome\thome\tNOUN\tNN\t_\t0\troot\t_\tSpaceAfter=No
      4\t.\t.\tPUNCT\t.\t_\t3\tpunct\t_\t_

      # newdoc id = doc2
      1\tAcme\tAcme\tPROPN\tNNP\t_\t2\tnsubj\t_\tEntity=(7)
      2\tgrew\tgrow\tVERB\tVBD\t_\t0\troot\t_\tSpaceAfter=No
      3\t.\t.\tPUNCT\t.\t_\t2\tpunct\t_\t_
      """;

  private ConlluCorefDocumentStream stream(String conllu, ConlluTagset tagset)
      throws IOException {
    return new ConlluCorefDocumentStream(
        () -> new ByteArrayInputStream(conllu.getBytes(StandardCharsets.UTF_8)), tagset);
  }

  @Test
  void testReadsDocumentsWithLayersAndGoldChains() throws IOException {
    try (ConlluCorefDocumentStream stream = stream(CONLLU, ConlluTagset.X)) {
      final Document first = stream.read();
      Assertions.assertEquals("Byron met his mother.\nShe 's home.", first.text());
      Assertions.assertEquals(List.of("Byron met his mother.", "She 's home."),
          first.get(Layers.SENTENCES).stream().map(Annotation::value).toList());
      Assertions.assertEquals(List.of("Byron", "met", "his", "mother", ".", "She", "'s",
          "home", "."), first.get(Layers.TOKENS).stream().map(Annotation::value).toList());
      Assertions.assertEquals(List.of("NNP", "VBD", "PRP$", "NN", ".", "PRP", "VBZ", "NN",
          "."), first.get(Layers.POS_TAGS).stream().map(Annotation::value).toList());
      Assertions.assertEquals(List.of(new Annotation<>(new Span(0, 21), "Ann")),
          first.get(CorefAnnotator.SPEAKERS));

      final List<Annotation<CorefMention>> chains = first.get(CorefAnnotator.GOLD_CHAINS);
      Assertions.assertEquals(List.of("Byron", "his mother", "his", "She"),
          chains.stream().map(a -> first.text().subSequence(
              a.span().getStart(), a.span().getEnd()).toString()).toList());
      Assertions.assertEquals(List.of(0, 1, 0, 1),
          chains.stream().map(a -> a.value().chain()).toList());
      Assertions.assertEquals(CorefMention.KIND_GOLD, chains.get(0).value().kind());

      final Document second = stream.read();
      Assertions.assertEquals("Acme grew.", second.text());
      Assertions.assertFalse(second.layers().contains(CorefAnnotator.SPEAKERS));
      Assertions.assertEquals(1, second.get(CorefAnnotator.GOLD_CHAINS).size());
      Assertions.assertNull(stream.read());

      stream.reset();
      Assertions.assertEquals("Byron met his mother.\nShe 's home.", stream.read().text());
    }
  }

  @Test
  void testUniversalTagsetReadsTheUposColumn() throws IOException {
    try (ConlluCorefDocumentStream stream = stream(CONLLU, ConlluTagset.U)) {
      Assertions.assertEquals("PROPN",
          stream.read().get(Layers.POS_TAGS).get(0).value());
    }
  }

  @Test
  void testFileWithoutNewdocIsOneDocument() throws IOException {
    final String single = "1\tAcme\tAcme\tPROPN\tNNP\t_\t0\troot\t_\tEntity=(3)\n";
    try (ConlluCorefDocumentStream stream = stream(single, ConlluTagset.X)) {
      Assertions.assertEquals("Acme", stream.read().text());
      Assertions.assertNull(stream.read());
    }
  }

  @Test
  void testSpaceAfterRequiresTheExactMiscKey() throws IOException {
    final String conllu = "1\tAcme\tAcme\tPROPN\tNNP\t_\t2\tnsubj\t_\tNotSpaceAfter=No\n"
        + "2\tgrew\tgrow\tVERB\tVBD\t_\t0\troot\t_\t_\n";
    try (ConlluCorefDocumentStream stream = stream(conllu, ConlluTagset.X)) {
      Assertions.assertEquals("Acme grew", stream.read().text());
    }
  }

  @Test
  void testRejectsEmptyEntityAttribute() throws IOException {
    final String conllu = "1\tAcme\tAcme\tPROPN\tNNP\t_\t0\troot\t_\tEntity=\n";
    try (ConlluCorefDocumentStream stream = stream(conllu, ConlluTagset.X)) {
      Assertions.assertThrows(InvalidFormatException.class, stream::read);
    }
  }

  @Test
  void testSkipsEmptyNodeRows() throws IOException {
    final String emptyNode = "0.1\thad\thave\tAUX\tVBD\t_\t_\t_\t_\t_\n"
        + "1\tspoke\tspeak\tVERB\tVBD\t_\t0\troot\t_\t_\n"
        + "1.1\thad\thave\tAUX\tVBD\t_\t_\t_\t_\t_\n";
    try (ConlluCorefDocumentStream stream = stream(emptyNode, ConlluTagset.X)) {
      final Document document = stream.read();
      Assertions.assertEquals("spoke", document.text());
      Assertions.assertEquals(List.of("spoke"),
          document.get(Layers.TOKENS).stream().map(Annotation::value).toList());
    }
  }

  @Test
  void testDiscontinuousMentionPartsJoinTheirEntity() throws IOException {
    final String parts = "1\tKim\tKim\tPROPN\tNNP\t_\t0\troot\t_\tEntity=(e5[1/2]-person-1)\n"
        + "2\tand\tand\tCCONJ\tCC\t_\t3\tcc\t_\t_\n"
        + "3\tLee\tLee\tPROPN\tNNP\t_\t1\tconj\t_\tEntity=(e5[2/2]-person-1)\n";
    try (ConlluCorefDocumentStream stream = stream(parts, ConlluTagset.X)) {
      final List<Annotation<CorefMention>> chains =
          stream.read().get(CorefAnnotator.GOLD_CHAINS);
      Assertions.assertEquals(2, chains.size());
      Assertions.assertEquals(chains.get(0).value().chain(), chains.get(1).value().chain());
    }
  }

  @Test
  void testNestedEntitiesAreNumberedByFirstMentionStart() throws IOException {
    final String nested = "1\tThe\tthe\tDET\tDT\t_\t2\tdet\t_\tEntity=(outer\n"
        + "2\tcompany\tcompany\tNOUN\tNN\t_\t3\tnsubj\t_\tEntity=(inner)\n"
        + "3\tgrew\tgrow\tVERB\tVBD\t_\t0\troot\t_\tEntity=outer)\n";
    try (ConlluCorefDocumentStream stream = stream(nested, ConlluTagset.X)) {
      final List<Annotation<CorefMention>> chains =
          stream.read().get(CorefAnnotator.GOLD_CHAINS);
      Assertions.assertEquals(new Span(0, 16), chains.get(0).span());
      Assertions.assertEquals(0, chains.get(0).value().chain());
      Assertions.assertEquals(new Span(4, 11), chains.get(1).span());
      Assertions.assertEquals(1, chains.get(1).value().chain());
    }
  }

  @Test
  void testSkipsDocumentBlockWithoutTokens() throws IOException {
    final String withEmptyBlock = "# newdoc id = metadata-only\n"
        + "# text = no token rows follow\n"
        + "# newdoc id = actual\n"
        + "1\tAcme\tAcme\tPROPN\tNNP\t_\t0\troot\t_\tEntity=(3)\n";
    try (ConlluCorefDocumentStream stream = stream(withEmptyBlock, ConlluTagset.X)) {
      Assertions.assertEquals("Acme", stream.read().text());
      Assertions.assertNull(stream.read());
    }
  }

  @Test
  void testRejectsUnbalancedBracketsAndShortLines() throws IOException {
    try (ConlluCorefDocumentStream open = stream(
        "1\tAcme\tAcme\tPROPN\tNNP\t_\t0\troot\t_\tEntity=(3\n", ConlluTagset.X)) {
      Assertions.assertThrows(InvalidFormatException.class, open::read);
    }
    try (ConlluCorefDocumentStream closed = stream(
        "1\tAcme\tAcme\tPROPN\tNNP\t_\t0\troot\t_\tEntity=3)\n", ConlluTagset.X)) {
      Assertions.assertThrows(InvalidFormatException.class, closed::read);
    }
    try (ConlluCorefDocumentStream missingClose = stream(
        "1\tAcme\tAcme\tPROPN\tNNP\t_\t2\tnsubj\t_\tEntity=(3\n"
            + "2\tgrew\tgrow\tVERB\tVBD\t_\t0\troot\t_\tEntity=3\n",
        ConlluTagset.X)) {
      Assertions.assertThrows(InvalidFormatException.class, missingClose::read);
    }
    try (ConlluCorefDocumentStream acrossSentences = stream(
        "1\tAcme\tAcme\tPROPN\tNNP\t_\t0\troot\t_\tEntity=(3\n\n"
            + "1\tIt\tit\tPRON\tPRP\t_\t0\troot\t_\tEntity=3)\n",
        ConlluTagset.X)) {
      Assertions.assertThrows(InvalidFormatException.class, acrossSentences::read);
    }
    try (ConlluCorefDocumentStream shortLine = stream("1\tAcme\n", ConlluTagset.X)) {
      Assertions.assertThrows(InvalidFormatException.class, shortLine::read);
    }
    try (ConlluCorefDocumentStream longLine = stream(
        "1\tAcme\tAcme\tPROPN\tNNP\t_\t0\troot\t_\t_\textra\n", ConlluTagset.X)) {
      Assertions.assertThrows(InvalidFormatException.class, longLine::read);
    }
    try (ConlluCorefDocumentStream emptyId = stream(
        "1\tAcme\tAcme\tPROPN\tNNP\t_\t0\troot\t_\tEntity=()\n", ConlluTagset.X)) {
      Assertions.assertThrows(InvalidFormatException.class, emptyId::read);
    }
  }

  @Test
  void testRejectsEmptyColumnsAndSpeakerLabels() throws IOException {
    try (ConlluCorefDocumentStream emptyForm = stream(
        "1\t\t_\tPROPN\tNNP\t_\t0\troot\t_\t_\n", ConlluTagset.X)) {
      Assertions.assertThrows(InvalidFormatException.class, emptyForm::read);
    }
    try (ConlluCorefDocumentStream emptyTag = stream(
        "1\tAcme\t_\tPROPN\t\t_\t0\troot\t_\t_\n", ConlluTagset.X)) {
      Assertions.assertThrows(InvalidFormatException.class, emptyTag::read);
    }
    try (ConlluCorefDocumentStream emptySpeaker = stream(
        "# speaker =   \n1\tAcme\t_\tPROPN\tNNP\t_\t0\troot\t_\t_\n",
        ConlluTagset.X)) {
      Assertions.assertThrows(InvalidFormatException.class, emptySpeaker::read);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "word", "0", "01", "1.0", "01.1", "1.01", "1.1.1",
      "1-1", "2-1", "0-1", "1-0", "1--2"
  })
  void testRejectsMalformedWordIds(String id) throws IOException {
    try (ConlluCorefDocumentStream stream = stream(
        id + "\tAcme\tAcme\tPROPN\tNNP\t_\t0\troot\t_\t_\n", ConlluTagset.X)) {
      Assertions.assertThrows(InvalidFormatException.class, stream::read);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "2\tAcme\tAcme\tPROPN\tNNP\t_\t0\troot\t_\t_\n",
      "1\tAcme\tAcme\tPROPN\tNNP\t_\t0\troot\t_\t_\n"
          + "1\tgrew\tgrow\tVERB\tVBD\t_\t0\troot\t_\t_\n",
      "1\tAcme\tAcme\tPROPN\tNNP\t_\t0\troot\t_\t_\n"
          + "3\tgrew\tgrow\tVERB\tVBD\t_\t0\troot\t_\t_\n"
  })
  void testRejectsNonconsecutiveWordIds(String conllu) throws IOException {
    try (ConlluCorefDocumentStream stream = stream(conllu, ConlluTagset.X)) {
      Assertions.assertThrows(InvalidFormatException.class, stream::read);
    }
  }

  @Test
  void testRejectsNullArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new ConlluCorefDocumentStream(null, ConlluTagset.X));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new ConlluCorefDocumentStream(
            () -> new ByteArrayInputStream(new byte[0]), null));
  }
}
