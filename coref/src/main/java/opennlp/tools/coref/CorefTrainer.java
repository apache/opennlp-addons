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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.ml.EventTrainer;
import opennlp.tools.ml.TrainerFactory;
import opennlp.tools.ml.maxent.GISModel;
import opennlp.tools.ml.model.Context;
import opennlp.tools.ml.model.Event;
import opennlp.tools.ml.model.MaxentModel;
import opennlp.tools.util.AbstractEventStream;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.Span;
import opennlp.tools.util.TrainingParameters;

/**
 * Trains a {@link CorefModel} from documents that carry the input layers of
 * {@link CorefAnnotator} and a {@link CorefAnnotator#GOLD_CHAINS} layer.
 *
 * <p>Training uses the annotator's mention detection, candidate order, and speaker and
 * string sieves. Gold chains match detected mentions by exact span. An anaphor is
 * omitted when its gold antecedent was not detected.</p>
 *
 * <p>{@link #trainRanking} learns a mention ranker instead, after
 * <a href="https://aclanthology.org/D13-1203/">Durrett and Klein (EMNLP 2013)</a>: for
 * each anaphor the options are its candidates and a new-chain option. The model
 * maximizes the combined probability of all gold options under a softmax. Its weights
 * are stored in a {@link GISModel}.</p>
 *
 * <p>A corpus that annotates singleton entities annotates every mention of its scheme,
 * so a detected mention absent from that gold layer is excluded from training. In data
 * without gold singletons, an unannotated mention trains as a new chain.</p>
 *
 * @since 3.0.0
 */
public final class CorefTrainer {

  /** The epochs of {@link #trainRanking(String, ObjectStream, CorefAnnotator)}. */
  public static final int DEFAULT_EPOCHS = 10;

  /** The AdaGrad step size of {@link #trainRanking(String, ObjectStream, CorefAnnotator)}. */
  public static final double DEFAULT_LEARNING_RATE = 0.05;

  /** The L2 weight of {@link #trainRanking(String, ObjectStream, CorefAnnotator)}. */
  public static final double DEFAULT_L2 = 0.001;

  /** The seed of the shuffle that orders anaphors within a ranking epoch. */
  private static final long SHUFFLE_SEED = 17L;

  /** The AdaGrad smoothing term. */
  private static final double ADAGRAD_EPSILON = 1e-8;

  /**
   * One anaphor's options for ranking: the binary feature ids per option, the last
   * being new chain, and with contextual vectors the anaphor's span vector and each
   * candidate's, from which the real-valued features derive as
   * {@link CorefContextGenerator.Features} describes.
   *
   * @param options The sparse feature ids of each option.
   * @param anaphor The anaphor vector, or {@code null}.
   * @param antecedents The antecedent vector of each option, with {@code null} for the
   *                    new-chain option or when no vectors are used.
   * @param gold Which options are correct.
   */
  private record RankingInstance(int[][] options, float[] anaphor, float[][] antecedents,
      boolean[] gold) {
  }

  /** The weights of the ranker: one per binary feature and three dense blocks. */
  private static final class Weights {
    final double[] sparse;
    final double[] product;
    final double[] difference;
    final double[] newChain;

    /**
     * Allocates zero-initialized sparse and dense weights.
     *
     * @param features The number of sparse features.
     * @param dimension The dense vector dimension.
     */
    Weights(int features, int dimension) {
      sparse = new double[features];
      product = new double[dimension];
      difference = new double[dimension];
      newChain = new double[dimension];
    }
  }

  /** Reads training pairs from documents. */
  private static final class PairEventStream extends AbstractEventStream<Document> {

    private final CorefAnnotator annotator;

    /**
     * Creates an event stream over annotated documents.
     *
     * @param documents The document stream.
     * @param annotator The mention detector and resolver configuration.
     */
    PairEventStream(ObjectStream<Document> documents, CorefAnnotator annotator) {
      super(documents);
      this.annotator = annotator;
    }

    /** {@inheritDoc} */
    @Override
    protected Iterator<Event> createEvents(Document document) {
      return pairs(document, annotator).iterator();
    }
  }

  /** Prevents construction of this training utility. */
  private CorefTrainer() {
  }

  /**
   * Trains a model with the conventional entity types of {@link CorefAnnotator}.
   *
   * @param languageCode The ISO language code of the documents. Must not be
   *                     {@code null} or blank.
   * @param documents The training documents, each carrying the annotator's required
   *                  layers and {@link CorefAnnotator#GOLD_CHAINS}. Must not be
   *                  {@code null}.
   * @param parameters The training parameters. Must not be {@code null}.
   * @return The model. Never {@code null}.
   * @throws IOException Thrown if reading the documents fails.
   * @throws IllegalArgumentException Thrown if an argument is invalid or a document
   *         lacks a required layer.
   * @throws IllegalStateException Thrown if a vector provider violates its return
   *         contract.
   */
  public static CorefModel train(String languageCode, ObjectStream<Document> documents,
      TrainingParameters parameters) throws IOException {
    return train(languageCode, documents, parameters, new CorefAnnotator());
  }

  /**
   * Trains a model for the entity types and pronoun classes of an annotator.
   *
   * @param languageCode The ISO language code of the documents. Must not be
   *                     {@code null} or blank.
   * @param documents The training documents. Must not be {@code null}.
   * @param parameters The training parameters. Must not be {@code null}.
   * @param annotator The rule-based annotator whose mention detection and speaker
   *                  sieve the model is trained against. Must not be {@code null}.
   * @return The model. Never {@code null}.
   * @throws IOException Thrown if reading the documents fails.
   * @throws IllegalArgumentException Thrown if an argument is invalid or a document
   *         lacks a required layer.
   * @throws IllegalStateException Thrown if a vector provider violates its return
   *         contract.
   */
  public static CorefModel train(String languageCode, ObjectStream<Document> documents,
      TrainingParameters parameters, CorefAnnotator annotator) throws IOException {
    CorefModel.requireLanguageCode(languageCode);
    if (documents == null) {
      throw new IllegalArgumentException("documents must not be null");
    }
    if (parameters == null) {
      throw new IllegalArgumentException("parameters must not be null");
    }
    if (annotator == null) {
      throw new IllegalArgumentException("annotator must not be null");
    }
    final Map<String, String> manifestInfoEntries = new HashMap<>();
    final EventTrainer<TrainingParameters> trainer =
        TrainerFactory.getEventTrainer(parameters, manifestInfoEntries);
    final MaxentModel pairModel = trainer.train(new PairEventStream(documents, annotator));
    return new CorefModel(languageCode, pairModel, false,
        annotator.tokenVectorDimension(), manifestInfoEntries);
  }

  /**
   * Trains a ranking model with the settings chosen on the OntoGUM development split:
   * {@link #DEFAULT_EPOCHS}, {@link #DEFAULT_LEARNING_RATE}, and {@link #DEFAULT_L2}.
   *
   * @param languageCode The ISO language code of the documents. Must not be
   *                     {@code null} or blank.
   * @param documents The training documents. Must not be {@code null} and must produce
   *                  at least one ranking instance.
   * @param annotator The rule-based annotator whose mention detection, speaker and
   *                  string sieves, and word vectors the model is trained against.
   *                  Must not be {@code null}.
   * @return The model. Never {@code null}.
   * @throws IOException Thrown if reading the documents fails.
   * @throws IllegalArgumentException Thrown if an argument is invalid, a document lacks
   *         a required layer, or the data produces no ranking instances.
   * @throws IllegalStateException Thrown if a vector provider violates its return
   *         contract.
   */
  public static CorefModel trainRanking(String languageCode, ObjectStream<Document> documents,
      CorefAnnotator annotator) throws IOException {
    return trainRanking(languageCode, documents, DEFAULT_EPOCHS, DEFAULT_LEARNING_RATE,
        DEFAULT_L2, annotator);
  }

  /**
   * Trains a ranking model.
   *
   * @param languageCode The ISO language code of the documents. Must not be
   *                     {@code null} or blank.
   * @param documents The training documents. Must not be {@code null} and must produce
   *                  at least one ranking instance.
   * @param epochs How many passes over the anaphors to make. Must be positive.
   * @param learningRate The AdaGrad step size. Must be positive and finite.
   * @param l2 The L2 regularization weight. Must be finite and not negative.
   * @param annotator The rule-based annotator whose mention detection, speaker and
   *                  string sieves, and word vectors the model is trained against.
   *                  Must not be {@code null}.
   * @return The model. Never {@code null}.
   * @throws IOException Thrown if reading the documents fails.
   * @throws IllegalArgumentException Thrown if an argument is invalid or a document
   *         lacks a required layer, or the data produces no ranking instances.
   * @throws IllegalStateException Thrown if a vector provider violates its return
   *         contract.
   */
  public static CorefModel trainRanking(String languageCode, ObjectStream<Document> documents,
      int epochs, double learningRate, double l2, CorefAnnotator annotator)
      throws IOException {
    CorefModel.requireLanguageCode(languageCode);
    if (documents == null) {
      throw new IllegalArgumentException("documents must not be null");
    }
    if (annotator == null) {
      throw new IllegalArgumentException("annotator must not be null");
    }
    if (epochs <= 0) {
      throw new IllegalArgumentException("epochs must be positive: " + epochs);
    }
    if (!Double.isFinite(learningRate) || learningRate <= 0.0) {
      throw new IllegalArgumentException(
          "learningRate must be positive and finite: " + learningRate);
    }
    if (!Double.isFinite(l2) || l2 < 0.0) {
      throw new IllegalArgumentException("l2 must be finite and not negative: " + l2);
    }
    final Map<String, Integer> featureIds = new HashMap<>();
    final List<RankingInstance> instances = new ArrayList<>();
    final int dimension = annotator.tokenVectorDimension();
    Document document;
    while ((document = documents.read()) != null) {
      for (final RankingInstance instance : rankingInstances(document, annotator, featureIds)) {
        instances.add(instance);
        if (instance.anaphor() != null && instance.anaphor().length != dimension) {
          throw new IllegalStateException("token encoder returned dimension "
              + instance.anaphor().length + ", expected " + dimension);
        }
      }
    }
    if (instances.isEmpty()) {
      throw new IllegalArgumentException("training data produced no ranking instances");
    }
    final Weights weights = new Weights(featureIds.size(), dimension);
    final Weights squares = new Weights(featureIds.size(), dimension);
    final double[] gradient = new double[featureIds.size()];
    final Random random = new Random(SHUFFLE_SEED);
    final List<RankingInstance> order = new ArrayList<>(instances);
    for (int epoch = 0; epoch < epochs; epoch++) {
      Collections.shuffle(order, random);
      for (final RankingInstance instance : order) {
        step(instance, weights, squares, gradient, learningRate, l2);
      }
    }
    final String[] predicates = new String[featureIds.size() + 3 * dimension];
    final double[] all = new double[predicates.length];
    for (final Map.Entry<String, Integer> feature : featureIds.entrySet()) {
      predicates[feature.getValue()] = feature.getKey();
      all[feature.getValue()] = weights.sparse[feature.getValue()];
    }
    for (int d = 0; d < dimension; d++) {
      final int p = featureIds.size() + 3 * d;
      predicates[p] = CorefContextGenerator.PRODUCT + d;
      all[p] = weights.product[d];
      predicates[p + 1] = CorefContextGenerator.DIFFERENCE + d;
      all[p + 1] = weights.difference[d];
      predicates[p + 2] = CorefContextGenerator.NEW_CHAIN + d;
      all[p + 2] = weights.newChain[d];
    }
    final Context[] parameters = new Context[all.length];
    for (int f = 0; f < all.length; f++) {
      parameters[f] = new Context(new int[] {0}, new double[] {all[f]});
    }
    final GISModel model = new GISModel(parameters, predicates,
        new String[] {SieveResolver.LINK, SieveResolver.APART});
    return new CorefModel(languageCode, model, true, dimension, new HashMap<>());
  }

  /**
   * Takes one AdaGrad step up the log probability of the gold options: the gradient is
   * the expected feature count under the gold options minus that under all options.
   *
   * @param instance The ranking instance.
   * @param weights The current weights.
   * @param squares The accumulated squared gradients.
   * @param gradient Reusable sparse-gradient storage.
   * @param learningRate The AdaGrad step size.
   * @param l2 The L2 regularization weight.
   */
  private static void step(RankingInstance instance, Weights weights, Weights squares,
      double[] gradient, double learningRate, double l2) {
    final int[][] options = instance.options();
    final float[] u = instance.anaphor();
    final double[] scores = new double[options.length];
    double max = Double.NEGATIVE_INFINITY;
    double goldMax = Double.NEGATIVE_INFINITY;
    for (int o = 0; o < options.length; o++) {
      double score = 0.0;
      for (final int f : options[o]) {
        score += weights.sparse[f];
      }
      if (u != null) {
        score += dense(weights, u, instance.antecedents()[o]);
      }
      scores[o] = score;
      max = Math.max(max, score);
      if (instance.gold()[o]) {
        goldMax = Math.max(goldMax, score);
      }
    }
    double all = 0.0;
    double gold = 0.0;
    for (int o = 0; o < options.length; o++) {
      all += Math.exp(scores[o] - max);
      if (instance.gold()[o]) {
        gold += Math.exp(scores[o] - goldMax);
      }
    }
    final int[] touched = new int[gradient.length];
    final boolean[] queued = new boolean[gradient.length];
    int touchedCount = 0;
    final int dimension = u == null ? 0 : u.length;
    final double[] productGradient = new double[dimension];
    final double[] differenceGradient = new double[dimension];
    final double[] newChainGradient = new double[dimension];
    for (int o = 0; o < options.length; o++) {
      final double expected = Math.exp(scores[o] - max) / all;
      final double goldExpected = instance.gold()[o] ? Math.exp(scores[o] - goldMax) / gold : 0.0;
      final double delta = goldExpected - expected;
      if (delta == 0.0) {
        continue;
      }
      for (final int f : options[o]) {
        if (!queued[f]) {
          queued[f] = true;
          touched[touchedCount++] = f;
        }
        gradient[f] += delta;
      }
      if (u != null) {
        final float[] v = instance.antecedents()[o];
        for (int d = 0; d < dimension; d++) {
          if (v != null) {
            productGradient[d] += delta * u[d] * v[d];
            differenceGradient[d] += delta * Math.abs(u[d] - v[d]);
          } else {
            newChainGradient[d] += delta * u[d];
          }
        }
      }
    }
    for (int i = 0; i < touchedCount; i++) {
      final int f = touched[i];
      update(weights.sparse, squares.sparse, f, gradient[f], learningRate, l2);
      gradient[f] = 0.0;
    }
    for (int d = 0; d < dimension; d++) {
      update(weights.product, squares.product, d, productGradient[d], learningRate, l2);
      update(weights.difference, squares.difference, d, differenceGradient[d], learningRate, l2);
      update(weights.newChain, squares.newChain, d, newChainGradient[d], learningRate, l2);
    }
  }

  /**
   * Computes the dense portion of an option score.
   *
   * @param weights The current weights.
   * @param u The anaphor vector.
   * @param v The antecedent vector, or {@code null} for a new chain.
   * @return The dense portion of the option score.
   */
  private static double dense(Weights weights, float[] u, float[] v) {
    double score = 0.0;
    for (int d = 0; d < u.length; d++) {
      if (v != null) {
        score += weights.product[d] * u[d] * v[d] + weights.difference[d] * Math.abs(u[d] - v[d]);
      } else {
        score += weights.newChain[d] * u[d];
      }
    }
    return score;
  }

  /**
   * Applies one AdaGrad update.
   *
   * @param weights The weight array.
   * @param squares The accumulated squared gradients.
   * @param f The weight index.
   * @param gradient The likelihood gradient.
   * @param learningRate The step size.
   * @param l2 The regularization weight.
   */
  private static void update(double[] weights, double[] squares, int f, double gradient,
      double learningRate, double l2) {
    final double g = gradient - l2 * weights[f];
    squares[f] += g * g;
    weights[f] += learningRate * g / (Math.sqrt(squares[f]) + ADAGRAD_EPSILON);
  }

  /**
   * Builds the ranking instances of one document: for each anaphor the feature ids of
   * every admissible candidate and of the new-chain option, with the gold options
   * marked, applying gold links as they are found.
   *
   * @param document The training document.
   * @param annotator The mention detector and resolver configuration.
   * @param featureIds The feature dictionary to extend.
   * @return The document's ranking instances.
   * @throws IllegalArgumentException Thrown if the gold chains layer is absent.
   */
  private static List<RankingInstance> rankingInstances(Document document,
      CorefAnnotator annotator, Map<String, Integer> featureIds) {
    if (document == null || !document.layers().contains(CorefAnnotator.GOLD_CHAINS)) {
      throw new IllegalArgumentException(
          "document lacks the required layer " + CorefAnnotator.GOLD_CHAINS);
    }
    final List<RankingInstance> instances = new ArrayList<>();
    final SieveResolver resolver = annotator.resolver(document);
    if (resolver == null) {
      return instances;
    }
    resolver.resolvePrecise();
    final List<Mention> mentions = resolver.mentions();
    final Clusters clusters = resolver.clusters();
    final int[] gold = goldChains(document, mentions);
    final int[] chainStarts = chainStarts(document);
    final boolean completeGold = annotatesSingletons(document);
    final CorefContextGenerator features = new CorefContextGenerator(resolver);
    for (int j = 0; j < mentions.size(); j++) {
      if (clusters.find(j) != j || !resolver.rankable(j) || completeGold && gold[j] < 0) {
        continue;
      }
      final List<Integer> candidates = resolver.rankerCandidates(j);
      final boolean[] goldOptions = new boolean[candidates.size() + 1];
      int nearest = -1;
      boolean anyGold = false;
      for (int o = 0; o < candidates.size(); o++) {
        final int i = candidates.get(o);
        goldOptions[o] = gold[j] >= 0 && gold[i] == gold[j];
        anyGold |= goldOptions[o];
        if (goldOptions[o] && i > nearest) {
          nearest = i;
        }
      }
      if (!anyGold && unlearnable(mentions.get(j), gold[j], chainStarts)) {
        continue;
      }
      final int[][] options = new int[candidates.size() + 1][];
      final float[][] antecedents = new float[options.length][];
      for (int o = 0; o < candidates.size(); o++) {
        final CorefContextGenerator.Features pair = features.features(candidates.get(o), j);
        // Ranking stores the vectors, but inference expands them into float features.
        features.values(pair);
        options[o] = ids(pair.names(), featureIds);
        antecedents[o] = pair.antecedent();
      }
      final CorefContextGenerator.Features newChain = features.newChainFeatures(j);
      options[candidates.size()] = ids(newChain.names(), featureIds);
      goldOptions[candidates.size()] = !anyGold;
      instances.add(new RankingInstance(options, newChain.anaphor(), antecedents, goldOptions));
      if (nearest >= 0) {
        clusters.union(nearest, j);
      }
    }
    return instances;
  }

  /**
   * Maps feature strings to ids, assigning ids to unseen features.
   *
   * @param features The feature strings.
   * @param featureIds The feature dictionary to extend.
   * @return The corresponding feature ids.
   */
  private static int[] ids(String[] features, Map<String, Integer> featureIds) {
    final int[] ids = new int[features.length];
    for (int f = 0; f < features.length; f++) {
      ids[f] = featureIds.computeIfAbsent(features[f], key -> featureIds.size());
    }
    return ids;
  }

  /**
   * Builds the training pairs of one document.
   *
   * @param document The document with its gold chains.
   * @param annotator The annotator supplying mention detection.
   * @return The pair events in text order of their anaphors. Never {@code null}.
   * @throws IllegalArgumentException Thrown if the gold chains layer is absent.
   */
  static List<Event> pairs(Document document, CorefAnnotator annotator) {
    if (document == null || !document.layers().contains(CorefAnnotator.GOLD_CHAINS)) {
      throw new IllegalArgumentException(
          "document lacks the required layer " + CorefAnnotator.GOLD_CHAINS);
    }
    final SieveResolver resolver = annotator.resolver(document);
    final List<Event> events = new ArrayList<>();
    if (resolver == null) {
      return events;
    }
    resolver.resolvePrecise();
    final List<Mention> mentions = resolver.mentions();
    final Clusters clusters = resolver.clusters();
    final int[] gold = goldChains(document, mentions);
    final int[] chainStarts = chainStarts(document);
    final boolean completeGold = annotatesSingletons(document);
    final CorefContextGenerator features = new CorefContextGenerator(resolver);
    for (int j = 0; j < mentions.size(); j++) {
      if (clusters.find(j) != j || !resolver.rankable(j) || completeGold && gold[j] < 0) {
        continue;
      }
      final List<Integer> candidates = resolver.rankerCandidates(j);
      int antecedent = -1;
      if (gold[j] >= 0) {
        for (final int i : candidates) {
          if (gold[i] == gold[j] && i > antecedent) {
            antecedent = i;
          }
        }
      }
      if (antecedent < 0 && unlearnable(mentions.get(j), gold[j], chainStarts)) {
        continue;
      }
      for (final int i : candidates) {
        if (i < antecedent) {
          continue;
        }
        final CorefContextGenerator.Features pair = features.features(i, j);
        events.add(new Event(i == antecedent ? SieveResolver.LINK : SieveResolver.APART,
            features.names(pair), features.values(pair)));
      }
      if (antecedent >= 0) {
        clusters.union(antecedent, j);
      }
    }
    return events;
  }

  /**
   * Checks whether the gold layer includes singleton chains.
   *
   * @param document The training document.
   * @return Whether any gold chain contains one mention.
   */
  private static boolean annotatesSingletons(Document document) {
    final Map<Integer, Integer> sizes = new HashMap<>();
    for (final Annotation<CorefMention> mention : document.get(CorefAnnotator.GOLD_CHAINS)) {
      sizes.merge(mention.value().chain(), 1, Integer::sum);
    }
    return sizes.containsValue(1);
  }

  /**
   * Checks whether an anaphor's gold antecedents are all missing from the detected
   * mentions: its gold chain starts before it, yet no candidate shares the chain. Such
   * an anaphor is coreferent in the annotation, so treating it as a first mention or an
   * unlinked pair would be incorrect; it is omitted from training.
   *
   * @param anaphor The detected anaphor.
   * @param goldChain Its gold chain id, or {@code -1}.
   * @param chainStarts The first offset of every gold chain.
   * @return Whether the gold antecedent is absent from detected candidates.
   */
  private static boolean unlearnable(Mention anaphor, int goldChain, int[] chainStarts) {
    return goldChain >= 0 && chainStarts[goldChain] < anaphor.span().getStart();
  }

  /**
   * Finds the first mention offset of each gold chain.
   *
   * @param document The training document.
   * @return The first mention offset of each gold chain.
   */
  private static int[] chainStarts(Document document) {
    int chains = 0;
    for (final Annotation<CorefMention> gold : document.get(CorefAnnotator.GOLD_CHAINS)) {
      chains = Math.max(chains, gold.value().chain() + 1);
    }
    final int[] starts = new int[chains];
    Arrays.fill(starts, Integer.MAX_VALUE);
    for (final Annotation<CorefMention> gold : document.get(CorefAnnotator.GOLD_CHAINS)) {
      starts[gold.value().chain()] = Math.min(starts[gold.value().chain()], gold.span().getStart());
    }
    return starts;
  }

  /**
   * Maps detected mentions to gold chains by exact span.
   *
   * @param document The training document.
   * @param mentions The detected mentions.
   * @return The gold chain id of each mention, or {@code -1}.
   * @throws IllegalArgumentException Thrown if gold mention spans repeat or chain ids
   *         are not consecutive in first-mention order.
   */
  private static int[] goldChains(Document document, List<Mention> mentions) {
    final Map<Span, Integer> chainOfSpan = new HashMap<>();
    final List<Annotation<CorefMention>> annotations =
        new ArrayList<>(document.get(CorefAnnotator.GOLD_CHAINS));
    annotations.sort((first, second) -> first.span().compareTo(second.span()));
    final Set<Integer> seenChains = new HashSet<>();
    for (final Annotation<CorefMention> gold : annotations) {
      final Span span = new Span(gold.span().getStart(), gold.span().getEnd());
      if (chainOfSpan.putIfAbsent(span, gold.value().chain()) != null) {
        throw new IllegalArgumentException("duplicate gold mention span: " + span);
      }
      if (seenChains.add(gold.value().chain())
          && gold.value().chain() != seenChains.size() - 1) {
        throw new IllegalArgumentException("gold chain identifiers must start at zero in "
            + "first-mention order: expected " + (seenChains.size() - 1) + ", found "
            + gold.value().chain() + " at " + span);
      }
    }
    final int[] chains = new int[mentions.size()];
    for (int m = 0; m < chains.length; m++) {
      final Span span = mentions.get(m).span();
      chains[m] = chainOfSpan.getOrDefault(new Span(span.getStart(), span.getEnd()), -1);
    }
    return chains;
  }
}
