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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import opennlp.tools.postag.BilstmPOSModel;
import opennlp.tools.postag.BilstmPOSTagger;
import opennlp.tools.postag.BilstmPOSTrainer;
import opennlp.tools.postag.FeedforwardPOSModel;
import opennlp.tools.postag.FeedforwardPOSTagger;
import opennlp.tools.postag.FeedforwardPOSTrainer;
import opennlp.tools.postag.POSEvaluator;
import opennlp.tools.postag.POSSample;
import opennlp.tools.postag.POSTagger;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.wordvector.Glove;
import opennlp.tools.util.wordvector.WordVector;
import opennlp.tools.util.wordvector.WordVectorTable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trains POS taggers on a Universal Dependencies treebank and scores them on the
 * treebank's test split, reporting UPOS word accuracy.
 *
 * <p>Runs only when {@code opennlp.postag.ud.dir} names a directory containing
 * {@code train.conllu} and {@code test.conllu} (a UD treebank's splits, renamed or
 * linked). The data is downloaded by the runner and never enters the repository; check
 * the treebank's own license before training models for distribution. Assertions are
 * low regression floors; the logged scores are the measurement.</p>
 *
 * <p>Optional properties: {@code opennlp.postag.vectors} names a GloVe-format text file
 * consulted as the pretrained vector source, {@code opennlp.postag.lexicon} names a
 * one-word-per-line file whose words get stored vectors for tagging-time coverage,
 * {@code opennlp.postag.saveModel} names a file the trained BiLSTM model is written to,
 * {@code opennlp.postag.bilstm.multiTask} trains the BiLSTM with auxiliary heads, and
 * {@code opennlp.postag.bilstm.<field>} overrides any single
 * {@link BilstmPOSTrainer.Settings} field for a sweep run.</p>
 */
public class ConlluPOSTaggerEvalTest {

  private static final Logger logger =
      LoggerFactory.getLogger(ConlluPOSTaggerEvalTest.class);

  private static final String UD_DIR_PROPERTY = "opennlp.postag.ud.dir";
  private static final String VECTORS_PROPERTY = "opennlp.postag.vectors";
  private static final String LEXICON_PROPERTY = "opennlp.postag.lexicon";
  private static final String SAVE_MODEL_PROPERTY = "opennlp.postag.saveModel";

  /** Prefix of the per-field {@link BilstmPOSTrainer.Settings} sweep overrides. */
  private static final String BILSTM_PROPERTY_PREFIX = "opennlp.postag.bilstm.";

  private static final String TRAIN_FILE = "train.conllu";
  private static final String TEST_FILE = "test.conllu";

  private static final Path RESULTS_FILE = Path.of("target", "postag-eval-results.csv");
  private static final Path CONFUSION_FILE =
      Path.of("target", "postag-oov-confusion.csv");

  /**
   * The UPOS accuracy every run has to clear. It sits far below any plausible result:
   * it catches a broken tagger, and the logged score is the actual measurement.
   */
  private static final double ACCURACY_FLOOR = 0.85d;

  /**
   * The training count from which {@link #errorProfile} treats a word as in-vocabulary,
   * matching {@link BilstmPOSTrainer.Settings#wordCutoff()} of the default settings.
   */
  private static final int IN_VOCABULARY_CUTOFF = 2;

  /** The longest subword piece {@link #subwordPool} will try to match. */
  private static final int MAX_SUBWORD_LENGTH = 60;

  /** The prefix marking a non-initial subword piece in the vector table. */
  private static final String CONTINUATION_PREFIX = "##";

  @Test
  @EnabledIfSystemProperty(named = UD_DIR_PROPERTY, matches = ".+")
  void testFeedforwardBaseline() throws IOException {
    final Path dir = Path.of(System.getProperty(UD_DIR_PROPERTY));

    final long trainStart = System.currentTimeMillis();
    final FeedforwardPOSModel model;
    try (ObjectStream<POSSample> train = samples(dir.resolve(TRAIN_FILE))) {
      model = FeedforwardPOSTrainer.train(train, FeedforwardPOSTrainer.Settings.defaults());
    }
    logger.info("feedforward baseline trained in {} ms", System.currentTimeMillis() - trainStart);

    final double accuracy = evaluate(model, dir.resolve(TEST_FILE));
    logger.info("feedforward baseline UPOS accuracy {}", accuracy);
    record("ff-baseline", accuracy, "");

    assertTrue(accuracy > ACCURACY_FLOOR, "UPOS accuracy regressed below the floor");
  }

  @Test
  @EnabledIfSystemProperty(named = VECTORS_PROPERTY, matches = ".+")
  void testFeedforwardWithPretrainedVectors() throws IOException {
    final Path dir = Path.of(System.getProperty(UD_DIR_PROPERTY));
    final Function<CharSequence, float[]> vectors = vectors(
        Path.of(System.getProperty(VECTORS_PROPERTY)));

    final List<String> lexicon;
    final String lexiconPath = System.getProperty(LEXICON_PROPERTY);
    if (lexiconPath != null && !lexiconPath.isBlank()) {
      lexicon = Files.readAllLines(Path.of(lexiconPath));
    }
    else {
      lexicon = null;
    }

    final long trainStart = System.currentTimeMillis();
    final FeedforwardPOSModel model;
    try (ObjectStream<POSSample> train = samples(dir.resolve(TRAIN_FILE))) {
      if (lexicon == null) {
        model = FeedforwardPOSTrainer.train(train,
            FeedforwardPOSTrainer.Settings.defaults(), vectors);
      }
      else {
        model = FeedforwardPOSTrainer.train(train,
            FeedforwardPOSTrainer.Settings.defaults(), vectors, lexicon);
      }
    }
    logger.info("feedforward pretrained trained in {} ms",
        System.currentTimeMillis() - trainStart);

    final double accuracy = evaluate(model, dir.resolve(TEST_FILE));
    logger.info("feedforward pretrained UPOS accuracy {} (lexicon: {})", accuracy,
        lexicon == null ? "none" : lexicon.size() + " words");
    record("ff-pretrained" + (lexicon == null ? "" : "-lexicon"), accuracy, "");

    assertTrue(accuracy > ACCURACY_FLOOR, "UPOS accuracy regressed below the floor");
  }

  /**
   * Appends one measurement line to {@link #RESULTS_FILE} so sweep results survive
   * quiet console output.
   *
   * @param run The run label.
   * @param accuracy The measured value (UPOS accuracy, or tokens/s for throughput rows).
   * @param config The configuration label, empty when not applicable.
   * @throws IOException Thrown if the results file cannot be written.
   */
  private static void record(String run, double accuracy, String config)
      throws IOException {
    Files.createDirectories(RESULTS_FILE.getParent());
    Files.writeString(RESULTS_FILE,
        run + "," + accuracy + "," + config + "\n", StandardCharsets.UTF_8,
        Files.exists(RESULTS_FILE) ? StandardOpenOption.APPEND : StandardOpenOption.CREATE);
  }

  /**
   * Builds the BiLSTM training settings, letting sweep runs override any field with
   * an {@code opennlp.postag.bilstm.<field>} system property while defaulting to
   * {@link BilstmPOSTrainer.Settings#defaults()}.
   *
   * @return The effective settings. Never {@code null}.
   */
  private static BilstmPOSTrainer.Settings bilstmSettings() {
    final BilstmPOSTrainer.Settings base = BilstmPOSTrainer.Settings.defaults();
    return new BilstmPOSTrainer.Settings(
        intProperty("wordEmbeddingSize", base.wordEmbeddingSize()),
        intProperty("charEmbeddingSize", base.charEmbeddingSize()),
        intProperty("charHiddenSize", base.charHiddenSize()),
        intProperty("hiddenSize", base.hiddenSize()),
        intProperty("epochs", base.epochs()),
        intProperty("batchSize", base.batchSize()),
        doubleProperty("learningRate", base.learningRate()),
        doubleProperty("clipNorm", base.clipNorm()),
        doubleProperty("dropout", base.dropout()),
        intProperty("wordCutoff", base.wordCutoff()),
        intProperty("maxWordLength", base.maxWordLength()),
        longProperty("seed", base.seed()),
        intProperty("threads", base.threads()),
        doubleProperty("wordDropout", base.wordDropout()),
        intProperty("learningRateHalfLife", base.learningRateHalfLife()),
        booleanProperty("crf", base.crf()),
        intProperty("encoderLayers", base.encoderLayers()),
        doubleProperty("pretrainedDropout", base.pretrainedDropout()),
        doubleProperty("encoderDropout", base.encoderDropout()),
        doubleProperty("auxLossWeight", base.auxLossWeight()),
        doubleProperty("pretrainedTuning", base.pretrainedTuning()),
        booleanProperty("pretrainedAdapter", base.pretrainedAdapter()));
  }

  /**
   * Renders the settings as the compact configuration label the results file carries,
   * so a sweep row can be traced back to the run that produced it.
   *
   * @param s The settings of the run.
   * @return The label. Never {@code null}.
   */
  private static String bilstmLabel(BilstmPOSTrainer.Settings s) {
    return "h" + s.hiddenSize() + ";e" + s.epochs() + ";b" + s.batchSize() + ";lr"
        + s.learningRate() + ";d" + s.dropout() + ";seed" + s.seed() + ";t" + s.threads()
        + ";we" + s.wordEmbeddingSize() + ";ch" + s.charHiddenSize() + ";wd"
        + s.wordDropout() + ";hl" + s.learningRateHalfLife() + ";crf" + s.crf()
        + ";el" + s.encoderLayers() + ";pd" + s.pretrainedDropout() + ";ed"
        + s.encoderDropout() + ";aux" + s.auxLossWeight() + ";pt"
        + s.pretrainedTuning() + (s.pretrainedAdapter() ? ";pa" : "")
        + (multiTask() ? ";mt" : "");
  }

  /**
   * @return {@code true} when the BiLSTM run should train with the auxiliary heads.
   */
  private static boolean multiTask() {
    return Boolean.parseBoolean(
        System.getProperty(BILSTM_PROPERTY_PREFIX + "multiTask", "false"));
  }

  /**
   * Reads one {@link #BILSTM_PROPERTY_PREFIX} override.
   *
   * @param name The settings field name.
   * @param fallback The value to use when the property is unset.
   * @return The override, or {@code fallback}.
   */
  private static int intProperty(String name, int fallback) {
    final String value = System.getProperty(BILSTM_PROPERTY_PREFIX + name);
    return value != null ? Integer.parseInt(value) : fallback;
  }

  /**
   * Reads one {@link #BILSTM_PROPERTY_PREFIX} override.
   *
   * @param name The settings field name.
   * @param fallback The value to use when the property is unset.
   * @return The override, or {@code fallback}.
   */
  private static long longProperty(String name, long fallback) {
    final String value = System.getProperty(BILSTM_PROPERTY_PREFIX + name);
    return value != null ? Long.parseLong(value) : fallback;
  }

  /**
   * Reads one {@link #BILSTM_PROPERTY_PREFIX} override.
   *
   * @param name The settings field name.
   * @param fallback The value to use when the property is unset.
   * @return The override, or {@code fallback}.
   */
  private static double doubleProperty(String name, double fallback) {
    final String value = System.getProperty(BILSTM_PROPERTY_PREFIX + name);
    return value != null ? Double.parseDouble(value) : fallback;
  }

  /**
   * Reads one {@link #BILSTM_PROPERTY_PREFIX} override.
   *
   * @param name The settings field name.
   * @param fallback The value to use when the property is unset.
   * @return The override, or {@code fallback}.
   */
  private static boolean booleanProperty(String name, boolean fallback) {
    final String value = System.getProperty(BILSTM_PROPERTY_PREFIX + name);
    return value != null ? Boolean.parseBoolean(value) : fallback;
  }

  @Test
  @EnabledIfSystemProperty(named = UD_DIR_PROPERTY, matches = ".+")
  void testBilstmBaseline() throws IOException {
    final Path dir = Path.of(System.getProperty(UD_DIR_PROPERTY));

    final long trainStart = System.currentTimeMillis();
    final BilstmPOSModel model;
    final BilstmPOSTrainer.Settings settings = bilstmSettings();
    try (ObjectStream<POSSample> train = samples(dir.resolve(TRAIN_FILE))) {
      model = BilstmPOSTrainer.train(train, settings);
    }
    logger.info("bilstm baseline trained in {} ms", System.currentTimeMillis() - trainStart);

    final BilstmPOSTagger tagger = new BilstmPOSTagger(model);
    final double accuracy = evaluate(tagger, dir.resolve(TEST_FILE));
    final double throughput = measureThroughput(tagger, dir.resolve(TEST_FILE));
    logger.info("bilstm baseline UPOS accuracy {}, throughput {} tokens/s", accuracy,
        throughput);
    record("bilstm-baseline", accuracy, bilstmLabel(settings));
    record("bilstm-baseline-tokens-per-s", throughput, bilstmLabel(settings));

    assertTrue(accuracy > ACCURACY_FLOOR, "UPOS accuracy regressed below the floor");
  }

  @Test
  @EnabledIfSystemProperty(named = VECTORS_PROPERTY, matches = ".+")
  void testBilstmWithPretrainedVectors() throws IOException {
    final Path dir = Path.of(System.getProperty(UD_DIR_PROPERTY));
    final Function<CharSequence, float[]> vectors = vectors(
        Path.of(System.getProperty(VECTORS_PROPERTY)));

    final List<String> lexicon;
    final String lexiconPath = System.getProperty(LEXICON_PROPERTY);
    if (lexiconPath != null && !lexiconPath.isBlank()) {
      lexicon = Files.readAllLines(Path.of(lexiconPath));
    }
    else {
      lexicon = null;
    }

    final long trainStart = System.currentTimeMillis();
    final BilstmPOSModel model;
    final BilstmPOSTrainer.Settings settings = bilstmSettings();
    if (multiTask()) {
      try (ObjectStream<BilstmPOSTrainer.MultiTaskSample> train =
          new MultiTaskSampleStream(dir.resolve(TRAIN_FILE))) {
        model = BilstmPOSTrainer.trainMultiTask(train, settings, vectors, lexicon);
      }
    }
    else {
      try (ObjectStream<POSSample> train = samples(dir.resolve(TRAIN_FILE))) {
        if (lexicon == null) {
          model = BilstmPOSTrainer.train(train, settings, vectors);
        }
        else {
          model = BilstmPOSTrainer.train(train, settings, vectors, lexicon);
        }
      }
    }
    logger.info("bilstm pretrained trained in {} ms", System.currentTimeMillis() - trainStart);

    final BilstmPOSTagger tagger = new BilstmPOSTagger(model);
    final double accuracy = evaluate(tagger, dir.resolve(TEST_FILE));
    final double throughput = measureThroughput(tagger, dir.resolve(TEST_FILE));
    final double[] profile = errorProfile(tagger, dir.resolve(TRAIN_FILE),
        dir.resolve(TEST_FILE), vectors, lexicon);
    logger.info("bilstm pretrained UPOS accuracy {} (lexicon: {}), throughput {} tokens/s",
        accuracy, lexicon == null ? "none" : lexicon.size() + " words", throughput);
    logger.info("bilstm pretrained IV {} OOV {} (vector-covered OOV {}, uncovered OOV {})",
        profile[0], profile[1], profile[2], profile[3]);
    final String runLabel = "bilstm-pretrained" + (lexicon == null ? "" : "-lexicon");
    final String config = bilstmLabel(settings);
    record(runLabel, accuracy, config);
    record(runLabel + "-tokens-per-s", throughput, config);
    record(runLabel + "-iv-acc", profile[0], config);
    record(runLabel + "-oov-acc", profile[1], config);
    record(runLabel + "-oov-vec-acc", profile[2], config);
    record(runLabel + "-oov-novec-acc", profile[3], config);

    final String saveModel = System.getProperty(SAVE_MODEL_PROPERTY);
    if (saveModel != null && !saveModel.isBlank()) {
      model.serialize(Path.of(saveModel));
      logger.info("model written to {}", saveModel);
    }

    assertTrue(accuracy > ACCURACY_FLOOR, "UPOS accuracy regressed below the floor");
  }

  /**
   * Opens a CoNLL-U file as a stream of word-based POS samples.
   *
   * @param conllu The CoNLL-U file.
   * @return The sample stream; the caller closes it. Never {@code null}.
   * @throws IOException Thrown if the file cannot be opened.
   */
  private static ObjectStream<POSSample> samples(Path conllu) throws IOException {
    return new WordBasedPOSSampleStream(conllu);
  }

  /**
   * Reads a CoNLL-U file as POS samples over syntactic words: multiword-token range
   * lines (ids containing {@code -}) and empty nodes (ids containing {@code .}) are
   * dropped and their parts kept as individual tokens, the Universal Dependencies
   * convention for word-based tagging evaluation. {@link ConlluStream} is not used
   * because it merges multiword tokens into surface forms with composite tags, which
   * no tagger can be expected to reproduce.
   */
  private static final class WordBasedPOSSampleStream implements ObjectStream<POSSample> {

    private final BufferedReader reader;
    private final List<POSSample> pending = new ArrayList<>();
    private int next;
    private boolean loaded;

    private WordBasedPOSSampleStream(Path conllu) throws IOException {
      reader = Files.newBufferedReader(conllu, StandardCharsets.UTF_8);
    }

    @Override
    public POSSample read() throws IOException {
      if (!loaded) {
        load();
        loaded = true;
      }
      return next < pending.size() ? pending.get(next++) : null;
    }

    private void load() throws IOException {
      List<String> tokens = new ArrayList<>();
      List<String> tags = new ArrayList<>();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("#")) {
          continue;
        }
        if (line.isBlank()) {
          emit(tokens, tags);
          continue;
        }
        final String[] fields = line.split("\t");
        if (fields.length < 4 || fields[0].contains("-") || fields[0].contains(".")) {
          continue;
        }
        tokens.add(fields[1]);
        tags.add(fields[3]);
      }
      emit(tokens, tags);
    }

    private void emit(List<String> tokens, List<String> tags) {
      if (!tokens.isEmpty()) {
        pending.add(new POSSample(tokens.toArray(new String[0]),
            tags.toArray(new String[0])));
        tokens.clear();
        tags.clear();
      }
    }

    @Override
    public void reset() throws IOException, UnsupportedOperationException {
      throw new UnsupportedOperationException("reset is not supported");
    }

    @Override
    public void close() throws IOException {
      reader.close();
    }
  }

  /**
   * Reads a CoNLL-U file as multi-task samples over syntactic words: the same
   * word-based convention as {@link WordBasedPOSSampleStream}, additionally carrying
   * the XPOS column and the FEATS column as composite auxiliary labels.
   */
  private static final class MultiTaskSampleStream
      implements ObjectStream<BilstmPOSTrainer.MultiTaskSample> {

    private final BufferedReader reader;
    private final List<BilstmPOSTrainer.MultiTaskSample> pending = new ArrayList<>();
    private int next;
    private boolean loaded;

    private MultiTaskSampleStream(Path conllu) throws IOException {
      reader = Files.newBufferedReader(conllu, StandardCharsets.UTF_8);
    }

    @Override
    public BilstmPOSTrainer.MultiTaskSample read() throws IOException {
      if (!loaded) {
        load();
        loaded = true;
      }
      return next < pending.size() ? pending.get(next++) : null;
    }

    private void load() throws IOException {
      List<String> tokens = new ArrayList<>();
      List<String> tags = new ArrayList<>();
      List<String> xpos = new ArrayList<>();
      List<String> feats = new ArrayList<>();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("#")) {
          continue;
        }
        if (line.isBlank()) {
          emit(tokens, tags, xpos, feats);
          continue;
        }
        final String[] fields = line.split("\t");
        if (fields.length < 6 || fields[0].contains("-") || fields[0].contains(".")) {
          continue;
        }
        tokens.add(fields[1]);
        tags.add(fields[3]);
        xpos.add(fields[4]);
        feats.add(fields[5]);
      }
      emit(tokens, tags, xpos, feats);
    }

    private void emit(List<String> tokens, List<String> tags, List<String> xpos,
        List<String> feats) {
      if (!tokens.isEmpty()) {
        pending.add(new BilstmPOSTrainer.MultiTaskSample(
            tokens.toArray(new String[0]), tags.toArray(new String[0]),
            xpos.toArray(new String[0]), feats.toArray(new String[0])));
        tokens.clear();
        tags.clear();
        xpos.clear();
        feats.clear();
      }
    }

    @Override
    public void reset() throws IOException, UnsupportedOperationException {
      throw new UnsupportedOperationException("reset is not supported");
    }

    @Override
    public void close() throws IOException {
      reader.close();
    }
  }

  /**
   * Scores a feedforward model on a test split.
   *
   * @param model The model to wrap in a tagger and score.
   * @param testConllu The test split.
   * @return The UPOS word accuracy.
   * @throws IOException Thrown if the split cannot be read.
   */
  private static double evaluate(FeedforwardPOSModel model, Path testConllu)
      throws IOException {
    return evaluate(new FeedforwardPOSTagger(model), testConllu);
  }

  /**
   * Scores a tagger on a test split.
   *
   * @param tagger The tagger to score.
   * @param testConllu The test split.
   * @return The UPOS word accuracy.
   * @throws IOException Thrown if the split cannot be read.
   */
  private static double evaluate(POSTagger tagger, Path testConllu)
      throws IOException {
    final POSEvaluator evaluator = new POSEvaluator(tagger);
    try (ObjectStream<POSSample> test = samples(testConllu)) {
      evaluator.evaluate(test);
    }
    return evaluator.getWordAccuracy();
  }

  /**
   * Times the tagger over the whole test split in one pass, warm representation cache
   * included.
   *
   * @param tagger The tagger to time.
   * @param testConllu The test split.
   * @return The tagging rate in tokens per second.
   * @throws IOException Thrown if the split cannot be read.
   */
  private static double measureThroughput(POSTagger tagger, Path testConllu)
      throws IOException {
    final List<String[]> sentences = new ArrayList<>();
    int tokens = 0;
    try (ObjectStream<POSSample> test = samples(testConllu)) {
      POSSample sample;
      while ((sample = test.read()) != null) {
        sentences.add(sample.getSentence());
        tokens += sample.getSentence().length;
      }
    }
    final long start = System.nanoTime();
    for (final String[] sentence : sentences) {
      tagger.tag(sentence);
    }
    return tokens / ((System.nanoTime() - start) / 1e9d);
  }

  /**
   * Splits tagging accuracy into in-vocabulary and out-of-vocabulary tokens against
   * the training vocabulary ({@link #IN_VOCABULARY_CUTOFF}, normalized like the tagger
   * does), and further
   * splits OOV by whether the token resolves a stored pretrained vector, the error
   * profile that drives sweep decisions. Also writes the OOV gold-to-predicted
   * confusion counts to {@link #CONFUSION_FILE}.
   *
   * @param tagger The tagger to profile.
   * @param trainConllu The training split the vocabulary is taken from.
   * @param testConllu The test split the tagger is measured on.
   * @param vectors The word vector source, or {@code null} when none was used.
   * @param lexicon The additional stored-vector words, or {@code null} for none.
   * @return {IV accuracy, OOV accuracy, vector-covered OOV accuracy, uncovered OOV
   *         accuracy}; empty splits come back as 1.0.
   * @throws IOException Thrown if a split or the confusion file cannot be read or
   *         written.
   */
  private static double[] errorProfile(POSTagger tagger, Path trainConllu,
      Path testConllu, Function<CharSequence, float[]> vectors,
      Iterable<String> lexicon) throws IOException {
    final Map<String, Integer> counts = new HashMap<>();
    try (ObjectStream<POSSample> train = samples(trainConllu)) {
      POSSample sample;
      while ((sample = train.read()) != null) {
        for (final String token : sample.getSentence()) {
          counts.merge(BilstmPOSModel.normalize(token), 1, Integer::sum);
        }
      }
    }
    final Set<String> vocabulary = new HashSet<>();
    for (final Map.Entry<String, Integer> entry : counts.entrySet()) {
      if (entry.getValue() >= IN_VOCABULARY_CUTOFF) {
        vocabulary.add(entry.getKey());
      }
    }
    final Set<String> vectorWords = new HashSet<>();
    if (vectors != null) {
      for (final String word : counts.keySet()) {
        if (vectors.apply(word) != null) {
          vectorWords.add(word);
        }
      }
      if (lexicon != null) {
        for (final String word : lexicon) {
          final String normalized = BilstmPOSModel.normalize(word);
          if (vectors.apply(normalized) != null) {
            vectorWords.add(normalized);
          }
        }
      }
    }
    long ivCorrect = 0;
    long ivTotal = 0;
    long oovCorrect = 0;
    long oovTotal = 0;
    long vecCorrect = 0;
    long vecTotal = 0;
    long noVecCorrect = 0;
    long noVecTotal = 0;
    final Map<String, Integer> confusion = new HashMap<>();
    try (ObjectStream<POSSample> test = samples(testConllu)) {
      POSSample sample;
      while ((sample = test.read()) != null) {
        final String[] assigned = tagger.tag(sample.getSentence());
        final String[] gold = sample.getTags();
        for (int i = 0; i < gold.length; i++) {
          final boolean correct = assigned[i].equals(gold[i]);
          final String normalized = BilstmPOSModel.normalize(sample.getSentence()[i]);
          if (vocabulary.contains(normalized)) {
            ivTotal++;
            if (correct) {
              ivCorrect++;
            }
          }
          else {
            oovTotal++;
            if (correct) {
              oovCorrect++;
            }
            if (vectorWords.contains(normalized)) {
              vecTotal++;
              if (correct) {
                vecCorrect++;
              }
            }
            else {
              noVecTotal++;
              if (correct) {
                noVecCorrect++;
              }
            }
            if (!correct) {
              confusion.merge(gold[i] + ">" + assigned[i], 1, Integer::sum);
            }
          }
        }
      }
    }
    final List<Map.Entry<String, Integer>> sorted = new ArrayList<>(confusion.entrySet());
    sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
    final StringBuilder out = new StringBuilder();
    for (final Map.Entry<String, Integer> entry : sorted) {
      out.append(entry.getKey()).append(',').append(entry.getValue()).append('\n');
    }
    Files.writeString(CONFUSION_FILE, out.toString(), StandardCharsets.UTF_8);
    return new double[] {ivTotal > 0 ? (double) ivCorrect / ivTotal : 1.0d,
        oovTotal > 0 ? (double) oovCorrect / oovTotal : 1.0d,
        vecTotal > 0 ? (double) vecCorrect / vecTotal : 1.0d,
        noVecTotal > 0 ? (double) noVecCorrect / noVecTotal : 1.0d};
  }

  /**
   * Builds the word-vector source over a GloVe-format table of raw (unnormalized)
   * rows, mirroring {@code StaticEmbeddingModel.embed}: a whole-word hit is the raw
   * row, a miss falls back to greedy longest-match subword segmentation (the table's
   * {@code ##}-prefixed continuation pieces) with mean pooling, and the pooled result
   * is L2-normalized. Words that cannot be segmented at all yield {@code null}.
   *
   * @param gloveFile The vector text file.
   * @return The vector source. Never {@code null}.
   * @throws IOException Thrown if the file cannot be read.
   */
  private static Function<CharSequence, float[]> vectors(Path gloveFile) throws IOException {
    final WordVectorTable table;
    try (InputStream in = Files.newInputStream(gloveFile)) {
      table = Glove.parse(in);
    }
    logger.info("loaded {} vectors of dimension {} from {}", table.size(),
        table.dimension(), gloveFile);
    return word -> {
      final String form = word.toString();
      final float[] pooled;
      final WordVector hit = table.get(form);
      if (hit != null) {
        pooled = new float[hit.dimension()];
        for (int i = 0; i < pooled.length; i++) {
          pooled[i] = hit.getAsFloat(i);
        }
      }
      else {
        pooled = subwordPool(table, form);
        if (pooled == null) {
          return null;
        }
      }
      double sumOfSquares = 0.0d;
      for (final float value : pooled) {
        sumOfSquares += (double) value * value;
      }
      final double norm = Math.max(Math.sqrt(sumOfSquares), 1e-12d);
      for (int i = 0; i < pooled.length; i++) {
        pooled[i] /= (float) norm;
      }
      return pooled;
    };
  }

  /**
   * Pools a word the table has no row for out of its subword pieces, matching greedily
   * from the left and taking the longest piece the table knows at every position. All
   * but the first piece are looked up with the {@link #CONTINUATION_PREFIX}.
   *
   * @param table The vector table.
   * @param word The word to segment.
   * @return The mean of the piece vectors, or {@code null} when the word cannot be
   *         segmented.
   */
  private static float[] subwordPool(WordVectorTable table, String word) {
    final float[] sum = new float[table.dimension()];
    final StringBuilder piece = new StringBuilder();
    int count = 0;
    int start = 0;
    while (start < word.length()) {
      final int prefixLength = start == 0 ? 0 : CONTINUATION_PREFIX.length();
      final int longest = Math.min(word.length(), start + MAX_SUBWORD_LENGTH);
      piece.setLength(0);
      if (start > 0) {
        piece.append(CONTINUATION_PREFIX);
      }
      piece.append(word, start, longest);
      WordVector vector = null;
      for (int end = longest; end > start; end--) {
        vector = table.get(piece.toString());
        if (vector != null) {
          break;
        }
        piece.setLength(piece.length() - 1);
      }
      if (vector == null) {
        return null;
      }
      for (int i = 0; i < sum.length; i++) {
        sum[i] += vector.getAsFloat(i);
      }
      count++;
      start += piece.length() - prefixLength;
    }
    for (int i = 0; i < sum.length; i++) {
      sum[i] /= count;
    }
    return sum;
  }
}
