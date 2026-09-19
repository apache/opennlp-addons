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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

import opennlp.tools.coref.Mention.Gender;
import opennlp.tools.coref.Mention.Number;
import opennlp.tools.coref.Mention.Person;
import opennlp.tools.document.Annotation;
import opennlp.tools.ml.model.MaxentModel;
import opennlp.tools.util.StringUtil;

/**
 * Resolves the mentions of one document with the precision-ranked sieves of
 * <a href="https://aclanthology.org/J13-4004/">Lee et al. (Computational Linguistics
 * 2013), "Deterministic Coreference Resolution Based on Entity-Centric, Precision-Ranked
 * Rules"</a>.
 *
 * <p>Each sieve visits unresolved mentions in text order and tests candidates in salience
 * order. Tests compare accumulated cluster attributes and reject nested mentions or
 * conflicting entity types.</p>
 *
 * <p>The speaker sieve handles first and second person pronouns before the string sieves.
 * It uses the speakers layer when present and quoted-speech attribution otherwise.</p>
 *
 * <p>With a {@link MaxentModel}, the resolver applies the speaker and string sieves and
 * then ranks the remaining candidate antecedents with {@link CorefContextGenerator}.</p>
 */
final class SieveResolver {

  /** The outcome of a pair the ranker links. */
  static final String LINK = "link";

  /** The outcome of a pair the ranker leaves apart. */
  static final String APART = "apart";

  /** How many candidates in salience order the ranker scores for a non-pronoun. */
  private static final int RANKER_CANDIDATES = 60;

  /** How many sentences back a pronoun may find its antecedent. */
  private static final int PRONOUN_WINDOW = 3;

  /** No limit on how many sentences back an antecedent may lie. */
  private static final int UNLIMITED = Integer.MAX_VALUE;

  /** The speaker of text outside every quotation when no speakers layer says otherwise. */
  private static final String NARRATOR = "";

  /** How many tokens a verb of speech may lie from the person it attributes a quote to. */
  private static final int ATTRIBUTION_DISTANCE = 2;

  /** Tests whether the anaphor may link to the candidate antecedent. */
  private interface Link {
    boolean test(int antecedent, int anaphor);
  }

  private final List<Mention> mentions;
  private final Clusters clusters;
  private final String[] forms;
  private final int[] sentenceOfToken;
  private final List<Annotation<String>> speakers;
  private final Set<String> personTypes;
  private final Set<String> neutralTypes;
  private final WordVectors vectors;
  private final float[][] tokenVectors;
  private final List<List<Integer>> bySentence;

  /** The speaker of every mention, filled on first use. */
  private String[] speakerOf;

  /**
   * Initializes the resolver.
   *
   * @param mentions The mentions in text order.
   * @param clusters The clusters over the mentions, initially all singletons.
   * @param forms The original token forms.
   * @param sentenceOfToken The sentence index of every token.
   * @param speakers The speakers layer, or {@code null} when the document carries none.
   * @param personTypes The lowercased entity types gendered pronouns may resolve to.
   * @param neutralTypes The lowercased entity types neutral pronouns may resolve to.
   * @param vectors The word vectors for the ranker's similarity features, or
   *                {@code null}.
   * @param tokenVectors One contextual vector per token for the ranker's span features,
   *                     or {@code null}.
   */
  SieveResolver(List<Mention> mentions, Clusters clusters, String[] forms,
      int[] sentenceOfToken, List<Annotation<String>> speakers, Set<String> personTypes,
      Set<String> neutralTypes, WordVectors vectors, float[][] tokenVectors) {
    this.mentions = mentions;
    this.clusters = clusters;
    this.forms = forms;
    this.sentenceOfToken = sentenceOfToken;
    this.speakers = speakers;
    this.personTypes = personTypes;
    this.neutralTypes = neutralTypes;
    this.vectors = vectors;
    this.tokenVectors = tokenVectors;
    bySentence = new ArrayList<>();
    for (int i = 0; i < mentions.size(); i++) {
      final int sentence = mentions.get(i).sentence();
      while (bySentence.size() <= sentence) {
        bySentence.add(new ArrayList<>());
      }
      bySentence.get(sentence).add(i);
    }
  }

  /**
   * Resolves with a model: the speaker and string sieves, then for each anaphor still
   * first of its cluster the best-scoring admissible candidate. A pair classifier links
   * when the best link probability reaches the threshold; a ranking model links when
   * the best candidate outscores the option of starting a new chain.
   *
   * @param model The model with the {@link #LINK} and {@link #APART} outcomes.
   * @param ranking Whether the model was trained by ranking.
   * @param threshold The least link probability that makes a pair classifier link.
   */
  void resolve(MaxentModel model, boolean ranking, double threshold) {
    resolvePrecise();
    final CorefContextGenerator features = new CorefContextGenerator(this);
    final int link = model.getIndex(LINK);
    for (int j = 0; j < mentions.size(); j++) {
      if (clusters.find(j) != j || !rankable(j)) {
        continue;
      }
      int best = -1;
      double bestScore = ranking
          ? score(model, features, features.newChainFeatures(j), link)
          : Double.NEGATIVE_INFINITY;
      for (final int i : rankerCandidates(j)) {
        final double score = score(model, features, features.features(i, j), link);
        if (score > bestScore) {
          bestScore = score;
          best = i;
        }
      }
      if (best >= 0 && (ranking || bestScore >= threshold)) {
        clusters.union(best, j);
      }
    }
  }

  /**
   * Scores one antecedent or new-chain option.
   *
   * @param model The pair model.
   * @param features The feature generator.
   * @param option The option features.
   * @param link The link outcome index.
   * @return The option's link probability.
   */
  private double score(MaxentModel model, CorefContextGenerator features,
      CorefContextGenerator.Features option, int link) {
    return model.eval(features.names(option), features.values(option))[link];
  }

  /**
   * Runs the sieves that precede the ranker: the speaker sieve and the string sieves,
   * exact match, relaxed string match, acronym, and person name, whose precision the
   * ranker is not asked to relearn. A trainer runs the same passes before it reads its
   * pairs.
   */
  void resolvePrecise() {
    speakerSieve();
    pass(nominal(), UNLIMITED, this::exactMatch);
    pass(nominal(), UNLIMITED, this::relaxedStringMatch);
    pass(nominal(), UNLIMITED, (i, j) -> acronym(i, j) || personName(i, j));
  }

  /**
   * Builds the predicate used by nominal sieves.
   *
   * @return The nominal-sieve anaphor predicate.
   */
  private IntPredicate nominal() {
    return j -> !mentions.get(j).pronoun() && !mentions.get(j).indefinite()
        && mentions.get(j).head() != null;
  }

  /**
   * Checks whether the ranker may resolve a mention.
   *
   * @param j The mention index.
   * @return Whether the ranker may resolve the mention.
   */
  boolean rankable(int j) {
    final Mention mention = mentions.get(j);
    return mention.head() != null && !(mention.pronoun() && mention.person() != Person.THIRD);
  }

  /**
   * Lists the candidates the ranker scores for an anaphor: in salience order, within
   * the pronoun window for a pronoun and capped for other mentions, skipping the
   * anaphor's own cluster, containing mentions, and type-incompatible clusters.
   *
   * @param j The anaphor index.
   * @return The candidate mention indexes in salience order.
   */
  List<Integer> rankerCandidates(int j) {
    final Mention mention = mentions.get(j);
    final List<Integer> admissible = new ArrayList<>();
    for (final int i : candidates(j, mention, mention.pronoun() ? PRONOUN_WINDOW : UNLIMITED)) {
      if (clusters.find(i) == clusters.find(j) || mention.nests(mentions.get(i))
          || !clusters.typesCompatible(i, j)) {
        continue;
      }
      admissible.add(i);
      if (!mention.pronoun() && admissible.size() >= RANKER_CANDIDATES) {
        break;
      }
    }
    return admissible;
  }

  /**
   * Reads the speaker assigned to a mention.
   *
   * @param j The mention index.
   * @return Its speaker, computed once.
   */
  String speakerOf(int j) {
    if (speakerOf == null) {
      speakerOf = speakerOfMentions();
    }
    return speakerOf[j];
  }

  /**
   * Reads the detected mentions.
   *
   * @return The mentions in text order.
   */
  List<Mention> mentions() {
    return mentions;
  }

  /**
   * Reads the current mention clusters.
   *
   * @return The mention clusters.
   */
  Clusters clusters() {
    return clusters;
  }

  /**
   * Reads the configured word vectors.
   *
   * @return The word vectors for similarity features, or {@code null}.
   */
  WordVectors vectors() {
    return vectors;
  }

  /**
   * Reads the contextual token vectors.
   *
   * @return One contextual vector per token, or {@code null}.
   */
  float[][] tokenVectors() {
    return tokenVectors;
  }

  /** Runs every sieve in precision order. */
  void resolve() {
    resolvePrecise();
    final IntPredicate nominal = nominal();
    pass(nominal, UNLIMITED, (i, j) -> strictHeadMatch(i, j, true, true));
    pass(nominal, UNLIMITED, (i, j) -> strictHeadMatch(i, j, true, false));
    pass(nominal, UNLIMITED, (i, j) -> strictHeadMatch(i, j, false, true));
    pass(nominal, UNLIMITED, this::properHeadMatch);
    pass(nominal, UNLIMITED, this::relaxedHeadMatch);
    // Demonstratives mostly point at clauses and events; the rules leave them to a ranker.
    pass(j -> mentions.get(j).pronoun() && mentions.get(j).person() == Person.THIRD
        && CorefLexicon.demonstrative(mentions.get(j).head()) == null,
        PRONOUN_WINDOW, this::pronounMatch);
  }

  /**
   * Chains the first and second person mentions of each speaker and joins a speaker's
   * first person singular chain to the speaker's own mention where a quotation is
   * attributed to one.
   */
  private void speakerSieve() {
    if (speakerOf == null) {
      speakerOf = speakerOfMentions();
    }
    final Map<String, Integer> firstOfGroup = new HashMap<>();
    for (int j = 0; j < mentions.size(); j++) {
      final Mention mention = mentions.get(j);
      if (!mention.pronoun() || mention.person() == Person.THIRD) {
        continue;
      }
      final String group = speakerOf[j] + '\u0000' + mention.person() + '\u0000'
          + (mention.person() == Person.FIRST && mention.number() == Number.PLURAL);
      final Integer first = firstOfGroup.putIfAbsent(group, j);
      if (first != null) {
        clusters.union(first, j);
      }
    }
  }

  /**
   * Assigns every mention its speaker: the covering speakers layer value, or without a
   * layer the narrator outside quotations and, inside one, the person the quotation is
   * attributed to or an anonymous speaker unique to that quotation. A quotation
   * attributed to a person mention is keyed by that mention, so the quoted first person
   * singular chain forms around it.
   *
   * @return The speaker identifier of each mention.
   */
  private String[] speakerOfMentions() {
    final String[] speakerOf = new String[mentions.size()];
    if (speakers != null) {
      for (int j = 0; j < mentions.size(); j++) {
        speakerOf[j] = NARRATOR;
        for (final Annotation<String> speaker : speakers) {
          if (speaker.span().contains(mentions.get(j).span().getStart())) {
            speakerOf[j] = speaker.value();
            break;
          }
        }
      }
      return speakerOf;
    }
    // quoteOf[t] is the index of the quotation token t lies in, or 0 outside every one
    final int[] quoteOf = new int[forms.length];
    final List<Integer> quoteStarts = new ArrayList<>();
    final List<Integer> quoteEnds = new ArrayList<>();
    int open = 0;
    for (int t = 0; t < forms.length; t++) {
      if (open == 0 && CorefLexicon.opensQuote(forms[t])) {
        quoteStarts.add(t);
        quoteEnds.add(forms.length - 1);
        open = quoteStarts.size();
      } else if (open > 0 && CorefLexicon.closesQuote(forms[t])) {
        quoteEnds.set(open - 1, t);
        open = 0;
      } else if (open > 0) {
        quoteOf[t] = open;
      }
    }
    final Map<Integer, Integer> attributedTo = new HashMap<>();
    for (int q = 0; q < quoteStarts.size(); q++) {
      final int attributed = attribute(quoteStarts.get(q), quoteEnds.get(q), quoteOf);
      if (attributed >= 0) {
        attributedTo.put(q + 1, attributed);
      }
    }
    for (int j = 0; j < mentions.size(); j++) {
      final int first = mentions.get(j).firstToken();
      final int quote = first < 0 ? 0 : quoteOf[first];
      if (quote == 0) {
        speakerOf[j] = NARRATOR;
      } else if (attributedTo.containsKey(quote)) {
        final int speaker = attributedTo.get(quote);
        speakerOf[j] = "mention:" + speaker;
        if (mentions.get(j).person() == Person.FIRST
            && mentions.get(j).number() == Number.SINGULAR) {
          clusters.union(speaker, j);
        }
      } else {
        speakerOf[j] = "quote:" + quote;
      }
    }
    return speakerOf;
  }

  /**
   * Finds the person a quotation is attributed to: a person mention outside the
   * quotation, in the sentence of its opening or closing mark, within a couple of
   * tokens of a verb of speech.
   *
   * @param quoteStart The opening quote token index.
   * @param quoteEnd The closing quote token index.
   * @param quoteOf The quote index of each token, or zero outside quotations.
   * @return The mention index, or {@code -1} when no attribution is found.
   */
  private int attribute(int quoteStart, int quoteEnd, int[] quoteOf) {
    for (int i = 0; i < mentions.size(); i++) {
      final Mention mention = mentions.get(i);
      if (mention.firstToken() < 0 || quoteOf[mention.firstToken()] != 0
          || mention.pronoun() || mention.type() == null
          || !personTypes.contains(mention.type())) {
        continue;
      }
      final int sentence = mention.sentence();
      if (sentence != sentenceOfToken[quoteStart] && sentence != sentenceOfToken[quoteEnd]) {
        continue;
      }
      if (speechVerbNear(mention.firstToken() - 1, -1, quoteOf)
          || speechVerbNear(mention.lastToken() + 1, 1, quoteOf)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Looks near a person mention for a speech verb outside a quotation.
   *
   * @param from The first token index.
   * @param step The search direction, {@code -1} or {@code 1}.
   * @param quoteOf The quote index of each token.
   * @return Whether a speech verb is found.
   */
  private boolean speechVerbNear(int from, int step, int[] quoteOf) {
    for (int t = from, seen = 0; t >= 0 && t < forms.length && seen < ATTRIBUTION_DISTANCE;
        t += step, seen++) {
      if (quoteOf[t] != 0) {
        return false;
      }
      if (CorefLexicon.speechVerb(StringUtil.toLowerCase(forms[t]))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Runs one sieve: for each anaphor that heads its own cluster, links the first
   * candidate antecedent in salience order that passes the link test.
   *
   * @param anaphor The anaphor predicate.
   * @param window The preceding-sentence limit.
   * @param link The pair predicate.
   */
  private void pass(IntPredicate anaphor, int window, Link link) {
    for (int j = 0; j < mentions.size(); j++) {
      if (clusters.find(j) != j || !anaphor.test(j)) {
        continue;
      }
      final Mention mention = mentions.get(j);
      final int limit = reflexive(mention) ? 0 : window;
      for (final int i : candidates(j, mention, limit)) {
        if (clusters.find(i) == clusters.find(j) || mention.nests(mentions.get(i))
            || !clusters.typesCompatible(i, j)) {
          continue;
        }
        if (link.test(i, j) && clusters.union(i, j)) {
          break;
        }
      }
    }
  }

  /**
   * Checks whether a mention is a reflexive pronoun.
   *
   * @param mention The mention.
   * @return Whether it is a reflexive pronoun.
   */
  private boolean reflexive(Mention mention) {
    return mention.pronoun()
        && (mention.head().endsWith("self") || mention.head().endsWith("selves"));
  }

  /**
   * Orders the candidate antecedents of an anaphor: the same sentence left to right,
   * then each preceding sentence within the window, nearest first, read right to left
   * for a pronoun and left to right otherwise.
   *
   * @param j The anaphor index.
   * @param mention The anaphor.
   * @param window The preceding-sentence limit.
   * @return The candidate indexes in salience order.
   */
  private List<Integer> candidates(int j, Mention mention, int window) {
    final List<Integer> ordered = new ArrayList<>();
    final int sentence = mention.sentence();
    for (final int i : bySentence.get(sentence)) {
      if (i < j) {
        ordered.add(i);
      }
    }
    final int farthest = window == UNLIMITED ? 0 : Math.max(0, sentence - window);
    for (int s = sentence - 1; s >= farthest; s--) {
      final List<Integer> earlier = bySentence.get(s);
      if (mention.pronoun()) {
        for (int k = earlier.size() - 1; k >= 0; k--) {
          ordered.add(earlier.get(k));
        }
      } else {
        ordered.addAll(earlier);
      }
    }
    return ordered;
  }

  /**
   * Checks whether two mentions have identical normalized text.
   *
   * @param i The antecedent index.
   * @param j The anaphor index.
   * @return Whether their normalized text is identical.
   */
  boolean exactMatch(int i, int j) {
    final Mention antecedent = mentions.get(i);
    return !antecedent.pronoun()
        && !antecedent.normalized().isEmpty()
        && antecedent.normalized().equals(mentions.get(j).normalized());
  }

  /**
   * Checks whether two mentions have identical text through their heads.
   *
   * @param i The antecedent index.
   * @param j The anaphor index.
   * @return Whether their text through the head is identical.
   */
  boolean relaxedStringMatch(int i, int j) {
    final Mention antecedent = mentions.get(i);
    return !antecedent.pronoun()
        && !antecedent.headPrefix().isEmpty()
        && antecedent.headPrefix().equals(mentions.get(j).headPrefix());
  }

  /**
   * Checks whether one proper name is the acronym of another.
   *
   * @param i The antecedent index.
   * @param j The anaphor index.
   * @return Whether one proper name is the other's acronym.
   */
  boolean acronym(int i, int j) {
    final Mention antecedent = mentions.get(i);
    final Mention anaphor = mentions.get(j);
    return antecedent.proper() && anaphor.proper()
        && (acronymOf(anaphor, antecedent) || acronymOf(antecedent, anaphor));
  }

  /**
   * Checks whether a one-token uppercase mention spells the initials of the capitalized
   * words of a multi-word mention.
   *
   * @param candidate The possible acronym.
   * @param name The possible expanded name.
   * @return Whether the candidate spells the name's initials.
   */
  private boolean acronymOf(Mention candidate, Mention name) {
    if (candidate.firstToken() < 0 || candidate.firstToken() != candidate.lastToken()
        || name.firstToken() < 0 || name.lastToken() - name.firstToken() < 1) {
      return false;
    }
    final String acronym = forms[candidate.firstToken()];
    if (acronym.codePointCount(0, acronym.length()) < 2) {
      return false;
    }
    for (int c = 0; c < acronym.length();) {
      final int codePoint = acronym.codePointAt(c);
      if (!Character.isUpperCase(codePoint)) {
        return false;
      }
      c += Character.charCount(codePoint);
    }
    final StringBuilder initials = new StringBuilder();
    for (int t = name.firstToken(); t <= name.lastToken(); t++) {
      final String form = forms[t];
      if (!form.isEmpty()) {
        final int codePoint = form.codePointAt(0);
        if (Character.isUpperCase(codePoint)) {
          initials.appendCodePoint(codePoint);
        }
      }
    }
    return initials.codePointCount(0, initials.length()) >= 2
        && initials.toString().equals(acronym);
  }

  /**
   * Links a person's single-word name to a longer name of the same person that starts
   * or ends with it, so a surname or first name finds the full name. Both mentions must
   * be typed as people; place and organization names, where a shared word need not mean
   * a shared referent, never take this link.
   *
   * @param i The antecedent index.
   * @param j The anaphor index.
   * @return Whether the two person names are compatible short and long forms.
   */
  boolean personName(int i, int j) {
    final Mention antecedent = mentions.get(i);
    final Mention anaphor = mentions.get(j);
    if (!antecedent.proper() || !anaphor.proper()
        || !personType(antecedent.type()) || !personType(anaphor.type())
        || !clusters.numbersAgree(i, j)) {
      return false;
    }
    final List<String> shorter = nameWords(antecedent.words().size() <= anaphor.words().size()
        ? antecedent : anaphor);
    final List<String> longer = nameWords(antecedent.words().size() <= anaphor.words().size()
        ? anaphor : antecedent);
    return shorter.size() == 1 && longer.size() >= 2
        && (longer.get(0).equals(shorter.get(0))
            || longer.get(longer.size() - 1).equals(shorter.get(0)));
  }

  /**
   * Checks whether an entity type is configured as a person type.
   *
   * @param type The entity type, or {@code null}.
   * @return Whether it is configured as a person type.
   */
  private boolean personType(String type) {
    return type != null && personTypes.contains(type);
  }

  /**
   * Reads the words of a name after any leading title.
   *
   * @param mention The name mention.
   * @return Its words without leading titles.
   */
  private List<String> nameWords(Mention mention) {
    final List<String> words = mention.words();
    int start = 0;
    while (start < words.size() - 1 && CorefLexicon.title(words.get(start))) {
      start++;
    }
    return words.subList(start, words.size());
  }

  /**
   * Links an anaphor whose head matches a head in the candidate's cluster, optionally
   * requiring that every content word of the anaphor's cluster appears in the
   * candidate's cluster and that every modifier of the anaphor appears in the candidate
   * mention.
   *
   * @param i The antecedent index.
   * @param j The anaphor index.
   * @param wordInclusion Whether to require cluster word inclusion.
   * @param compatibleModifiers Whether to require anaphor modifier inclusion.
   * @return Whether the mentions satisfy the requested head test.
   */
  boolean strictHeadMatch(int i, int j, boolean wordInclusion,
      boolean compatibleModifiers) {
    final Mention antecedent = mentions.get(i);
    final Mention anaphor = mentions.get(j);
    if (antecedent.pronoun() || !clusters.heads(i).contains(anaphor.head())) {
      return false;
    }
    if (wordInclusion && !clusters.words(i).containsAll(clusters.words(j))) {
      return false;
    }
    return !compatibleModifiers || modifiersAppearIn(anaphor, antecedent);
  }

  /**
   * Checks whether all content modifiers of an anaphor occur in an antecedent.
   *
   * @param anaphor The anaphor.
   * @param antecedent The antecedent.
   * @return Whether every content modifier before the anaphor's head occurs in the antecedent.
   */
  private boolean modifiersAppearIn(Mention anaphor, Mention antecedent) {
    final List<String> words = anaphor.words();
    final Set<String> candidateWords = new HashSet<>(antecedent.words());
    for (final String word : words) {
      if (word.equals(anaphor.head())) {
        break;
      }
      if (!CorefLexicon.stopWord(word) && !candidateWords.contains(word)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Links two proper names with the same head word, agreeing number, and no differing
   * numeric modifiers, so {@code President Obama} finds {@code Barack Obama}. A head
   * that names a kind of place or institution, such as {@code University}, is shared by
   * many distinct names, so there the modifiers of one side must appear in the other:
   * {@code Harvard University} never finds {@code Stanford University}.
   *
   * @param i The antecedent index.
   * @param j The anaphor index.
   * @return Whether the proper names satisfy the head and modifier checks.
   */
  boolean properHeadMatch(int i, int j) {
    final Mention antecedent = mentions.get(i);
    final Mention anaphor = mentions.get(j);
    if (!antecedent.proper() || !anaphor.proper()
        || !antecedent.head().equals(anaphor.head())
        || !clusters.numbersAgree(i, j)
        || numbersDiffer(antecedent, anaphor)) {
      return false;
    }
    return !CorefLexicon.compoundNameHead(anaphor.head())
        || modifiersAppearIn(anaphor, antecedent)
        || modifiersAppearIn(antecedent, anaphor);
  }

  /**
   * Checks whether two mentions contain different numeric modifiers.
   *
   * @param a The first mention.
   * @param b The second mention.
   * @return Whether both contain different numeric modifiers.
   */
  private boolean numbersDiffer(Mention a, Mention b) {
    final Set<String> numbersA = numerals(a);
    final Set<String> numbersB = numerals(b);
    return !numbersA.isEmpty() && !numbersB.isEmpty() && !numbersA.equals(numbersB);
  }

  /**
   * Reads the numeric modifiers of a mention.
   *
   * @param mention The mention.
   * @return Its words that begin with a Unicode digit.
   */
  private Set<String> numerals(Mention mention) {
    final Set<String> numerals = new HashSet<>();
    for (final String word : mention.words()) {
      if (!word.isEmpty() && Character.isDigit(word.codePointAt(0))) {
        numerals.add(word);
      }
    }
    return numerals;
  }

  /**
   * Links two entity mentions of the same type when the anaphor's head appears anywhere
   * in the candidate's cluster and the cluster words include the anaphor's, unless the
   * two names differ by a compound head such as {@code city}, which tells
   * {@code Kansas City} apart from {@code Kansas}.
   *
   * @param i The antecedent index.
   * @param j The anaphor index.
   * @return Whether the entity mentions satisfy the relaxed head checks.
   */
  boolean relaxedHeadMatch(int i, int j) {
    final Mention antecedent = mentions.get(i);
    final Mention anaphor = mentions.get(j);
    if (antecedent.pronoun() || antecedent.type() == null
        || !antecedent.type().equals(anaphor.type())
        || !clusters.words(i).contains(anaphor.head())
        || !clusters.words(i).containsAll(clusters.words(j))) {
      return false;
    }
    return !compoundDiffers(antecedent, anaphor) && !compoundDiffers(anaphor, antecedent);
  }

  /**
   * Checks whether a compound head distinguishes two names.
   *
   * @param compound The possible compound name.
   * @param other The other name.
   * @return Whether the first name has a compound head absent from the other.
   */
  private boolean compoundDiffers(Mention compound, Mention other) {
    return CorefLexicon.compoundNameHead(compound.head())
        && !other.words().contains(compound.head());
  }

  /**
   * Links a pronoun to the nearest candidate whose cluster agrees in number, gender,
   * animacy, and person and whose entity type the pronoun class accepts.
   *
   * @param i The antecedent index.
   * @param j The pronoun index.
   * @return Whether the pronoun may refer to the antecedent cluster.
   */
  boolean pronounMatch(int i, int j) {
    if (!clusters.attributesAgree(i, j)) {
      return false;
    }
    final String type = clusters.type(i);
    if (type == null) {
      return true;
    }
    final Gender gender = mentions.get(j).gender();
    if (gender == Gender.NEUTRAL) {
      return neutralTypes.contains(type);
    }
    if (gender == Gender.MALE || gender == Gender.FEMALE) {
      return personTypes.contains(type);
    }
    return personTypes.contains(type) || neutralTypes.contains(type);
  }
}
