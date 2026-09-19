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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the features of one anaphor and candidate antecedent pair for the ranking
 * model: the sieve tests as predicates, the surface shape of each mention, the distance
 * between them, their attribute pairs, and conjunctions of mention kinds with the rest.
 * The feature set follows
 * <a href="https://aclanthology.org/D13-1203/">Durrett and Klein (EMNLP 2013), "Easy
 * Victories and Uphill Battles in Coreference Resolution"</a>.
 *
 * <p>Features are opaque strings of the form {@code name=value}; the model learns a
 * weight per string. Head words and pronoun forms are lexical features and so make the
 * model corpus-specific; every other feature is closed-class.</p>
 *
 * <p>With contextual token vectors, each mention also has a span vector, the mean of its
 * token vectors, and a pair carries their bucketed cosine as strings plus real-valued
 * features: the elementwise product and absolute difference of the two span vectors,
 * so the model learns a bilinear comparison after
 * <a href="https://aclanthology.org/D17-1018/">Lee et al. (EMNLP 2017)</a>; the
 * new-chain option carries the anaphor's span vector, so the model learns which
 * mentions in context start a chain.</p>
 */
final class CorefContextGenerator {

  /** The value of a similarity feature when a head has no vector. */
  private static final String UNKNOWN_SIMILARITY = "unknown";

  /** Name prefix of the real-valued features holding the product of two span vectors. */
  static final String PRODUCT = "vp";

  /** Name prefix of the real-valued features holding the absolute difference of two span vectors. */
  static final String DIFFERENCE = "vd";

  /** Name prefix of the real-valued features holding the anaphor's span vector for the new-chain option. */
  static final String NEW_CHAIN = "vn";

  private final SieveResolver resolver;
  private final List<Mention> mentions;
  private final Clusters clusters;
  private final WordVectors vectors;
  private final Map<String, float[]> vectorCache = new HashMap<>();
  private final float[][] tokenVectors;
  private final float[][] spanVectors;
  private final int vectorDimension;
  private String[] productNames;
  private String[] differenceNames;
  private String[] newChainNames;

  /**
   * The features of one option: the binary features by name and, with contextual
   * vectors, the anaphor's span vector and, for a pair, the candidate's. The
   * real-valued features derive from the vectors: the elementwise product and absolute
   * difference of the two for a pair, the anaphor's vector alone for the new-chain
   * option. Options that carry no vectors have {@code null} vectors.
   *
   * @param names The binary features.
   * @param anaphor The anaphor's span vector, or {@code null}.
   * @param antecedent The candidate's span vector, or {@code null} for the new-chain
   *                   option and without vectors.
   */
  record Features(String[] names, float[] anaphor, float[] antecedent) {

    /** {@return the dimension of the real-valued features, zero without vectors} */
    int dimension() {
      return anaphor == null ? 0 : anaphor.length;
    }
  }

  /**
   * Initializes the generator over a resolver's mentions and clusters.
   *
   * @param resolver The resolver whose sieve tests supply the predicates.
   */
  CorefContextGenerator(SieveResolver resolver) {
    this.resolver = resolver;
    this.mentions = resolver.mentions();
    this.clusters = resolver.clusters();
    this.vectors = resolver.vectors();
    this.vectorDimension = vectors == null ? 0 : vectors.dimension();
    this.tokenVectors = resolver.tokenVectors();
    this.spanVectors = tokenVectors == null ? null : new float[mentions.size()][];
  }

  /**
   * Generates the features of the option that starts a new chain: the anaphor's own
   * shape and what precedes it, so the ranker learns which mentions are first mentions.
   *
   * @param j The anaphor's index.
   * @return The features. Never {@code null}.
   */
  Features newChainFeatures(int j) {
    final Mention anaphor = mentions.get(j);
    final List<String> features = new ArrayList<>(16);
    final String kind = "new|" + kind(anaphor);
    features.add(kind);
    features.add(kind + "|head=" + anaphor.head());
    features.add(kind + "|first=" + first(anaphor));
    features.add(kind + "|shape=" + shape(anaphor));
    features.add(kind + "|length=" + bucket(anaphor.words().size()));
    features.add(kind + "|type=" + anaphor.type());
    features.add(kind + "|sentence=" + bucket(anaphor.sentence()));
    features.add(kind + "|position=" + bucket(positionInSentence(j)));
    features.add(kind + "|earlierHead=" + earlierHead(j));
    features.add(kind + "|earlierExact=" + earlierExact(j));
    if (anaphor.pronoun()) {
      features.add(kind + "|form=" + anaphor.head());
    }
    return new Features(features.toArray(new String[0]),
        spanVectors == null ? null : spanVector(j), null);
  }

  /**
   * Generates the features of a pair.
   *
   * @param i The candidate antecedent's index.
   * @param j The anaphor's index.
   * @return The features. Never {@code null}.
   */
  Features features(int i, int j) {
    final Mention antecedent = mentions.get(i);
    final Mention anaphor = mentions.get(j);
    final List<String> features = new ArrayList<>(80);
    final String kinds = kind(anaphor) + '>' + kind(antecedent);
    features.add("kinds=" + kinds);
    features.add("antClusterSize=" + bucket(clusters.size(i)));
    features.add(kinds + "|antClusterSize=" + bucket(clusters.size(i)));
    features.add("clusterExact=" + clusters.normalizedForms(i).contains(anaphor.normalized()));
    features.add("clusterHead=" + clusters.heads(i).contains(anaphor.head()));
    features.add("antPosition=" + bucket(positionInSentence(i)));
    features.add("sameSpeaker=" + (resolver.speakerOf(i).equals(resolver.speakerOf(j))));
    if (antecedent.pronoun()) {
      features.add("antForm=" + antecedent.head());
      features.add("antForm=" + antecedent.head() + "|anaHead=" + anaphor.head());
    }
    if (vectors != null && !anaphor.pronoun()) {
      final String similarity = "sim=" + similarity(anaphor.head(), antecedent.head());
      features.add(similarity);
      features.add(kinds + '|' + similarity);
      String best = UNKNOWN_SIMILARITY;
      double bestCosine = -2.0;
      for (final String head : clusters.heads(i)) {
        final double cosine = cosine(anaphor.head(), head);
        if (cosine > bestCosine) {
          bestCosine = cosine;
          best = similarityBucket(cosine);
        }
      }
      features.add("clusterSim=" + best);
    }

    sieve(features, "exact", resolver.exactMatch(i, j), kinds);
    sieve(features, "relaxed", resolver.relaxedStringMatch(i, j), kinds);
    sieve(features, "acronym", resolver.acronym(i, j), kinds);
    sieve(features, "personName", resolver.personName(i, j), kinds);
    sieve(features, "strictHead", resolver.strictHeadMatch(i, j, true, true), kinds);
    sieve(features, "headNoModifiers", resolver.strictHeadMatch(i, j, true, false), kinds);
    sieve(features, "headNoInclusion", resolver.strictHeadMatch(i, j, false, true), kinds);
    sieve(features, "properHead", resolver.properHeadMatch(i, j), kinds);
    sieve(features, "relaxedHead", resolver.relaxedHeadMatch(i, j), kinds);
    sieve(features, "agree", clusters.attributesAgree(i, j), kinds);
    sieve(features, "numberAgree", clusters.numbersAgree(i, j), kinds);
    if (anaphor.pronoun()) {
      sieve(features, "pronounMatch", resolver.pronounMatch(i, j), kinds);
    }

    final boolean sameHead = anaphor.head() != null && anaphor.head().equals(antecedent.head());
    features.add("sameHead=" + sameHead);
    features.add("anaHead=" + anaphor.head());
    features.add("antHead=" + antecedent.head());
    features.add("heads=" + anaphor.head() + '|' + antecedent.head());
    features.add("anaFirst=" + first(anaphor));
    features.add("antFirst=" + first(antecedent));
    features.add("anaShape=" + shape(anaphor));
    features.add("antShape=" + shape(antecedent));
    features.add("anaLength=" + bucket(anaphor.words().size()));
    features.add("antLength=" + bucket(antecedent.words().size()));
    features.add("anaType=" + anaphor.type());
    features.add("antType=" + antecedent.type());
    features.add("types=" + anaphor.type() + '|' + antecedent.type());
    features.add("gender=" + anaphor.gender() + '|' + antecedent.gender());
    features.add("number=" + anaphor.number() + '|' + antecedent.number());
    features.add("animacy=" + anaphor.animacy() + '|' + antecedent.animacy());
    features.add("antPerson=" + antecedent.person());
    features.add("antFirstInCluster=" + (clusters.find(i) == i));

    final int sentences = anaphor.sentence() - antecedent.sentence();
    final String distance = "dist=" + bucket(sentences) + '|' + bucket(j - i);
    features.add(distance);
    features.add(kinds + '|' + distance);
    features.add(kinds + "|sameHead=" + sameHead);
    if (anaphor.pronoun()) {
      final String form = "form=" + anaphor.head();
      features.add(form);
      features.add(form + '|' + kind(antecedent));
      features.add(form + "|antGender=" + antecedent.gender());
      features.add(form + "|antNumber=" + antecedent.number());
      features.add(form + "|antAnimacy=" + antecedent.animacy());
      features.add(form + "|antType=" + antecedent.type());
      features.add(form + '|' + distance);
      features.add(form + "|antHead=" + antecedent.head());
    } else {
      features.add("anaFirst=" + first(anaphor) + '|' + kind(antecedent));
      features.add("anaIndefinite=" + anaphor.indefinite());
      features.add("antIndefinite=" + antecedent.indefinite());
      features.add("bothProper=" + (anaphor.proper() && antecedent.proper()));
    }
    if (spanVectors == null) {
      return new Features(features.toArray(new String[0]), null, null);
    }
    final float[] u = spanVector(j);
    final float[] v = spanVector(i);
    final String context = "ctx=" + contextBucket(cosine(u, v));
    features.add(context);
    features.add(kinds + '|' + context);
    features.add("ctxCluster=" + contextBucket(cosine(u, clusterVector(i))));
    return new Features(features.toArray(new String[0]), u, v);
  }

  /**
   * Expands an option's features into the names a {@link opennlp.tools.ml.model.MaxentModel}
   * evaluates: the binary features followed by the real-valued ones.
   *
   * @param option The option.
   * @return The names. Never {@code null}.
   */
  String[] names(Features option) {
    final int dimension = option.dimension();
    if (dimension == 0) {
      return option.names();
    }
    final boolean pair = option.antecedent() != null;
    final String[] names = new String[option.names().length + (pair ? 2 : 1) * dimension];
    System.arraycopy(option.names(), 0, names, 0, option.names().length);
    int n = option.names().length;
    if (pair) {
      productNames = names(productNames, PRODUCT, dimension);
      differenceNames = names(differenceNames, DIFFERENCE, dimension);
      for (int d = 0; d < dimension; d++) {
        names[n++] = productNames[d];
        names[n++] = differenceNames[d];
      }
    } else {
      newChainNames = names(newChainNames, NEW_CHAIN, dimension);
      for (int d = 0; d < dimension; d++) {
        names[n++] = newChainNames[d];
      }
    }
    return names;
  }

  /**
   * Expands an option's features into the values matching {@link #names(Features)}:
   * one for every binary feature, then the real-valued ones.
   *
   * @param option The option.
   * @return The values, or {@code null} when every feature counts one.
   */
  float[] values(Features option) {
    final int dimension = option.dimension();
    if (dimension == 0) {
      return null;
    }
    final float[] u = option.anaphor();
    final float[] v = option.antecedent();
    final float[] values = new float[option.names().length + (v != null ? 2 : 1) * dimension];
    int n = 0;
    while (n < option.names().length) {
      values[n++] = 1f;
    }
    for (int d = 0; d < dimension; d++) {
      if (v != null) {
        values[n++] = denseValue((double) u[d] * v[d], PRODUCT, d);
        values[n++] = denseValue(Math.abs((double) u[d] - v[d]), DIFFERENCE, d);
      } else {
        values[n++] = u[d];
      }
    }
    return values;
  }

  /**
   * Converts a derived dense feature to the model's float representation.
   *
   * @param value The computed value.
   * @param feature The feature name prefix.
   * @param dimension The vector dimension.
   * @return The finite float value.
   * @throws IllegalStateException Thrown if the computation overflows.
   */
  private float denseValue(double value, String feature, int dimension) {
    final float result = (float) value;
    if (!Float.isFinite(result)) {
      throw new IllegalStateException(
          "dense feature " + feature + dimension + " is not finite");
    }
    return result;
  }

  /**
   * Computes and caches the mean contextual vector of a mention.
   *
   * @param m The mention index.
   * @return The mean of the mention's token vectors, computed once.
   */
  private float[] spanVector(int m) {
    if (spanVectors[m] == null) {
      final Mention mention = mentions.get(m);
      spanVectors[m] = mention.firstToken() < 0
          ? new float[tokenVectors[0].length]
          : mean(tokenVectors, mention.firstToken(), mention.lastToken() + 1);
    }
    return spanVectors[m];
  }

  /**
   * Computes the mean contextual vector of a cluster.
   *
   * @param i The candidate mention index.
   * @return The mean of the span vectors in the candidate's cluster.
   */
  private float[] clusterVector(int i) {
    final List<Integer> members = clusters.members(i);
    final float[][] spans = new float[members.size()][];
    for (int m = 0; m < spans.length; m++) {
      spans[m] = spanVector(members.get(m));
    }
    return mean(spans, 0, spans.length);
  }

  /**
   * Computes the componentwise mean of a vector range.
   *
   * @param vectors The vectors.
   * @param from The first included index.
   * @param to The first excluded index.
   * @return The componentwise mean.
   */
  private float[] mean(float[][] vectors, int from, int to) {
    final double[] sum = new double[vectors[from].length];
    for (int t = from; t < to; t++) {
      for (int d = 0; d < sum.length; d++) {
        sum[d] += vectors[t][d];
      }
    }
    final float[] mean = new float[sum.length];
    for (int d = 0; d < mean.length; d++) {
      mean[d] = (float) (sum[d] / (to - from));
    }
    return mean;
  }

  /**
   * Computes the cosine similarity of two vectors.
   *
   * @param u The first vector.
   * @param v The second vector.
   * @return Their cosine, or {@code -2} when either vector is all zeros.
   */
  private double cosine(float[] u, float[] v) {
    double dot = 0.0;
    double uu = 0.0;
    double vv = 0.0;
    for (int d = 0; d < u.length; d++) {
      dot += (double) u[d] * v[d];
      uu += (double) u[d] * u[d];
      vv += (double) v[d] * v[d];
    }
    return uu == 0.0 || vv == 0.0 ? -2.0 : dot / Math.sqrt(uu * vv);
  }

  /**
   * Places a contextual cosine in a feature bucket.
   *
   * @param cosine The contextual cosine, or {@code -2} when unknown.
   * @return Its tenth-sized bucket, {@code neg}, or {@code unknown}.
   */
  private String contextBucket(double cosine) {
    if (cosine < -1.0) {
      return UNKNOWN_SIMILARITY;
    }
    if (cosine < 0.0) {
      return "neg";
    }
    return Integer.toString(Math.min(9, (int) (cosine * 10)));
  }

  /**
   * Builds and caches the names of one dense feature block.
   *
   * @param names The cached names, or {@code null}.
   * @param prefix The feature prefix.
   * @param dimension The vector dimension.
   * @return The names for the requested dimension.
   */
  private String[] names(String[] names, String prefix, int dimension) {
    if (names != null && names.length == dimension) {
      return names;
    }
    final String[] built = new String[dimension];
    for (int d = 0; d < dimension; d++) {
      built[d] = prefix + d;
    }
    return built;
  }

  /**
   * Checks whether the anaphor has an earlier mention with the same head.
   *
   * @param j The anaphor index.
   * @return Whether an earlier mention has the same head.
   */
  private boolean earlierHead(int j) {
    final String head = mentions.get(j).head();
    for (int i = 0; i < j; i++) {
      if (head.equals(mentions.get(i).head())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks whether the anaphor has an earlier exact match.
   *
   * @param j The anaphor index.
   * @return Whether an earlier mention has the same normalized text.
   */
  private boolean earlierExact(int j) {
    final String normalized = mentions.get(j).normalized();
    for (int i = 0; i < j; i++) {
      if (normalized.equals(mentions.get(i).normalized())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Computes the bucketed similarity of two head words.
   *
   * @param a The first head word.
   * @param b The second head word.
   * @return Their bucketed cosine similarity, or {@code unknown}.
   */
  private String similarity(String a, String b) {
    return similarityBucket(cosine(a, b));
  }

  /**
   * Places a word-vector cosine in a feature bucket.
   *
   * @param cosine The cosine, or {@code -2} for a missing vector.
   * @return One of five similarity ranges, or {@code unknown}.
   */
  private String similarityBucket(double cosine) {
    if (cosine < -1.0) {
      return UNKNOWN_SIMILARITY;
    }
    if (cosine < 0.3) {
      return "low";
    }
    if (cosine < 0.5) {
      return "mid";
    }
    if (cosine < 0.7) {
      return "high";
    }
    return cosine < 0.9 ? "higher" : "same";
  }

  /**
   * Computes the vector cosine of two head words.
   *
   * @param a The first head word.
   * @param b The second head word.
   * @return Their vector cosine, or {@code -2} when either word has no vector.
   */
  private double cosine(String a, String b) {
    final float[] u = vector(a);
    final float[] v = vector(b);
    if (u == null || v == null) {
      return -2.0;
    }
    return cosine(u, v);
  }

  /**
   * Looks up and validates one word vector.
   *
   * @param word The lowercased word, or {@code null}.
   * @return Its vector, or {@code null}.
   * @throws IllegalStateException Thrown if the provider violates its return contract.
   */
  private float[] vector(String word) {
    if (word == null) {
      return null;
    }
    if (vectorCache.containsKey(word)) {
      return vectorCache.get(word);
    }
    final float[] vector = vectors.vector(word);
    if (vector == null) {
      vectorCache.put(word, null);
      return null;
    }
    if (vector.length != vectorDimension) {
      throw new IllegalStateException("word vectors returned dimension " + vector.length
          + " for " + word + ", expected " + vectorDimension);
    }
    for (int d = 0; d < vector.length; d++) {
      if (!Float.isFinite(vector[d])) {
        throw new IllegalStateException("word vectors returned a non-finite value for "
          + word + " at dimension " + d);
      }
    }
    vectorCache.put(word, vector);
    return vector;
  }

  /**
   * Counts earlier mentions in the same sentence.
   *
   * @param j The mention index.
   * @return The number of mentions before it in the same sentence.
   */
  private int positionInSentence(int j) {
    int position = 0;
    for (int i = j - 1; i >= 0 && mentions.get(i).sentence() == mentions.get(j).sentence(); i--) {
      position++;
    }
    return position;
  }

  /**
   * Adds a predicate and its mention-kind conjunction when the predicate is true.
   *
   * @param features The destination.
   * @param name The predicate name.
   * @param holds Whether the predicate is true.
   * @param kinds The mention-kind pair.
   */
  private void sieve(List<String> features, String name, boolean holds, String kinds) {
    if (holds) {
      features.add(name);
      features.add(name + '|' + kinds);
    }
  }

  /**
   * Classifies a mention for feature generation.
   *
   * @param mention The mention.
   * @return Its feature kind: pronoun, proper, nominal, or entity.
   */
  private String kind(Mention mention) {
    if (mention.pronoun()) {
      return "pronoun";
    }
    if (mention.namedEntity()) {
      return "entity";
    }
    return mention.proper() ? "proper" : "nominal";
  }

  /**
   * Reads a mention's first word.
   *
   * @param mention The mention.
   * @return Its first word, or an empty string.
   */
  private String first(Mention mention) {
    return mention.words().isEmpty() ? "" : mention.words().get(0);
  }

  /**
   * Classifies a mention by definiteness.
   *
   * @param mention The mention.
   * @return Its proper, definite, or indefinite shape.
   */
  private String shape(Mention mention) {
    if (mention.proper()) {
      return "proper";
    }
    return mention.indefinite() ? "indefinite" : "definite";
  }

  /**
   * Places a count in a feature bucket.
   *
   * @param value The count.
   * @return Its distance and length bucket.
   */
  private String bucket(int value) {
    if (value <= 3) {
      return Integer.toString(value);
    }
    if (value <= 7) {
      return "4-7";
    }
    if (value <= 15) {
      return "8-15";
    }
    return "16+";
  }
}
