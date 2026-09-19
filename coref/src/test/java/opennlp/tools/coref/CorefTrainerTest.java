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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.chunker.ChunkerAnnotator;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;
import opennlp.tools.ml.maxent.GISModel;
import opennlp.tools.ml.model.AbstractModel;
import opennlp.tools.ml.model.Context;
import opennlp.tools.ml.model.Event;
import opennlp.tools.util.InsufficientTrainingDataException;
import opennlp.tools.util.ObjectStreamUtils;
import opennlp.tools.util.Span;
import opennlp.tools.util.TrainingParameters;

/**
 * Trains a ranking model on a small synthetic corpus where a neutral pronoun follows
 * an organization and a gendered pronoun follows a person, and checks the pairs, the
 * model round trip, and that the ranking annotator reproduces the pattern.
 */
public class CorefTrainerTest {

  /** Builds a two-sentence document: an entity, a verb, then a pronoun sentence. */
  private Document document(String name, String type, String verb, String pronoun,
      int goldChainOfPronoun) {
    final String text = name + " " + verb + ". " + pronoun + " grew.";
    final List<Annotation<String>> tokens = new ArrayList<>();
    final List<Annotation<String>> tags = new ArrayList<>();
    int cursor = 0;
    for (final String[] token : new String[][] {{name, "NNP"}, {verb, "VBD"}, {".", "."},
        {pronoun, "PRP"}, {"grew", "VBD"}, {".", "."}}) {
      final int start = text.indexOf(token[0], cursor);
      final Span span = new Span(start, start + token[0].length());
      tokens.add(new Annotation<>(span, token[0]));
      tags.add(new Annotation<>(span, token[1]));
      cursor = span.getEnd();
    }
    final int firstEnd = text.indexOf('.') + 1;
    final List<Annotation<CorefMention>> gold = new ArrayList<>();
    gold.add(new Annotation<>(new Span(0, name.length()),
        new CorefMention(0, CorefMention.KIND_GOLD, CorefMention.NO_ENTITY)));
    gold.add(new Annotation<>(tokens.get(3).span(),
        new CorefMention(goldChainOfPronoun, CorefMention.KIND_GOLD, CorefMention.NO_ENTITY)));
    return Document.of(text)
        .with(Layers.SENTENCES, List.of(
            new Annotation<>(new Span(0, firstEnd), "s"),
            new Annotation<>(new Span(firstEnd + 1, text.length()), "s")))
        .with(Layers.TOKENS, tokens)
        .with(Layers.POS_TAGS, tags)
        .with(Layers.ENTITIES, List.of(new Annotation<>(new Span(0, name.length()), type)))
        .with(CorefAnnotator.GOLD_CHAINS, gold);
  }

  /** A corpus where "It" always follows an organization and "He" always a person. */
  private List<Document> corpus() {
    final List<Document> corpus = new ArrayList<>();
    final String[] organizations = {"Acme", "Globex", "Initech", "Umbrella", "Hooli", "Vandelay"};
    final String[] people = {"Kowalczyk", "Nowak", "Okafor", "Tanaka", "Ivanov", "Schmidt"};
    for (int i = 0; i < organizations.length; i++) {
      corpus.add(document(organizations[i], "organization", "expanded", "It", 0));
      corpus.add(document(people[i], "person", "arrived", "He", 0));
      // a pronoun of the other class stays apart: its gold chain is its own
      corpus.add(document(organizations[i], "organization", "expanded", "He", 1));
      corpus.add(document(people[i], "person", "arrived", "It", 1));
    }
    return corpus;
  }

  private TrainingParameters parameters() {
    final TrainingParameters parameters = TrainingParameters.defaultParams();
    parameters.put(TrainingParameters.ITERATIONS_PARAM, 50);
    parameters.put(TrainingParameters.CUTOFF_PARAM, 1);
    return parameters;
  }

  /**
   * Builds one rankable mention after three pronouns. The first and third pronouns share
   * its gold chain, so their common pair feature cancels to zero and then reappears.
   */
  private Document interleavedGoldOptions() {
    final String text = "I you we Acme operate.";
    final List<Annotation<String>> tokens = new ArrayList<>();
    final List<Annotation<String>> tags = new ArrayList<>();
    int cursor = 0;
    for (final String[] token : new String[][] {{"I", "PRP"}, {"you", "PRP"},
        {"we", "PRP"}, {"Acme", "NNP"}, {"operate", "VBP"}, {".", "."}}) {
      final int start = text.indexOf(token[0], cursor);
      final Span span = new Span(start, start + token[0].length());
      tokens.add(new Annotation<>(span, token[0]));
      tags.add(new Annotation<>(span, token[1]));
      cursor = span.getEnd();
    }
    return Document.of(text)
        .with(Layers.SENTENCES, List.of(new Annotation<>(new Span(0, text.length()), "s")))
        .with(Layers.TOKENS, tokens)
        .with(Layers.POS_TAGS, tags)
        .with(Layers.ENTITIES, List.of(new Annotation<>(tokens.get(3).span(), "organization")))
        .with(CorefAnnotator.GOLD_CHAINS, List.of(
            new Annotation<>(tokens.get(0).span(),
                new CorefMention(0, CorefMention.KIND_GOLD, CorefMention.NO_ENTITY)),
            new Annotation<>(tokens.get(1).span(),
                new CorefMention(1, CorefMention.KIND_GOLD, CorefMention.NO_ENTITY)),
            new Annotation<>(tokens.get(2).span(),
                new CorefMention(0, CorefMention.KIND_GOLD, CorefMention.NO_ENTITY)),
            new Annotation<>(tokens.get(3).span(),
                new CorefMention(0, CorefMention.KIND_GOLD, CorefMention.NO_ENTITY))));
  }

  /** Builds a document whose detected antecedent is not the anaphor's gold antecedent. */
  private Document missingGoldAntecedent() {
    final String text = "Absent arrived. Zephyr spoke. He left.";
    final List<Annotation<String>> tokens = new ArrayList<>();
    final List<Annotation<String>> tags = new ArrayList<>();
    int cursor = 0;
    for (final String[] token : new String[][] {{"Absent", "NNP"}, {"arrived", "VBD"},
        {".", "."}, {"Zephyr", "NNP"}, {"spoke", "VBD"}, {".", "."},
        {"He", "PRP"}, {"left", "VBD"}, {".", "."}}) {
      final int start = text.indexOf(token[0], cursor);
      final Span span = new Span(start, start + token[0].length());
      tokens.add(new Annotation<>(span, token[0]));
      tags.add(new Annotation<>(span, token[1]));
      cursor = span.getEnd();
    }
    return Document.of(text)
        .with(Layers.SENTENCES, List.of(
            new Annotation<>(new Span(0, 15), "s"),
            new Annotation<>(new Span(16, 29), "s"),
            new Annotation<>(new Span(30, text.length()), "s")))
        .with(Layers.TOKENS, tokens)
        .with(Layers.POS_TAGS, tags)
        .with(Layers.ENTITIES, List.of(new Annotation<>(tokens.get(3).span(), "person")))
        .with(CorefAnnotator.GOLD_CHAINS, List.of(
            new Annotation<>(tokens.get(0).span(),
                new CorefMention(0, CorefMention.KIND_GOLD, CorefMention.NO_ENTITY)),
            new Annotation<>(tokens.get(3).span(),
                new CorefMention(1, CorefMention.KIND_GOLD, CorefMention.NO_ENTITY)),
            new Annotation<>(tokens.get(6).span(),
                new CorefMention(0, CorefMention.KIND_GOLD, CorefMention.NO_ENTITY))));
  }

  @Test
  void testPairsLabelTheGoldAntecedentAndTheRest() {
    final List<Event> events = CorefTrainer.pairs(
        document("Acme", "organization", "expanded", "It", 0), new CorefAnnotator());
    Assertions.assertEquals(1, events.size());
    Assertions.assertEquals(SieveResolver.LINK, events.get(0).getOutcome());
    Assertions.assertTrue(List.of(events.get(0).getContext()).contains("kinds=pronoun>entity"));

    final List<Event> apart = CorefTrainer.pairs(
        document("Acme", "organization", "expanded", "He", 1), new CorefAnnotator());
    Assertions.assertEquals(1, apart.size());
    Assertions.assertEquals(SieveResolver.APART, apart.get(0).getOutcome());
  }

  @Test
  void testPairsRejectDocumentWithoutGoldChains() {
    final Document plain = document("Acme", "organization", "expanded", "It", 0);
    final Document withoutGold = Document.of(plain.text())
        .with(Layers.SENTENCES, plain.get(Layers.SENTENCES))
        .with(Layers.TOKENS, plain.get(Layers.TOKENS))
        .with(Layers.POS_TAGS, plain.get(Layers.POS_TAGS))
        .with(Layers.ENTITIES, plain.get(Layers.ENTITIES));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.pairs(withoutGold, new CorefAnnotator()));
  }

  /** A gold mention span belongs to one chain. */
  @Test
  void testPairsRejectDuplicateGoldMentionSpans() {
    final Document base = document("Acme", "organization", "expanded", "It", 0);
    final Span repeated = base.get(CorefAnnotator.GOLD_CHAINS).get(0).span();
    final Document duplicate = Document.of(base.text())
        .with(Layers.SENTENCES, base.get(Layers.SENTENCES))
        .with(Layers.TOKENS, base.get(Layers.TOKENS))
        .with(Layers.POS_TAGS, base.get(Layers.POS_TAGS))
        .with(Layers.ENTITIES, base.get(Layers.ENTITIES))
        .with(CorefAnnotator.GOLD_CHAINS, List.of(
            new Annotation<>(repeated,
                new CorefMention(0, CorefMention.KIND_GOLD, CorefMention.NO_ENTITY)),
            new Annotation<>(repeated,
                new CorefMention(1, CorefMention.KIND_GOLD, CorefMention.NO_ENTITY))));

    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.pairs(duplicate, new CorefAnnotator()));
  }

  /** Gold chain identifiers follow first-mention order without gaps. */
  @Test
  void testPairsRejectNoncanonicalGoldChainIds() {
    final Document base = document("Acme", "organization", "expanded", "It", 0);
    final List<Annotation<CorefMention>> original = base.get(CorefAnnotator.GOLD_CHAINS);
    final Document sparse = Document.of(base.text())
        .with(Layers.SENTENCES, base.get(Layers.SENTENCES))
        .with(Layers.TOKENS, base.get(Layers.TOKENS))
        .with(Layers.POS_TAGS, base.get(Layers.POS_TAGS))
        .with(Layers.ENTITIES, base.get(Layers.ENTITIES))
        .with(CorefAnnotator.GOLD_CHAINS, List.of(
            new Annotation<>(original.get(0).span(),
                new CorefMention(7, CorefMention.KIND_GOLD,
                    CorefMention.NO_ENTITY)),
            new Annotation<>(original.get(1).span(),
                new CorefMention(7, CorefMention.KIND_GOLD,
                    CorefMention.NO_ENTITY))));

    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.pairs(sparse, new CorefAnnotator()));
  }

  @Test
  void testTrainedModelRoundTripsAndRanks() throws IOException {
    final CorefModel trained = CorefTrainer.train("eng",
        ObjectStreamUtils.createObjectStream(corpus()), parameters());
    Assertions.assertEquals("eng", trained.getLanguage());
    Assertions.assertEquals(2, trained.getPairModel().getNumOutcomes());

    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    trained.serialize(bytes);
    final CorefModel model = new CorefModel(new ByteArrayInputStream(bytes.toByteArray()));

    // The synthetic corpus is balanced, so the pairs are calibrated around one half,
    // unlike a corpus where unlinked pairs dominate and the default floor applies.
    final CorefAnnotator annotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), model, 0.5);
    final Document linked = annotator.annotate(
        document("Cyberdyne", "organization", "expanded", "It", 0));
    final List<Annotation<CorefMention>> chains = linked.get(CorefAnnotator.CHAINS);
    Assertions.assertEquals(2, chains.size());
    Assertions.assertEquals(chains.get(0).value().chain(), chains.get(1).value().chain());

    final Document apart = annotator.annotate(
        document("Cyberdyne", "organization", "expanded", "He", 1));
    final List<Annotation<CorefMention>> apartChains = apart.get(CorefAnnotator.CHAINS);
    Assertions.assertNotEquals(apartChains.get(0).value().chain(),
        apartChains.get(1).value().chain());
  }

  @Test
  void testRankingModelRoundTripsAndLinksAgainstTheNewChainOption() throws IOException {
    final CorefModel trained = CorefTrainer.trainRanking("eng",
        ObjectStreamUtils.createObjectStream(corpus()), new CorefAnnotator());
    Assertions.assertTrue(trained.isRanking());
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    trained.serialize(bytes);
    final CorefModel model = new CorefModel(new ByteArrayInputStream(bytes.toByteArray()));
    Assertions.assertTrue(model.isRanking());

    final CorefAnnotator annotator = new CorefAnnotator(model);
    final List<Annotation<CorefMention>> linked = annotator.annotate(
        document("Cyberdyne", "organization", "expanded", "It", 0)).get(CorefAnnotator.CHAINS);
    Assertions.assertEquals(linked.get(0).value().chain(), linked.get(1).value().chain());
    final List<Annotation<CorefMention>> apart = annotator.annotate(
        document("Cyberdyne", "organization", "expanded", "He", 1)).get(CorefAnnotator.CHAINS);
    Assertions.assertNotEquals(apart.get(0).value().chain(), apart.get(1).value().chain());
  }

  /** A pair-classifier score equal to the configured threshold is accepted. */
  @Test
  void testPairClassifierLinksAtThreshold() {
    final GISModel scoresOneHalf = new GISModel(new Context[0], new String[0],
        new String[] {SieveResolver.LINK, SieveResolver.APART});
    final CorefModel model = new CorefModel("eng", scoresOneHalf, Map.of());
    final CorefAnnotator annotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), model, 0.5);

    final List<Annotation<CorefMention>> chains = annotator.annotate(
        document("Cyberdyne", "organization", "expanded", "It", 0))
        .get(CorefAnnotator.CHAINS);
    Assertions.assertEquals(chains.get(0).value().chain(), chains.get(1).value().chain());
  }

  @Test
  void testRankingUpdatesAFeatureOnceWhenItsGradientReturnsToZero() throws IOException {
    final CorefModel model = CorefTrainer.trainRanking("eng",
        ObjectStreamUtils.createObjectStream(List.of(interleavedGoldOptions())),
        1, 0.1, 1.0, new CorefAnnotator());
    final int link = model.getPairModel().getIndex(SieveResolver.LINK);
    final double probability = model.getPairModel()
        .eval(new String[] {"kinds=entity>pronoun"})[link];
    final double weight = 0.1 * 0.25 / (0.25 + 1e-8);
    final double expected = Math.exp(weight) / (Math.exp(weight) + 1.0);

    Assertions.assertEquals(expected, probability, 1e-8);
  }

  @Test
  void testRankingOmitsFeaturesFromUnlearnableAnaphors() throws IOException {
    final CorefModel model = CorefTrainer.trainRanking("eng",
        ObjectStreamUtils.createObjectStream(List.of(
            document("Acme", "organization", "expanded", "It", 0),
            missingGoldAntecedent())),
        1, 0.1, 0.0, new CorefAnnotator());
    final Map<?, ?> predicates = (Map<?, ?>)
        ((AbstractModel) model.getPairModel()).getDataStructures()[1];

    Assertions.assertFalse(predicates.containsKey("antHead=zephyr"));
  }

  @Test
  void testPairwiseModelPreservesEncoderDimension() throws IOException {
    final CorefAnnotator annotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD,
        null, ENCODER);
    final CorefModel model = CorefTrainer.train("eng",
        ObjectStreamUtils.createObjectStream(corpus()), parameters(), annotator);
    Assertions.assertFalse(model.isRanking());
    Assertions.assertEquals(ENCODER.dimension(), model.getTokenVectorDimension());
  }

  @Test
  void testWordVectorsFeedSimilarityFeaturesForNominalAnaphors() {
    final WordVectors vectors = wordVectors(2, word -> switch (word) {
      case "acme", "firm" -> new float[] {1f, 0.1f};
      default -> null;
    });
    final CorefAnnotator annotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD, vectors);
    final String text = "Acme expanded. The firm grew.";
    final List<Annotation<String>> tokens = new ArrayList<>();
    final List<Annotation<String>> tags = new ArrayList<>();
    int cursor = 0;
    for (final String[] token : new String[][] {{"Acme", "NNP"}, {"expanded", "VBD"},
        {".", "."}, {"The", "DT"}, {"firm", "NN"}, {"grew", "VBD"}, {".", "."}}) {
      final int start = text.indexOf(token[0], cursor);
      final Span span = new Span(start, start + token[0].length());
      tokens.add(new Annotation<>(span, token[0]));
      tags.add(new Annotation<>(span, token[1]));
      cursor = span.getEnd();
    }
    final Document document = Document.of(text)
        .with(Layers.SENTENCES, List.of(new Annotation<>(new Span(0, 14), "s"),
            new Annotation<>(new Span(15, 29), "s")))
        .with(Layers.TOKENS, tokens)
        .with(Layers.POS_TAGS, tags)
        .with(Layers.ENTITIES, List.of(new Annotation<>(new Span(0, 4), "organization")))
        .with(ChunkerAnnotator.CHUNKS,
            List.of(new Annotation<>(new Span(0, 4), "NP"), new Annotation<>(new Span(15, 23), "NP")))
        .with(CorefAnnotator.GOLD_CHAINS, List.of(
            new Annotation<>(new Span(0, 4), new CorefMention(0, CorefMention.KIND_GOLD, -1)),
            new Annotation<>(new Span(15, 23), new CorefMention(0, CorefMention.KIND_GOLD, -1))));
    final List<Event> events = CorefTrainer.pairs(document, annotator);
    Assertions.assertEquals(1, events.size());
    final List<String> features = List.of(events.get(0).getContext());
    Assertions.assertTrue(features.contains("sim=same"), features.toString());
    Assertions.assertTrue(features.contains("clusterSim=same"), features.toString());
    // Without vectors the similarity features are absent.
    final List<String> plain = List.of(CorefTrainer.pairs(document, new CorefAnnotator())
        .get(0).getContext());
    Assertions.assertFalse(plain.stream().anyMatch(f -> f.startsWith("sim=")), plain.toString());
  }

  @Test
  void testWordVectorsMustReturnValidConsistentVectors() {
    final WordVectors empty = wordVectors(1, word -> new float[0]);
    final CorefAnnotator emptyAnnotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD, empty);
    Assertions.assertThrows(IllegalStateException.class,
        () -> CorefTrainer.pairs(firmDocument(), emptyAnnotator));

    final WordVectors nonfinite = wordVectors(1, word -> new float[] {Float.NaN});
    final CorefAnnotator nonfiniteAnnotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD,
        nonfinite);
    Assertions.assertThrows(IllegalStateException.class,
        () -> CorefTrainer.pairs(firmDocument(), nonfiniteAnnotator));

    final WordVectors inconsistent = wordVectors(2, word -> word.equals("firm")
        ? new float[] {1f, 0f}
        : new float[] {1f});
    final CorefAnnotator inconsistentAnnotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD,
        inconsistent);
    Assertions.assertThrows(IllegalStateException.class,
        () -> CorefTrainer.pairs(firmDocument(), inconsistentAnnotator));
  }

  @Test
  void testMissingWordVectorIsLookedUpOncePerDocument() {
    final int[] firmLookups = {0};
    final WordVectors vectors = wordVectors(1, word -> {
      if ("firm".equals(word)) {
        firmLookups[0]++;
      }
      return null;
    });
    final CorefAnnotator annotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD,
        vectors);

    CorefTrainer.pairs(firmDocument(), annotator);

    Assertions.assertEquals(1, firmLookups[0]);
  }

  @Test
  void testSimilarityHandlesLargeFiniteVectorComponents() {
    final WordVectors words = wordVectors(1, word -> switch (word) {
      case "acme" -> new float[] {-Float.MAX_VALUE};
      case "firm" -> new float[] {Float.MAX_VALUE};
      default -> null;
    });
    final CorefAnnotator wordAnnotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD,
        words);
    final List<String> wordFeatures = List.of(
        CorefTrainer.pairs(firmDocument(), wordAnnotator).get(0).getContext());
    Assertions.assertTrue(wordFeatures.contains("sim=low"), wordFeatures.toString());

    final TokenVectors tokens = tokenVectors(1, forms -> {
      final float[][] vectors = new float[forms.length][1];
      for (int t = 0; t < forms.length; t++) {
        if ("Acme".equals(forms[t])) {
          vectors[t][0] = -Float.MIN_NORMAL;
        } else if ("The".equals(forms[t]) || "firm".equals(forms[t])) {
          vectors[t][0] = Float.MAX_VALUE;
        }
      }
      return vectors;
    });
    final CorefAnnotator tokenAnnotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD,
        null, tokens);
    final List<String> tokenFeatures = List.of(
        CorefTrainer.pairs(firmDocument(), tokenAnnotator).get(0).getContext());
    Assertions.assertTrue(tokenFeatures.contains("ctx=neg"), tokenFeatures.toString());
  }

  @Test
  void testDenseFeaturesRejectArithmeticOverflow() {
    final TokenVectors tokens = tokenVectors(1, forms -> {
      final float[][] encoded = new float[forms.length][1];
      for (int t = 0; t < forms.length; t++) {
        encoded[t][0] = "Acme".equals(forms[t])
            ? Float.MAX_VALUE : -Float.MAX_VALUE;
      }
      return encoded;
    });
    final CorefAnnotator annotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD,
        null, tokens);

    Assertions.assertThrows(IllegalStateException.class,
        () -> CorefTrainer.pairs(firmDocument(), annotator));
    Assertions.assertThrows(IllegalStateException.class,
        () -> CorefTrainer.trainRanking("eng",
            ObjectStreamUtils.createObjectStream(List.of(firmDocument())),
            1, 0.05, 0.0, annotator));
  }

  /** Builds the two-sentence "Acme expanded. The firm grew." document with chunks and gold. */
  private Document firmDocument() {
    final String text = "Acme expanded. The firm grew.";
    final List<Annotation<String>> tokens = new ArrayList<>();
    final List<Annotation<String>> tags = new ArrayList<>();
    int cursor = 0;
    for (final String[] token : new String[][] {{"Acme", "NNP"}, {"expanded", "VBD"},
        {".", "."}, {"The", "DT"}, {"firm", "NN"}, {"grew", "VBD"}, {".", "."}}) {
      final int start = text.indexOf(token[0], cursor);
      final Span span = new Span(start, start + token[0].length());
      tokens.add(new Annotation<>(span, token[0]));
      tags.add(new Annotation<>(span, token[1]));
      cursor = span.getEnd();
    }
    return Document.of(text)
        .with(Layers.SENTENCES, List.of(new Annotation<>(new Span(0, 14), "s"),
            new Annotation<>(new Span(15, 29), "s")))
        .with(Layers.TOKENS, tokens)
        .with(Layers.POS_TAGS, tags)
        .with(Layers.ENTITIES, List.of(new Annotation<>(new Span(0, 4), "organization")))
        .with(ChunkerAnnotator.CHUNKS,
            List.of(new Annotation<>(new Span(0, 4), "NP"), new Annotation<>(new Span(15, 23), "NP")))
        .with(CorefAnnotator.GOLD_CHAINS, List.of(
            new Annotation<>(new Span(0, 4), new CorefMention(0, CorefMention.KIND_GOLD, -1)),
            new Annotation<>(new Span(15, 23), new CorefMention(0, CorefMention.KIND_GOLD, -1))));
  }

  /**
   * A two-dimensional test encoder: organization names and the
   * neutral pronoun point one way, person names and the male pronoun the other.
   */
  private static final TokenVectors ENCODER = tokenVectors(2, tokens -> {
    final float[][] vectors = new float[tokens.length][];
    for (int t = 0; t < tokens.length; t++) {
      final String word = tokens[t];
      if (word.equals("It") || word.equals("Acme") || word.equals("firm")
          || word.equals("Cyberdyne") || word.equals("Globex") || word.equals("Initech")
          || word.equals("Umbrella") || word.equals("Hooli") || word.equals("Vandelay")) {
        vectors[t] = new float[] {1f, 0f};
      } else if (word.equals("He") || Character.isUpperCase(word.charAt(0))) {
        vectors[t] = new float[] {0f, 1f};
      } else {
        vectors[t] = new float[] {0.5f, 0.5f};
      }
    }
    return vectors;
  });

  @Test
  void testTokenVectorsAddSpanFeaturesWithValues() {
    final CorefAnnotator annotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD, null,
        ENCODER);
    final List<Event> events = CorefTrainer.pairs(firmDocument(), annotator);
    Assertions.assertEquals(1, events.size());
    final Event event = events.get(0);
    final List<String> names = List.of(event.getContext());
    final float[] values = event.getValues();
    Assertions.assertNotNull(values);
    Assertions.assertEquals(names.size(), values.length);
    // "The firm" averages {0, 1} and {1, 0} to {0.5, 0.5}; "Acme" is {1, 0}: the
    // cosine 0.707 falls in the eighth tenth, and the products and differences follow
    Assertions.assertTrue(names.contains("ctx=7"), names.toString());
    Assertions.assertTrue(names.contains("ctxCluster=7"), names.toString());
    Assertions.assertEquals(0.5f, values[names.indexOf("vp0")], 1e-6f);
    Assertions.assertEquals(0f, values[names.indexOf("vp1")], 1e-6f);
    Assertions.assertEquals(0.5f, values[names.indexOf("vd0")], 1e-6f);
    Assertions.assertEquals(0.5f, values[names.indexOf("vd1")], 1e-6f);
    Assertions.assertEquals(1f, values[names.indexOf("kinds=nominal>entity")], 1e-6f);
    // Without an encoder the features are binary and carry no values.
    Assertions.assertNull(CorefTrainer.pairs(firmDocument(), new CorefAnnotator())
        .get(0).getValues());
  }

  @Test
  void testRankingModelTrainsAndDecodesWithTokenVectors() throws IOException {
    Assertions.assertEquals(2, ENCODER.dimension());
    final CorefAnnotator rules = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD, null,
        ENCODER);
    final CorefModel trained = CorefTrainer.trainRanking("eng",
        ObjectStreamUtils.createObjectStream(corpus()), rules);
    Assertions.assertEquals(2, trained.getTokenVectorDimension());
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    trained.serialize(bytes);
    final CorefModel model = new CorefModel(new ByteArrayInputStream(bytes.toByteArray()));
    Assertions.assertTrue(model.isRanking());
    Assertions.assertEquals(2, model.getTokenVectorDimension());
    Assertions.assertTrue(model.getPairModel().eval(new String[] {"vp0"}, new float[] {1f})
        [model.getPairModel().getIndex(SieveResolver.LINK)] > 0.5,
        "a shared direction should favor linking");

    final CorefAnnotator annotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), model, CorefAnnotator.DEFAULT_THRESHOLD, null,
        ENCODER);
    final List<Annotation<CorefMention>> linked = annotator.annotate(
        document("Cyberdyne", "organization", "expanded", "It", 0)).get(CorefAnnotator.CHAINS);
    Assertions.assertEquals(linked.get(0).value().chain(), linked.get(1).value().chain());
    final List<Annotation<CorefMention>> apart = annotator.annotate(
        document("Cyberdyne", "organization", "expanded", "He", 1)).get(CorefAnnotator.CHAINS);
    Assertions.assertNotEquals(apart.get(0).value().chain(), apart.get(1).value().chain());
  }

  @Test
  void testVectorDimensionsAreValidatedAtConstruction() {
    final GISModel pairModel = new GISModel(new Context[0], new String[0],
        new String[] {SieveResolver.LINK, SieveResolver.APART});
    final CorefModel contextual = new CorefModel("eng", pairModel, true, 2, Map.of());
    final CorefModel plain = new CorefModel("eng", pairModel, true, 0, Map.of());
    final TokenVectors wrongDimension = tokenVectors(1,
        tokens -> new float[tokens.length][1]);

    Assertions.assertDoesNotThrow(() -> new CorefAnnotator(Set.of("person"),
        Set.of("organization"), contextual, CorefAnnotator.DEFAULT_THRESHOLD, null,
        ENCODER));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefAnnotator(contextual));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefAnnotator(Set.of("person"), Set.of("organization"), contextual,
            CorefAnnotator.DEFAULT_THRESHOLD, null, wrongDimension));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefAnnotator(Set.of("person"), Set.of("organization"), plain,
            CorefAnnotator.DEFAULT_THRESHOLD, null, ENCODER));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefAnnotator(Set.of("person"), Set.of("organization"), null,
            CorefAnnotator.DEFAULT_THRESHOLD, wordVectors(0, word -> null), null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefAnnotator(Set.of("person"), Set.of("organization"), null,
            CorefAnnotator.DEFAULT_THRESHOLD, null,
            tokenVectors(0, tokens -> new float[tokens.length][0])));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefModel("eng", pairModel, true, -1, Map.of()));
  }

  @Test
  void testEncoderMustReturnOneVectorPerToken() {
    final TokenVectors short1 = tokenVectors(1, tokens -> new float[][] {{1f}});
    final CorefAnnotator annotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD, null, short1);
    Assertions.assertThrows(IllegalStateException.class,
        () -> annotator.annotate(firmDocument()));
    final TokenVectors none = tokenVectors(1, tokens -> null);
    final CorefAnnotator nullAnnotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD, null, none);
    Assertions.assertThrows(IllegalStateException.class,
        () -> nullAnnotator.annotate(firmDocument()));
  }

  @Test
  void testEncoderMustReturnNonemptyVectorsOfOneDimension() {
    final TokenVectors missing = tokenVectors(2, tokens -> {
      final float[][] vectors = new float[tokens.length][];
      for (int t = 0; t < vectors.length; t++) {
        vectors[t] = new float[] {1f, 0f};
      }
      vectors[1] = null;
      return vectors;
    });
    final CorefAnnotator missingAnnotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD, null,
        missing);
    Assertions.assertThrows(IllegalStateException.class,
        () -> missingAnnotator.annotate(firmDocument()));

    final TokenVectors empty = tokenVectors(2, tokens -> new float[tokens.length][0]);
    final CorefAnnotator emptyAnnotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD, null,
        empty);
    Assertions.assertThrows(IllegalStateException.class,
        () -> emptyAnnotator.annotate(firmDocument()));

    final TokenVectors ragged = tokenVectors(2, tokens -> {
      final float[][] vectors = new float[tokens.length][];
      for (int t = 0; t < vectors.length; t++) {
        vectors[t] = new float[] {1f, 0f};
      }
      vectors[1] = new float[] {1f};
      return vectors;
    });
    final CorefAnnotator raggedAnnotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD, null,
        ragged);
    Assertions.assertThrows(IllegalStateException.class,
        () -> raggedAnnotator.annotate(firmDocument()));

    final TokenVectors nonfinite = tokenVectors(2, tokens -> {
      final float[][] vectors = new float[tokens.length][2];
      vectors[0][0] = Float.POSITIVE_INFINITY;
      return vectors;
    });
    final CorefAnnotator nonfiniteAnnotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD, null,
        nonfinite);
    Assertions.assertThrows(IllegalStateException.class,
        () -> nonfiniteAnnotator.annotate(firmDocument()));
  }

  @Test
  void testRankingRejectsEncoderThatChangesDimensionBetweenDocuments() {
    final TokenVectors changing = tokenVectors(2, tokens -> {
      final int dimension = tokens[0].equals("Acme") || tokens[0].equals("It") ? 2 : 3;
      final float[][] vectors = new float[tokens.length][dimension];
      for (final float[] vector : vectors) {
        vector[0] = 1f;
      }
      return vectors;
    });
    final CorefAnnotator annotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD, null,
        changing);
    final List<Document> documents = List.of(
        document("Acme", "organization", "expanded", "It", 0),
        document("Kowalczyk", "person", "arrived", "He", 0));

    Assertions.assertThrows(IllegalStateException.class,
        () -> CorefTrainer.trainRanking("eng",
            ObjectStreamUtils.createObjectStream(documents), annotator));
  }

  @Test
  void testTokenVectorsHandleEntityWithoutAlignedToken() {
    final String text = "Ada spoke. She left.";
    final List<Annotation<String>> tokens = new ArrayList<>();
    int cursor = 0;
    for (final String form : new String[] {"spoke", ".", "She", "left", "."}) {
      final int start = text.indexOf(form, cursor);
      final Span span = new Span(start, start + form.length());
      tokens.add(new Annotation<>(span, form));
      cursor = span.getEnd();
    }
    final CorefAnnotator annotator = new CorefAnnotator(Set.of("person"),
        Set.of("organization", "location"), null, CorefAnnotator.DEFAULT_THRESHOLD, null,
        ENCODER);
    final Document document = Document.of(text)
        .with(Layers.SENTENCES, List.of(
            new Annotation<>(new Span(0, 10), "s"),
            new Annotation<>(new Span(11, 20), "s")))
        .with(Layers.TOKENS, tokens)
        .with(Layers.POS_TAGS, List.of(
            new Annotation<>(tokens.get(0).span(), "VBD"),
            new Annotation<>(tokens.get(1).span(), "."),
            new Annotation<>(tokens.get(2).span(), "PRP"),
            new Annotation<>(tokens.get(3).span(), "VBD"),
            new Annotation<>(tokens.get(4).span(), ".")))
        .with(Layers.ENTITIES, List.of(new Annotation<>(new Span(0, 3), "person")))
        .with(CorefAnnotator.GOLD_CHAINS, List.of(
            new Annotation<>(new Span(0, 3),
                new CorefMention(0, CorefMention.KIND_GOLD, CorefMention.NO_ENTITY)),
            new Annotation<>(new Span(11, 14),
                new CorefMention(0, CorefMention.KIND_GOLD, CorefMention.NO_ENTITY))));

    final List<Event> events = CorefTrainer.pairs(document, annotator);
    Assertions.assertEquals(1, events.size());
    Assertions.assertNotNull(events.get(0).getValues());
  }

  /** Three sentences: an entity, a definite phrase, and a second definite phrase. */
  private Document threeMentions(List<Annotation<CorefMention>> gold) {
    final String text = "Acme expanded. The firm grew. The company hired.";
    final List<Annotation<String>> tokens = new ArrayList<>();
    final List<Annotation<String>> tags = new ArrayList<>();
    int cursor = 0;
    for (final String[] token : new String[][] {{"Acme", "NNP"}, {"expanded", "VBD"},
        {".", "."}, {"The", "DT"}, {"firm", "NN"}, {"grew", "VBD"}, {".", "."},
        {"The", "DT"}, {"company", "NN"}, {"hired", "VBD"}, {".", "."}}) {
      final int start = text.indexOf(token[0], cursor);
      final Span span = new Span(start, start + token[0].length());
      tokens.add(new Annotation<>(span, token[0]));
      tags.add(new Annotation<>(span, token[1]));
      cursor = span.getEnd();
    }
    return Document.of(text)
        .with(Layers.SENTENCES, List.of(new Annotation<>(new Span(0, 14), "s"),
            new Annotation<>(new Span(15, 29), "s"), new Annotation<>(new Span(30, 48), "s")))
        .with(Layers.TOKENS, tokens)
        .with(Layers.POS_TAGS, tags)
        .with(Layers.ENTITIES, List.of(new Annotation<>(new Span(0, 4), "organization")))
        .with(ChunkerAnnotator.CHUNKS, List.of(new Annotation<>(new Span(0, 4), "NP"),
            new Annotation<>(new Span(15, 23), "NP"), new Annotation<>(new Span(30, 41), "NP")))
        .with(CorefAnnotator.GOLD_CHAINS, gold);
  }

  @Test
  void testGoldSingletonsMarkPartialAnnotationSoUnannotatedMentionsAreNotTaught() {
    // OntoNotes style: no singletons, so the unannotated "The company" is a true
    // non-mention or singleton and is taught to start a chain: 1 + 2 pairs.
    final Document complete = threeMentions(List.of(
        new Annotation<>(new Span(0, 4), new CorefMention(0, CorefMention.KIND_GOLD, -1)),
        new Annotation<>(new Span(15, 23), new CorefMention(0, CorefMention.KIND_GOLD, -1))));
    Assertions.assertEquals(3, CorefTrainer.pairs(complete, new CorefAnnotator()).size());
    // A corpus that annotates singletons annotates every mention of its scheme, so
    // "The company", absent from the gold layer, lies outside the scheme and is skipped.
    final Document partial = threeMentions(List.of(
        new Annotation<>(new Span(0, 4), new CorefMention(0, CorefMention.KIND_GOLD, -1)),
        new Annotation<>(new Span(15, 23), new CorefMention(1, CorefMention.KIND_GOLD, -1))));
    Assertions.assertEquals(1, CorefTrainer.pairs(partial, new CorefAnnotator()).size());
  }

  @Test
  void testRankingRejectsBadSettings() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.trainRanking("eng", ObjectStreamUtils.createObjectStream(corpus()),
            0, 0.1, 0.0, new CorefAnnotator()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.trainRanking("eng", ObjectStreamUtils.createObjectStream(corpus()),
            1, 0.0, 0.0, new CorefAnnotator()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.trainRanking("eng", ObjectStreamUtils.createObjectStream(corpus()),
            1, 0.1, -1.0, new CorefAnnotator()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.trainRanking("eng", ObjectStreamUtils.createObjectStream(corpus()),
            1, Double.POSITIVE_INFINITY, 0.0, new CorefAnnotator()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.trainRanking("eng", ObjectStreamUtils.createObjectStream(corpus()),
            1, 0.1, Double.POSITIVE_INFINITY, new CorefAnnotator()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.trainRanking("eng", null, new CorefAnnotator()));
  }

  @Test
  void testRankingRequiresTrainingInstances() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.trainRanking("eng",
            ObjectStreamUtils.createObjectStream(List.of()), new CorefAnnotator()));
  }

  @Test
  void testPairwiseRequiresTrainingEvents() {
    Assertions.assertThrows(InsufficientTrainingDataException.class,
        () -> CorefTrainer.train("eng",
            ObjectStreamUtils.createObjectStream(List.of()), parameters()));
  }

  @Test
  void testTrainRejectsNullArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.train("eng", null, parameters()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.train("eng", ObjectStreamUtils.createObjectStream(corpus()), null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.train("eng", ObjectStreamUtils.createObjectStream(corpus()),
            parameters(), null));
  }

  /** Model construction and both trainers reject a missing language code. */
  @Test
  void testModelAndTrainingRejectMissingLanguageCode() {
    final GISModel pairModel = new GISModel(new Context[0], new String[0],
        new String[] {SieveResolver.LINK, SieveResolver.APART});
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefModel(null, pairModel, null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefModel.requireLanguageCode(" "));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefModel(" ", pairModel, null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.train(null,
            ObjectStreamUtils.createObjectStream(corpus()), parameters()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.train(" ",
            ObjectStreamUtils.createObjectStream(corpus()), parameters()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.trainRanking(null,
            ObjectStreamUtils.createObjectStream(corpus()), new CorefAnnotator()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CorefTrainer.trainRanking(" ",
            ObjectStreamUtils.createObjectStream(corpus()), new CorefAnnotator()));
  }

  @Test
  void testAnnotatorRejectsNullModelAndBadThreshold() throws IOException {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefAnnotator((CorefModel) null));
    final CorefModel model = CorefTrainer.train("eng",
        ObjectStreamUtils.createObjectStream(corpus()), parameters());
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefAnnotator(Set.of("person"), Set.of("organization"), model, 1.5));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefModel("eng", null, null));
  }

  @Test
  void testModelRequiresExactlyTheCoreferenceOutcomes() {
    final Context[] parameters = new Context[0];
    final String[] predicates = new String[0];
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefModel("eng",
            new GISModel(parameters, predicates, new String[] {SieveResolver.LINK, "other"}),
            null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefModel("eng", new GISModel(parameters, predicates,
            new String[] {SieveResolver.LINK, SieveResolver.APART, "other"}), null));
  }

  @Test
  void testModelReadersRejectNullSources() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefModel((InputStream) null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefModel((File) null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CorefModel((Path) null));
  }

  /** Builds a word-vector provider with an explicit dimension. */
  private static WordVectors wordVectors(int dimension, Function<String, float[]> lookup) {
    return new WordVectors() {
      /** {@inheritDoc} */
      @Override
      public int dimension() {
        return dimension;
      }

      /** {@inheritDoc} */
      @Override
      public float[] vector(String word) {
        return lookup.apply(word);
      }
    };
  }

  /** Builds a token-vector provider with an explicit dimension. */
  private static TokenVectors tokenVectors(int dimension,
      Function<String[], float[][]> encoder) {
    return new TokenVectors() {
      /** {@inheritDoc} */
      @Override
      public int dimension() {
        return dimension;
      }

      /** {@inheritDoc} */
      @Override
      public float[][] vectors(String[] tokens) {
        return encoder.apply(tokens);
      }
    };
  }
}
