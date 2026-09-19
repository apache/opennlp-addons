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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import opennlp.dl.vectors.TokenVectorsDL;
import opennlp.tools.chunker.ChunkerAnnotator;
import opennlp.tools.chunker.ChunkerME;
import opennlp.tools.chunker.ChunkerModel;
import opennlp.tools.coref.CorefAnnotator;
import opennlp.tools.coref.CorefMention;
import opennlp.tools.coref.CorefModel;
import opennlp.tools.coref.CorefScorer;
import opennlp.tools.coref.CorefScores;
import opennlp.tools.coref.CorefScores.Score;
import opennlp.tools.coref.CorefTrainer;
import opennlp.tools.coref.TokenVectors;
import opennlp.tools.coref.WordVectors;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.Layers;
import opennlp.tools.formats.CorefEvalSupport;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.parser.ParserAnnotator;
import opennlp.tools.parser.ParserFactory;
import opennlp.tools.parser.ParserModel;
import opennlp.tools.util.ObjectStreamUtils;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;
import opennlp.tools.util.TrainingParameters;

/**
 * Scores the coreference annotator on OntoGUM, the OntoNotes-scheme conversion of the
 * Georgetown University Multilayer corpus, with the CoNLL metrics of {@link CorefScorer}.
 *
 * <p>Runs only when {@code opennlp.coref.gum.dir} names a GUM checkout: its
 * {@code splits.md} selects the documents and {@code coref/ontogum/conllu} supplies
 * gold sentences, tokens, Penn tags, and coreference chains through
 * {@link ConlluCorefDocumentStream}. The data is fetched by the
 * runner and never enters the repository; the annotations are CC-BY 4.0 and the texts
 * carry their own licenses, so check both before any use beyond measurement. Named
 * entities are predicted by the {@code en-ner-person}, {@code en-ner-location}, and
 * {@code en-ner-organization} models in {@code opennlp.coref.models.dir}, which
 * defaults to {@code ~/.opennlp}; noun phrase mentions come from the constituency
 * parser model {@code opennlp.coref.parser} names or, without one, from the chunker
 * model {@code opennlp.coref.chunker} names, by default {@code en-chunker.bin} in the
 * same directory, and are absent when none exists. The {@code # speaker} lines of the
 * spoken genres become a speakers layer unless {@code opennlp.coref.speakers} is
 * {@code false}. {@code opennlp.coref.split} chooses {@code dev} (the default),
 * {@code test}, {@code test2}, or {@code train}.</p>
 *
 * <p>Any corpus in the same CoNLL-U encoding, such as the CorefUD collection, replaces
 * the GUM split when {@code opennlp.coref.eval} names its files, comma separated, each
 * holding any number of {@code # newdoc} documents; {@code opennlp.coref.train.data}
 * names the training files the same way. Documents are named by their
 * {@code # newdoc id}. {@code opennlp.coref.encoder} names a directory holding an ONNX
 * encoder as {@code model.onnx} and {@code vocab.txt}, whose contextual token vectors
 * feed the ranker's span features at training and decoding time. With
 * {@code opennlp.coref.skip.redacted}, documents whose word forms GUM withholds, the
 * reddit texts shipped as underscores, are left out of scoring and training and the
 * split is recorded with a {@code -clean} suffix. Singleton entities of
 * the key are dropped before scoring, as the CoNLL-2012 scorer does, so corpora that
 * annotate singletons score on the same footing as OntoGUM, which has none.</p>
 *
 * <p>Sentences, tokens, and tags are gold, so the figures are not comparable one to
 * one with the fully predicted CoreNLP input of Zhu, Pradhan, and Zeldes (ACL 2021),
 * who report a CoNLL average of 39.7 for the deterministic Stanford system on the 2021
 * OntoGUM test set. Assertions are low regression floors; the logged scores and the
 * rows appended to {@code target/coref-eval-results.csv} are the measurement.</p>
 */
public class OntoGumCorefEvalTest {

  private static final Logger LOG = LoggerFactory.getLogger(OntoGumCorefEvalTest.class);

  private static final String GUM_DIR_PROPERTY = "opennlp.coref.gum.dir";
  private static final String SPLIT_PROPERTY = "opennlp.coref.split";
  private static final String CHUNKER_PROPERTY = "opennlp.coref.chunker";
  private static final String PARSER_PROPERTY = "opennlp.coref.parser";
  private static final String DUMP_PROPERTY = "opennlp.coref.dump";
  private static final String SPEAKERS_PROPERTY = "opennlp.coref.speakers";
  private static final String TRAIN_PROPERTY = "opennlp.coref.train";
  private static final String MODEL_PROPERTY = "opennlp.coref.model";
  private static final String ITERATIONS_PROPERTY = "opennlp.coref.iterations";
  private static final String CUTOFF_PROPERTY = "opennlp.coref.cutoff";
  private static final String THRESHOLD_PROPERTY = "opennlp.coref.threshold";
  private static final String ALGORITHM_PROPERTY = "opennlp.coref.algorithm";
  private static final String RANKING_PROPERTY = "opennlp.coref.ranking";
  private static final String EPOCHS_PROPERTY = "opennlp.coref.epochs";
  private static final String RATE_PROPERTY = "opennlp.coref.rate";
  private static final String L2_PROPERTY = "opennlp.coref.l2";
  private static final String VECTORS_PROPERTY = "opennlp.coref.vectors";
  private static final String EVAL_PROPERTY = "opennlp.coref.eval";
  private static final String TRAIN_DATA_PROPERTY = "opennlp.coref.train.data";
  private static final String ENCODER_PROPERTY = "opennlp.coref.encoder";
  private static final String SKIP_REDACTED_PROPERTY = "opennlp.coref.skip.redacted";

  /** The share of underscore-only tokens beyond which a document counts as redacted. */
  private static final double REDACTED_SHARE = 0.3;
  private static final String NEWDOC_ID = "# newdoc id =";
  private static final Path RESULTS_FILE = Path.of("target", "coref-eval-results.csv");

  /** A mention identity: the character span it covers. */
  private record Mention(int start, int end) {
  }

  /**
   * One gold-annotated document with its coreference key, the entities of two or more
   * mentions the scorer sees, and every gold entity including singletons for training.
   */
  private record GoldDocument(String name, Document document, List<Set<Mention>> key,
      List<Set<Mention>> gold) {
  }

  /** A read-only view over the per-split and per-genre scorers. */
  private record Result(String split, int documents, CorefScores scores,
      double corefSeconds) {
  }

  /** Runs when a GUM checkout or evaluation files are configured. */
  static boolean configured() {
    return System.getProperty(GUM_DIR_PROPERTY) != null
        || System.getProperty(EVAL_PROPERTY) != null;
  }

  @Test
  @EnabledIf("configured")
  void testScoresOntoGumSplit() throws IOException {
    final Path gum = System.getProperty(GUM_DIR_PROPERTY) == null
        ? null : Path.of(System.getProperty(GUM_DIR_PROPERTY));
    final Path models = CorefEvalSupport.modelsDirectory();
    final String evalFiles = System.getProperty(EVAL_PROPERTY);
    final String split = evalFiles != null ? "eval" : System.getProperty(SPLIT_PROPERTY, "dev");
    final String label = split + (skipRedacted() ? "-clean" : "");

    final List<NameFinderME> finders = CorefEvalSupport.nameFinders(models);
    final Path chunkerModel = Path.of(System.getProperty(CHUNKER_PROPERTY,
        models.resolve("en-chunker.bin").toString()));
    final String parserModel = System.getProperty(PARSER_PROPERTY);
    final DocumentAnnotator phraser = parserModel != null
        ? new ParserAnnotator(ParserFactory.create(new ParserModel(Path.of(parserModel))))
        : Files.exists(chunkerModel)
            ? new ChunkerAnnotator(new ChunkerME(new ChunkerModel(chunkerModel))) : null;
    final CorefAnnotator annotator = annotator(gum, finders, phraser);
    final CorefScorer all = new CorefScorer();
    final Map<String, CorefScorer> byGenre = new TreeMap<>();
    int documents = 0;
    long corefNanos = 0;
    final StringBuilder dump = new StringBuilder();
    final List<GoldDocument> golds = evalFiles != null
        ? readFiles(evalFiles) : gumDocuments(gum, split);
    Assertions.assertFalse(golds.isEmpty(), "no documents to score for " + split);
    for (final GoldDocument gold : golds) {
      final String name = gold.name();
      if (skipRedacted() && redacted(gold.document())) {
        LOG.info("skipping {}: redacted text", name);
        continue;
      }
      final Document tagged = CorefEvalSupport.withEntities(gold.document(), finders);
      final Document input = phraser == null ? tagged : phraser.annotate(tagged);
      final long started = System.nanoTime();
      final Document output = annotator.annotate(input);
      corefNanos += System.nanoTime() - started;
      final List<Set<Mention>> response = responsePartition(output);
      if (System.getProperty(DUMP_PROPERTY) != null) {
        dumpMentions(dump, gold, output, response);
      }
      all.add(gold.key(), response);
      byGenre.computeIfAbsent(genre(name), key -> new CorefScorer())
          .add(gold.key(), response);
      documents++;
    }
    final Result result = new Result(label, documents, all.scores(), corefNanos / 1e9);
    LOG.info("{} ({} documents): {}", label, documents, describe(result.scores()));
    LOG.info("coreference pass: {} documents/second",
        String.format(Locale.ROOT, "%.0f", documents / Math.max(result.corefSeconds(), 1e-9)));
    for (final Map.Entry<String, CorefScorer> genre : byGenre.entrySet()) {
      LOG.info("  {}: {}", genre.getKey(), describe(genre.getValue().scores()));
    }
    record(result);
    if (System.getProperty(DUMP_PROPERTY) != null) {
      Files.writeString(Path.of(System.getProperty(DUMP_PROPERTY)), dump.toString());
    }
    Assertions.assertTrue(result.scores().conll() > 0.05,
        "CoNLL average collapsed: " + result.scores().conll());
  }

  /**
   * Chooses the annotator: rule-based by default, ranking with the model
   * {@code opennlp.coref.model} names when it exists, or, with
   * {@code opennlp.coref.train}, ranking with a model trained on the train split and
   * saved to that path when one is given.
   */
  private static CorefAnnotator annotator(Path gum, List<NameFinderME> finders,
      DocumentAnnotator phraser) throws IOException {
    final String modelPath = System.getProperty(MODEL_PROPERTY);
    final double threshold = Double.parseDouble(
        System.getProperty(THRESHOLD_PROPERTY, Double.toString(CorefAnnotator.DEFAULT_THRESHOLD)));
    final WordVectors vectors = System.getProperty(VECTORS_PROPERTY) == null
        ? null : loadVectors(Path.of(System.getProperty(VECTORS_PROPERTY)));
    final TokenVectors encoder = encoder();
    final Set<String> personTypes = Set.of("person");
    final Set<String> neutralTypes = Set.of("organization", "location");
    if (System.getProperty(TRAIN_PROPERTY) == null) {
      if (modelPath == null || !Files.exists(Path.of(modelPath))) {
        return new CorefAnnotator();
      }
      return new CorefAnnotator(personTypes, neutralTypes, new CorefModel(Path.of(modelPath)),
          threshold, vectors, encoder);
    }
    final List<Document> training = new ArrayList<>();
    final String trainFiles = System.getProperty(TRAIN_DATA_PROPERTY);
    final List<GoldDocument> golds = trainFiles != null
        ? readFiles(trainFiles) : gumDocuments(gum, "train");
    Assertions.assertFalse(golds.isEmpty(), "no training documents");
    for (final GoldDocument gold : golds) {
      if (skipRedacted() && redacted(gold.document())) {
        continue;
      }
      final Document tagged = CorefEvalSupport.withEntities(gold.document(), finders);
      final Document input = phraser == null ? tagged : phraser.annotate(tagged);
      training.add(input.with(CorefAnnotator.GOLD_CHAINS, goldLayer(gold.gold())));
    }
    final CorefAnnotator rules = new CorefAnnotator(personTypes, neutralTypes, null,
        threshold, vectors, encoder);
    final long started = System.nanoTime();
    final CorefModel model;
    if (System.getProperty(RANKING_PROPERTY) != null) {
      model = CorefTrainer.trainRanking("eng", ObjectStreamUtils.createObjectStream(training),
          Integer.parseInt(System.getProperty(EPOCHS_PROPERTY,
              Integer.toString(CorefTrainer.DEFAULT_EPOCHS))),
          Double.parseDouble(System.getProperty(RATE_PROPERTY,
              Double.toString(CorefTrainer.DEFAULT_LEARNING_RATE))),
          Double.parseDouble(System.getProperty(L2_PROPERTY,
              Double.toString(CorefTrainer.DEFAULT_L2))), rules);
    } else {
      final TrainingParameters parameters = TrainingParameters.defaultParams();
      parameters.put(TrainingParameters.ALGORITHM_PARAM,
          System.getProperty(ALGORITHM_PROPERTY, "MAXENT"));
      parameters.put(TrainingParameters.ITERATIONS_PARAM,
          Integer.parseInt(System.getProperty(ITERATIONS_PROPERTY, "100")));
      parameters.put(TrainingParameters.CUTOFF_PARAM,
          Integer.parseInt(System.getProperty(CUTOFF_PROPERTY, "3")));
      model = CorefTrainer.train("eng", ObjectStreamUtils.createObjectStream(training),
          parameters, rules);
    }
    LOG.info("trained on {} documents in {} s", training.size(),
        String.format(Locale.ROOT, "%.1f", (System.nanoTime() - started) / 1e9));
    if (modelPath != null) {
      model.serialize(Path.of(modelPath));
    }
    return new CorefAnnotator(personTypes, neutralTypes, model, threshold, vectors, encoder);
  }

  private static boolean skipRedacted() {
    return Boolean.parseBoolean(System.getProperty(SKIP_REDACTED_PROPERTY));
  }

  /** {@return whether more than {@value #REDACTED_SHARE} of the tokens are underscores only} */
  private static boolean redacted(Document document) {
    final List<Annotation<String>> tokens = document.get(Layers.TOKENS);
    int underscores = 0;
    for (final Annotation<String> token : tokens) {
      boolean all = !token.value().isEmpty();
      for (int i = 0; all && i < token.value().length(); i++) {
        all = token.value().charAt(i) == '_';
      }
      if (all) {
        underscores++;
      }
    }
    return !tokens.isEmpty() && underscores > REDACTED_SHARE * tokens.size();
  }

  /** Opens the ONNX token encoder {@code opennlp.coref.encoder} names, or {@code null}. */
  private static TokenVectors encoder() throws IOException {
    final String directory = System.getProperty(ENCODER_PROPERTY);
    if (directory == null) {
      return null;
    }
    try {
      final long started = System.nanoTime();
      final TokenVectorsDL encoder = new TokenVectorsDL(
          Path.of(directory, "model.onnx").toFile(), Path.of(directory, "vocab.txt").toFile());
      LOG.info("encoder loaded in {} s",
          String.format(Locale.ROOT, "%.1f", (System.nanoTime() - started) / 1e9));
      return encoder;
    } catch (ai.onnxruntime.OrtException e) {
      throw new IOException("cannot load the encoder in " + directory, e);
    }
  }

  /**
   * Loads a GloVe-format text file, one lowercased word and its numbers per line, as
   * word vectors.
   */
  private static WordVectors loadVectors(Path file) throws IOException {
    final Map<String, float[]> table = new HashMap<>();
    int dimension = -1;
    try (java.io.BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        final int space = line.indexOf(' ');
        if (space <= 0) {
          continue;
        }
        final String[] parts = StringUtil.splitOnUnicodeWhitespace(line.substring(space + 1));
        final float[] vector = new float[parts.length];
        for (int d = 0; d < parts.length; d++) {
          vector[d] = Float.parseFloat(parts[d]);
        }
        if (dimension < 0) {
          dimension = vector.length;
        } else if (vector.length != dimension) {
          throw new IOException("word-vector dimension changed from " + dimension
              + " to " + vector.length + " in " + file);
        }
        table.put(line.substring(0, space), vector);
      }
    }
    if (dimension <= 0) {
      throw new IOException("word-vector file contains no vectors: " + file);
    }
    LOG.info("loaded {} word vectors from {}", table.size(), file);
    final int loadedDimension = dimension;
    return new WordVectors() {
      /** {@inheritDoc} */
      @Override
      public int dimension() {
        return loadedDimension;
      }

      /** {@inheritDoc} */
      @Override
      public float[] vector(String word) {
        return table.get(word);
      }
    };
  }

  /** Turns a key partition into a gold chains layer in text order. */
  private static List<Annotation<CorefMention>> goldLayer(List<Set<Mention>> key) {
    final List<Annotation<CorefMention>> layer = new ArrayList<>();
    for (int chain = 0; chain < key.size(); chain++) {
      for (final Mention mention : key.get(chain)) {
        layer.add(new Annotation<>(new Span(mention.start(), mention.end()),
            new CorefMention(chain, CorefMention.KIND_GOLD, CorefMention.NO_ENTITY)));
      }
    }
    layer.sort((a, b) -> Integer.compare(a.span().getStart(), b.span().getStart()));
    return layer;
  }

  /**
   * Appends one document's mention diagnostics: every key mention the response lacks,
   * every response mention the key lacks, and the mentions the annotator produced
   * including singletons, each with its surface text.
   */
  private static void dumpMentions(StringBuilder dump, GoldDocument gold, Document output,
      List<Set<Mention>> response) {
    final CharSequence text = gold.document().text();
    final Set<Mention> keyMentions = new HashSet<>();
    gold.key().forEach(keyMentions::addAll);
    final Set<Mention> responseMentions = new HashSet<>();
    response.forEach(responseMentions::addAll);
    final List<Annotation<CorefMention>> produced = output.get(CorefAnnotator.CHAINS);
    final Set<Mention> all = new HashSet<>();
    for (final Annotation<CorefMention> mention : produced) {
      all.add(new Mention(mention.span().getStart(), mention.span().getEnd()));
    }
    dump.append("# ").append(gold.name()).append(": key ").append(keyMentions.size())
        .append(", response ").append(responseMentions.size()).append(", produced ")
        .append(produced.size()).append(", key-in-produced ")
        .append(keyMentions.stream().filter(all::contains).count());
    if (output.layers().contains(ParserAnnotator.PHRASES)) {
      final Set<Mention> nounPhrases = new HashSet<>();
      for (final Annotation<ParserAnnotator.Phrase> phrase : output.get(ParserAnnotator.PHRASES)) {
        if ("NP".equals(phrase.value().label())) {
          nounPhrases.add(new Mention(phrase.span().getStart(), phrase.span().getEnd()));
        }
      }
      dump.append(", np-phrases ").append(nounPhrases.size()).append(", key-in-np ")
          .append(keyMentions.stream().filter(nounPhrases::contains).count());
    }
    if (output.layers().contains(ChunkerAnnotator.CHUNKS)) {
      final Set<Mention> chunks = new HashSet<>();
      for (final Annotation<String> chunk : output.get(ChunkerAnnotator.CHUNKS)) {
        if ("NP".equals(chunk.value())) {
          chunks.add(new Mention(chunk.span().getStart(), chunk.span().getEnd()));
        }
      }
      dump.append(", np-chunks ").append(chunks.size()).append(", key-in-chunk ")
          .append(keyMentions.stream().filter(chunks::contains).count());
    }
    dump.append('\n');
    for (final Mention mention : sorted(keyMentions)) {
      if (!responseMentions.contains(mention)) {
        dump.append(all.contains(mention) ? "MISSED-SINGLETON\t" : "MISSED\t")
            .append(text.subSequence(mention.start(), mention.end())).append('\n');
      }
    }
    for (final Mention mention : sorted(responseMentions)) {
      if (!keyMentions.contains(mention)) {
        dump.append("SPURIOUS\t").append(text.subSequence(mention.start(), mention.end()))
            .append('\n');
      }
    }
    if (output.layers().contains(ChunkerAnnotator.CHUNKS)) {
      for (final Annotation<String> chunk : output.get(ChunkerAnnotator.CHUNKS)) {
        dump.append("CHUNK\t").append(chunk.value()).append('\t')
            .append(text.subSequence(chunk.span().getStart(), chunk.span().getEnd()))
            .append('\n');
      }
    }
    for (final Annotation<CorefMention> mention : produced) {
      dump.append("PRODUCED\t").append(mention.value().chain()).append('\t')
          .append(mention.value().kind()).append('\t')
          .append(text.subSequence(mention.span().getStart(), mention.span().getEnd()))
          .append('\n');
    }
  }

  private static List<Mention> sorted(Set<Mention> mentions) {
    final List<Mention> sorted = new ArrayList<>(mentions);
    sorted.sort((a, b) -> a.start() != b.start()
        ? Integer.compare(a.start(), b.start()) : Integer.compare(b.end(), a.end()));
    return sorted;
  }

  /** Formats the metric triples on one line. */
  private static String describe(CorefScores scores) {
    return String.format(Locale.ROOT,
        "CoNLL %.1f | MUC %s | B3 %s | CEAFe %s | mentions %s",
        100 * scores.conll(), triple(scores.muc()), triple(scores.bCubed()),
        triple(scores.ceafE()), triple(scores.mentions()));
  }

  private static String triple(Score score) {
    return String.format(Locale.ROOT, "P %.1f R %.1f F %.1f",
        100 * score.precision(), 100 * score.recall(), 100 * score.f1());
  }

  /** Appends one CSV row per run so sweeps accumulate in the build directory. */
  private static void record(Result result) throws IOException {
    Files.createDirectories(RESULTS_FILE.getParent());
    if (!Files.exists(RESULTS_FILE)) {
      Files.writeString(RESULTS_FILE, "time,split,documents,conll,muc_p,muc_r,muc_f,"
          + "b3_p,b3_r,b3_f,ceafe_p,ceafe_r,ceafe_f,mention_p,mention_r,mention_f,"
          + "coref_seconds\n");
    }
    final CorefScores s = result.scores();
    Files.writeString(RESULTS_FILE, String.format(Locale.ROOT,
        "%s,%s,%d,%.4f,%s,%s,%s,%s,%.3f%n", Instant.now(), result.split(),
        result.documents(), s.conll(), csv(s.muc()), csv(s.bCubed()), csv(s.ceafE()),
        csv(s.mentions()), result.corefSeconds()),
        java.nio.file.StandardOpenOption.APPEND);
  }

  private static String csv(Score score) {
    return String.format(Locale.ROOT, "%.4f,%.4f,%.4f",
        score.precision(), score.recall(), score.f1());
  }

  /** The genre is the middle segment of a GUM document name; other corpora are one genre. */
  private static String genre(String name) {
    if (!name.startsWith("GUM_")) {
      return "corpus";
    }
    final int first = name.indexOf('_');
    final int second = name.indexOf('_', first + 1);
    return second < 0 ? name : name.substring(first + 1, second);
  }

  /**
   * Reads the document names listed under one {@code ## split} heading of
   * {@code splits.md}.
   */
  private static List<String> splitDocuments(Path splits, String split)
      throws IOException {
    final List<String> names = new ArrayList<>();
    boolean inSplit = false;
    for (final String line : Files.readAllLines(splits, StandardCharsets.UTF_8)) {
      if (line.startsWith("## ")) {
        inSplit = line.substring(3).trim().equals(split);
      } else if (inSplit && line.trim().startsWith("* ")) {
        names.add(line.trim().substring(2).trim());
      }
    }
    return names;
  }

  /**
   * Reads the OntoGUM documents of one {@code splits.md} split that the checkout holds.
   */
  private static List<GoldDocument> gumDocuments(Path gum, String split) throws IOException {
    Assertions.assertNotNull(gum, GUM_DIR_PROPERTY + " is required for split " + split);
    final List<String> names = splitDocuments(gum.resolve("splits.md"), split);
    Assertions.assertFalse(names.isEmpty(), "split " + split + " lists no documents");
    final List<GoldDocument> golds = new ArrayList<>();
    for (final String name : names) {
      final Path file = gum.resolve("coref/ontogum/conllu").resolve(name + ".conllu");
      if (!Files.exists(file)) {
        LOG.info("skipping {}: not in this checkout", name);
        continue;
      }
      final List<GoldDocument> read = readAll(file);
      Assertions.assertEquals(1, read.size(), name + " should hold one document");
      golds.add(new GoldDocument(name, read.get(0).document(), read.get(0).key(),
          read.get(0).gold()));
    }
    return golds;
  }

  /** Reads every document of the comma separated CoNLL-U files. */
  private static List<GoldDocument> readFiles(String files) throws IOException {
    final List<GoldDocument> golds = new ArrayList<>();
    for (final String file : CorefEvalSupport.splitOn(files, ',')) {
      if (!file.isBlank()) {
        golds.addAll(readAll(Path.of(file.trim())));
      }
    }
    return golds;
  }

  /**
   * Reads every document of one CoNLL-U file through {@link ConlluCorefDocumentStream}
   * with Penn tags, naming each by its {@code # newdoc id} and turning its gold chains
   * layer into the key partition without singletons.
   */
  private static List<GoldDocument> readAll(Path file) throws IOException {
    final Iterator<String> ids = documentIds(file).iterator();
    final List<GoldDocument> golds = new ArrayList<>();
    try (ConlluCorefDocumentStream stream = new ConlluCorefDocumentStream(
        () -> Files.newInputStream(file), ConlluTagset.X)) {
      Document document;
      while ((document = stream.read()) != null) {
        final String name = ids.hasNext() ? ids.next()
            : file.getFileName().toString() + "#" + golds.size();
        final Map<Integer, Set<Mention>> entities = new TreeMap<>();
        for (final Annotation<CorefMention> mention : document.get(CorefAnnotator.GOLD_CHAINS)) {
          entities.computeIfAbsent(mention.value().chain(), key -> new HashSet<>())
              .add(new Mention(mention.span().getStart(), mention.span().getEnd()));
        }
        Document input = Document.of(document.text())
            .with(Layers.SENTENCES, document.get(Layers.SENTENCES))
            .with(Layers.TOKENS, document.get(Layers.TOKENS))
            .with(Layers.POS_TAGS, document.get(Layers.POS_TAGS));
        if (document.layers().contains(CorefAnnotator.SPEAKERS)
            && !"false".equals(System.getProperty(SPEAKERS_PROPERTY))) {
          input = input.with(CorefAnnotator.SPEAKERS, document.get(CorefAnnotator.SPEAKERS));
        }
        final List<Set<Mention>> gold = disjoint(name, entities.values(), true);
        golds.add(new GoldDocument(name, input, disjoint(name, gold, false), gold));
      }
    }
    return golds;
  }

  /** Lists the {@code # newdoc id} values of a file in order. */
  private static List<String> documentIds(Path file) throws IOException {
    final List<String> ids = new ArrayList<>();
    for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (line.startsWith(NEWDOC_ID)) {
        ids.add(line.substring(NEWDOC_ID.length()).trim());
      }
    }
    return ids;
  }

  /**
   * Keeps the first entity's claim on a span the conversion filed under two ids, so the
   * key stays a partition; the scorer rejects overlapping entities. Singleton entities
   * are dropped unless kept, following the CoNLL-2012 scorer.
   */
  private static List<Set<Mention>> disjoint(String name,
      Collection<Set<Mention>> entities, boolean keepSingletons) {
    final Set<Mention> seen = new HashSet<>();
    final List<Set<Mention>> key = new ArrayList<>();
    int dropped = 0;
    for (final Set<Mention> entity : entities) {
      final Set<Mention> kept = new HashSet<>();
      for (final Mention mention : entity) {
        if (seen.add(mention)) {
          kept.add(mention);
        } else {
          dropped++;
        }
      }
      if (kept.size() > 1 || keepSingletons && !kept.isEmpty()) {
        key.add(kept);
      }
    }
    if (dropped > 0) {
      LOG.info("{}: dropped {} key mention(s) filed under two entities", name, dropped);
    }
    return key;
  }

  /** Groups the chains layer into entities of two or more mentions. */
  private static List<Set<Mention>> responsePartition(Document output) {
    final Map<Integer, Set<Mention>> chains = new TreeMap<>();
    for (final Annotation<CorefMention> mention : output.get(CorefAnnotator.CHAINS)) {
      chains.computeIfAbsent(mention.value().chain(), key -> new HashSet<>())
          .add(new Mention(mention.span().getStart(), mention.span().getEnd()));
    }
    final List<Set<Mention>> response = new ArrayList<>();
    for (final Collection<Mention> chain : chains.values()) {
      if (chain.size() > 1) {
        response.add(new HashSet<>(chain));
      }
    }
    return response;
  }
}
