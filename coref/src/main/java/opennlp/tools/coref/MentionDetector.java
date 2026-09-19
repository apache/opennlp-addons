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
import java.util.Set;

import opennlp.tools.coref.CorefLexicon.Pronoun;
import opennlp.tools.coref.Mention.Animacy;
import opennlp.tools.coref.Mention.Gender;
import opennlp.tools.coref.Mention.Number;
import opennlp.tools.coref.Mention.Person;
import opennlp.tools.document.Annotation;
import opennlp.tools.namefind.NameFinderAnnotator;
import opennlp.tools.parser.ParserAnnotator.Phrase;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

/**
 * Collects the candidate mentions of a document and computes their attributes.
 *
 * <p>Entities come from the entity layer, noun phrases come from parser phrases and NP
 * chunks, and pronouns come from tagged tokens. Of parser phrases sharing a head, only
 * the largest remains. An exact parser and chunk duplicate keeps the parser head. A
 * phrase headed by an entity extends that entity mention; a phrase cut by an entity
 * boundary is omitted. Pleonastic {@code it} is omitted.</p>
 */
final class MentionDetector {

  /** The tag prefix of nouns in the Penn Treebank tag set. */
  private static final String NOUN_TAG_PREFIX = "NN";

  /** The Penn Treebank tags of proper nouns. */
  private static final Set<String> PROPER_TAGS = Set.of("NNP", "NNPS");

  /** The Penn Treebank tags of plural nouns. */
  private static final Set<String> PLURAL_TAGS = Set.of("NNS", "NNPS");

  /** POS tags that mark pronoun tokens: Penn Treebank PRP and PRP$, Universal PRON. */
  private static final Set<String> PRONOUN_TAGS = Set.of("PRP", "PRP$", "PRON");

  /** Tags of the words that open a definite noun phrase. */
  private static final Set<String> DEFINITE_OPENER_TAGS = Set.of("DT", "PRP$", "WDT", "CD");

  /** The chunk type of noun phrases. */
  private static final String NOUN_PHRASE = "NP";

  /** The Penn Treebank tag of the possessive marker. */
  private static final String POSSESSIVE_TAG = "POS";

  /** The lowercased possessive marker, for tag sets that do not single it out. */
  private static final String POSSESSIVE_MARKER = "'s";

  /** The preposition that separates a name's head segment from a trailing qualifier. */
  private static final String OF = "of";

  /** The comma, which separates a head segment from an appositive or qualifier. */
  private static final String COMMA = ",";

  /** Entity type labels of organizations, whose number is left open. */
  private static final Set<String> ORGANIZATION_TYPES = Set.of("organization", "org");

  /** How many tokens after a pleonastic verb its complement may follow. */
  private static final int PLEONASTIC_WINDOW = 6;

  /** One mention candidate before its attributes are computed. */
  private static final class Candidate {
    Span span;
    final String kind;
    final int entity;
    final String type;
    int firstToken;
    int lastToken;
    /** The head token the parse names, or {@code -1} to find it by rule. */
    int head;

    /**
     * Creates a mention candidate.
     *
     * @param span The source span.
     * @param kind The mention kind.
     * @param entity The source entity index.
     * @param type The entity type, or {@code null}.
     * @param firstToken The first intersecting token index.
     * @param lastToken The last intersecting token index.
     */
    Candidate(Span span, String kind, int entity, String type, int firstToken,
        int lastToken) {
      this.span = span;
      this.kind = kind;
      this.entity = entity;
      this.type = type;
      this.firstToken = firstToken;
      this.lastToken = lastToken;
      this.head = -1;
    }
  }

  /**
   * One noun phrase with its token range and head. A merged phrase joins two chunks
   * across {@code of} or {@code and}; it is a candidate along with its parts and does
   * not expand an entity.
   *
   * @param span The phrase span.
   * @param first The first token index.
   * @param last The last token index.
   * @param head The head token index.
   * @param merged Whether this phrase joins two chunks.
   */
  private record NounPhrase(Span span, int first, int last, int head, boolean merged) {
  }

  /** The tag of a coordinating conjunction. */
  private static final String CONJUNCTION_TAG = "CC";

  /** The conjunction that joins two noun phrases into a plural mention. */
  private static final String AND = "and";

  /** The tag of a determiner, which a standalone demonstrative carries. */
  private static final String DETERMINER_TAG = "DT";

  private final Set<String> personTypes;
  private final CharSequence text;
  private final List<Annotation<String>> sentences;
  private final List<Annotation<String>> tokens;
  private final String[] lower;
  private final String[] tag;
  private final int[] sentenceOfToken;
  private final int[] sentenceEndToken;

  /**
   * Initializes mention detection over aligned document layers.
   *
   * @param personTypes The lowercased person entity types.
   * @param text The document text.
   * @param sentences The sentence layer.
   * @param tokens The token layer.
   * @param tags The token-aligned POS tags.
   * @param sentenceOfToken The sentence index of each token.
   */
  private MentionDetector(Set<String> personTypes, CharSequence text,
      List<Annotation<String>> sentences, List<Annotation<String>> tokens,
      List<Annotation<String>> tags, int[] sentenceOfToken) {
    this.personTypes = personTypes;
    this.text = text;
    this.sentences = sentences;
    this.tokens = tokens;
    this.sentenceOfToken = sentenceOfToken;
    lower = new String[tokens.size()];
    tag = new String[tokens.size()];
    sentenceEndToken = new int[tokens.size()];
    for (int t = 0; t < tokens.size(); t++) {
      lower[t] = StringUtil.toLowerCase(tokens.get(t).value());
      tag[t] = tags.get(t).value();
    }
    for (int t = tokens.size() - 1, end = tokens.size() - 1; t >= 0; t--) {
      if (t < tokens.size() - 1 && sentenceOfToken[t] != sentenceOfToken[t + 1]) {
        end = t;
      }
      sentenceEndToken[t] = end;
    }
  }

  /**
   * Detects the mentions of a document.
   *
   * @param personTypes The lowercased entity types of people.
   * @param text The document text.
   * @param sentences The sentence layer. Must not be empty when tokens are present.
   * @param tokens The token layer.
   * @param tags The POS tag layer, aligned with the tokens.
   * @param sentenceOfToken The sentence index of every token.
   * @param entities The entity layer.
   * @param chunks The chunk layer, or {@code null} when the document carries none.
   * @param phrases The parse layer, or {@code null} when the document carries none.
   * @return The mentions in text order, wider spans first at a shared start. Never
   *         {@code null}.
   */
  static List<Mention> detect(Set<String> personTypes, CharSequence text,
      List<Annotation<String>> sentences, List<Annotation<String>> tokens,
      List<Annotation<String>> tags, int[] sentenceOfToken,
      List<Annotation<String>> entities, List<Annotation<String>> chunks,
      List<Annotation<Phrase>> phrases) {
    return new MentionDetector(personTypes, text, sentences, tokens, tags, sentenceOfToken)
        .detect(entities, chunks, phrases);
  }

  /**
   * Collects and orders mentions from the available input layers.
   *
   * @param entities The entity layer.
   * @param chunks The chunk layer, or {@code null}.
   * @param phrases The parse phrase layer, or {@code null}.
   * @return The mentions in text order.
   */
  private List<Mention> detect(List<Annotation<String>> entities,
      List<Annotation<String>> chunks, List<Annotation<Phrase>> phrases) {
    final List<Candidate> candidates = new ArrayList<>();
    for (int e = 0; e < entities.size(); e++) {
      final Span span = entities.get(e).span();
      candidates.add(new Candidate(span, CorefMention.KIND_ENTITY, e,
          StringUtil.toLowerCase(entities.get(e).value()), firstToken(span),
          lastToken(span)));
    }
    final int entityCount = candidates.size();
    if (phrases != null) {
      for (final NounPhrase phrase : maximalNounPhrases(phrases)) {
        addNounPhrase(phrase, candidates, entityCount);
      }
    }
    if (chunks != null) {
      for (final NounPhrase phrase : chunkNounPhrases(chunks)) {
        addNounPhrase(phrase, candidates, entityCount);
      }
    }
    addPronouns(candidates, entityCount);
    candidates.sort((a, b) -> a.span.getStart() != b.span.getStart()
        ? Integer.compare(a.span.getStart(), b.span.getStart())
        : Integer.compare(b.span.getEnd(), a.span.getEnd()));
    final List<Mention> mentions = new ArrayList<>(candidates.size());
    for (final Candidate candidate : candidates) {
      mentions.add(mention(candidate));
    }
    return mentions;
  }

  /**
   * Reads the noun phrase chunks, joining a chunk that opens with a possessive marker to
   * the noun phrase chunk right before it, since base chunking splits {@code Norton 's
   * capital} into the possessor and the possessed while the mention is the whole phrase.
   *
   * @param chunks The chunk layer.
   * @return The noun phrases represented by the chunks.
   */
  private List<NounPhrase> chunkNounPhrases(List<Annotation<String>> chunks) {
    final List<NounPhrase> phrases = new ArrayList<>();
    int pendingFirst = -1;
    int pendingLast = -1;
    for (final Annotation<String> chunk : chunks) {
      final boolean nounPhrase = NOUN_PHRASE.equals(chunk.value());
      final int first = nounPhrase ? firstToken(chunk.span()) : -1;
      final int last = nounPhrase ? lastToken(chunk.span()) : -1;
      final boolean valid = nounPhrase && first >= 0 && last >= first;
      if (valid && pendingFirst >= 0 && first == pendingLast + 1
          && (POSSESSIVE_TAG.equals(tag[first]) || POSSESSIVE_MARKER.equals(lower[first]))) {
        pendingLast = last;
        continue;
      }
      if (pendingFirst >= 0) {
        phrases.add(nounPhrase(pendingFirst, pendingLast));
      }
      pendingFirst = valid ? first : -1;
      pendingLast = valid ? last : -1;
    }
    if (pendingFirst >= 0) {
      phrases.add(nounPhrase(pendingFirst, pendingLast));
    }
    return withMergedPhrases(phrases);
  }

  /**
   * Adds, for two chunks a lone {@code of} or {@code and} separates, the phrase spanning
   * both: OntoNotes annotates {@code the law of negligence} and {@code Kim and Lee} as
   * mentions where base chunking stops at the preposition or conjunction. The phrase
   * takes the first chunk's head; its parts stay candidates of their own.
   *
   * @param phrases The noun phrases in text order.
   * @return The original and merged noun phrases.
   */
  private List<NounPhrase> withMergedPhrases(List<NounPhrase> phrases) {
    final List<NounPhrase> all = new ArrayList<>(phrases.size() * 2);
    for (int p = 0; p < phrases.size(); p++) {
      final NounPhrase phrase = phrases.get(p);
      all.add(phrase);
      if (p + 1 >= phrases.size()) {
        continue;
      }
      final NounPhrase next = phrases.get(p + 1);
      final int between = phrase.last() + 1;
      if (next.first() != between + 1
          || sentenceOfToken[phrase.first()] != sentenceOfToken[next.last()]) {
        continue;
      }
      final boolean of = OF.equals(lower[between]);
      final boolean and = AND.equals(lower[between]) && CONJUNCTION_TAG.equals(tag[between]);
      if (of || and) {
        all.add(new NounPhrase(new Span(tokens.get(phrase.first()).span().getStart(),
            tokens.get(next.last()).span().getEnd()), phrase.first(), next.last(),
            phrase.head(), true));
      }
    }
    return all;
  }

  /**
   * Builds a noun phrase over a token range.
   *
   * @param first The first token index.
   * @param last The last token index.
   * @return The noun phrase with its inferred head.
   */
  private NounPhrase nounPhrase(int first, int last) {
    return new NounPhrase(new Span(tokens.get(first).span().getStart(),
        tokens.get(last).span().getEnd()), first, last, headToken(first, last), false);
  }

  /**
   * Reads the noun phrases of the parse layer. When phrases share a head token, only the
   * largest is retained, matching OntoNotes annotation.
   *
   * @param phrases The parse phrase layer.
   * @return The widest noun phrase for each head token.
   */
  private List<NounPhrase> maximalNounPhrases(List<Annotation<Phrase>> phrases) {
    final List<NounPhrase> nounPhrases = new ArrayList<>();
    for (final Annotation<Phrase> phrase : phrases) {
      if (!NOUN_PHRASE.equals(phrase.value().label())) {
        continue;
      }
      final int first = firstToken(phrase.span());
      final int last = lastToken(phrase.span());
      int head = firstToken(phrase.value().head());
      if (first < 0 || last < first || head < first || head > last) {
        continue;
      }
      // Head rules make the possessive marker the head of a possessive phrase; the
      // mention's head is the noun the marker follows.
      if (head > first && (POSSESSIVE_TAG.equals(tag[head]) || POSSESSIVE_MARKER.equals(lower[head]))) {
        head = headToken(first, head - 1);
      }
      nounPhrases.add(new NounPhrase(phrase.span(), first, last, head, false));
    }
    final List<NounPhrase> maximal = new ArrayList<>();
    for (final NounPhrase phrase : nounPhrases) {
      boolean widest = true;
      for (final NounPhrase other : nounPhrases) {
        if (other != phrase && other.head() == phrase.head()
            && other.span().contains(phrase.span()) && !other.span().equals(phrase.span())) {
          widest = false;
          break;
        }
      }
      if (widest) {
        maximal.add(phrase);
      }
    }
    return maximal;
  }

  /**
   * Reconciles one noun phrase with intersecting entity candidates and adds a nominal
   * mention when no entity supplies it.
   *
   * @param phrase The noun phrase.
   * @param candidates The mention candidates.
   * @param entityCount The number of entity candidates at the start of the list.
   */
  private void addNounPhrase(NounPhrase phrase, List<Candidate> candidates,
      int entityCount) {
    final Span span = phrase.span();
    final int first = phrase.first();
    final int last = phrase.last();
    for (int c = entityCount; c < candidates.size(); c++) {
      if (candidates.get(c).span.equals(span)) {
        return;
      }
    }
    Candidate headedBy = null;
    int touching = 0;
    for (int e = 0; e < entityCount; e++) {
      final Candidate entity = candidates.get(e);
      if (entity.lastToken < first || entity.firstToken > last || entity.firstToken < 0) {
        continue;
      }
      touching++;
      if (entity.firstToken == first && entity.lastToken == last) {
        return;
      }
      if (entity.firstToken < first || entity.lastToken > last) {
        return;
      }
      if (entity.lastToken == phrase.head()) {
        headedBy = entity;
      }
    }
    // A phrase headed by an entity is that entity's full mention, unless the phrase
    // coordinates it with others, in which case the coordination is a mention of its own.
    if (headedBy != null && !(touching > 1 && coordinated(first, last))) {
      if (!phrase.merged()) {
        headedBy.span = span;
        headedBy.firstToken = first;
        headedBy.lastToken = last;
        headedBy.head = phrase.head();
      }
      return;
    }
    if (first == last && (CorefLexicon.pronoun(lower[first]) != null
        || CorefLexicon.demonstrative(lower[first]) != null)) {
      return;
    }
    if (!phrase.merged()) {
      addPossessors(first, last, candidates, entityCount);
    }
    if (!tag[phrase.head()].startsWith(NOUN_TAG_PREFIX)) {
      return;
    }
    final Candidate nominal = new Candidate(span, CorefMention.KIND_NOMINAL,
        CorefMention.NO_ENTITY, null, first, last);
    nominal.head = phrase.head();
    candidates.add(nominal);
  }

  /**
   * Adds the possessor inside a noun phrase as a mention of its own, {@code Norton 's}
   * in {@code Norton 's capital}: the tokens up to a possessive marker, widening an
   * entity that ends right before the marker to include it, as OntoNotes annotates.
   *
   * @param first The first token index of the noun phrase.
   * @param last The last token index of the noun phrase.
   * @param candidates The mention candidates.
   * @param entityCount The number of entity candidates at the start of the list.
   */
  private void addPossessors(int first, int last, List<Candidate> candidates,
      int entityCount) {
    for (int t = first + 1; t < last; t++) {
      if (!POSSESSIVE_TAG.equals(tag[t]) && !POSSESSIVE_MARKER.equals(lower[t])) {
        continue;
      }
      final int head = headToken(first, t - 1);
      boolean widened = false;
      for (int e = 0; e < entityCount; e++) {
        final Candidate entity = candidates.get(e);
        if (entity.lastToken == t - 1 && entity.firstToken >= first) {
          entity.lastToken = t;
          entity.span = new Span(entity.span.getStart(), tokens.get(t).span().getEnd());
          widened = true;
        } else if (entity.firstToken <= t && entity.lastToken >= t) {
          widened = true;
        }
      }
      if (widened || !tag[head].startsWith(NOUN_TAG_PREFIX)
          || CorefLexicon.pronoun(lower[head]) != null) {
        continue;
      }
      final Candidate possessor = new Candidate(
          new Span(tokens.get(first).span().getStart(), tokens.get(t).span().getEnd()),
          CorefMention.KIND_NOMINAL, CorefMention.NO_ENTITY, null, first, t);
      possessor.head = head;
      candidates.add(possessor);
    }
  }

  /**
   * Checks whether a token range contains a coordinating {@code and}.
   *
   * @param first The first token index.
   * @param last The last token index.
   * @return Whether the range is coordinated.
   */
  private boolean coordinated(int first, int last) {
    for (int t = first; t <= last; t++) {
      if (AND.equals(lower[t])) {
        return true;
      }
    }
    return false;
  }

  /**
   * Adds every personal pronoun token no entity covers as a pronoun mention, and every
   * demonstrative determiner that stands alone, {@code That was costly}, instead of
   * opening a noun phrase.
   *
   * @param candidates The mention candidates.
   * @param entityCount The number of entity candidates at the start of the list.
   */
  private void addPronouns(List<Candidate> candidates, int entityCount) {
    for (int t = 0; t < tokens.size(); t++) {
      if (PRONOUN_TAGS.contains(tag[t])) {
        if (CorefLexicon.pronoun(lower[t]) == null) {
          continue;
        }
      } else if (!standaloneDemonstrative(t)) {
        continue;
      }
      final Span span = tokens.get(t).span();
      if (coveredByEntity(span, candidates, entityCount) || pleonastic(t)) {
        continue;
      }
      candidates.add(new Candidate(span, CorefMention.KIND_PRONOUN,
          CorefMention.NO_ENTITY, null, t, t));
    }
  }

  /**
   * Checks whether a demonstrative determiner has no following noun phrase.
   *
   * @param t The token index.
   * @return Whether the token is a standalone demonstrative.
   */
  private boolean standaloneDemonstrative(int t) {
    if (!DETERMINER_TAG.equals(tag[t]) || CorefLexicon.demonstrative(lower[t]) == null) {
      return false;
    }
    if (t == sentenceEndToken[t]) {
      return true;
    }
    final String next = tag[t + 1];
    return !(next.startsWith(NOUN_TAG_PREFIX) || next.startsWith("JJ") || "CD".equals(next)
        || DETERMINER_TAG.equals(next) || "PRP$".equals(next));
  }

  /**
   * Checks whether an entity candidate covers a span.
   *
   * @param span The span.
   * @param candidates The candidates whose first entries are entities.
   * @param entityCount The number of entity candidates.
   * @return Whether an entity covers the span.
   */
  private boolean coveredByEntity(Span span, List<Candidate> candidates, int entityCount) {
    for (int e = 0; e < entityCount; e++) {
      if (candidates.get(e).span.contains(span)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Recognizes a pleonastic {@code it}: one followed, possibly after an adverb, by a
   * verb such as {@code is} or {@code seems} and within a few tokens by a complement
   * opener such as {@code that} or {@code to}, all inside the sentence.
   *
   * @param t The token index.
   * @return Whether the token is pleonastic.
   */
  private boolean pleonastic(int t) {
    if (!"it".equals(lower[t])) {
      return false;
    }
    final int end = sentenceEndToken[t];
    int verb = t + 1;
    if (verb <= end && tag[verb].startsWith("RB")) {
      verb++;
    }
    if (verb > end || !CorefLexicon.pleonasticVerb(lower[verb])) {
      return false;
    }
    for (int k = verb + 1; k <= end && k <= verb + PLEONASTIC_WINDOW; k++) {
      if (CorefLexicon.pleonasticComplement(lower[k])) {
        return true;
      }
    }
    return false;
  }

  /**
   * Computes a candidate's attributes.
   *
   * @param candidate The candidate.
   * @return The mention.
   */
  private Mention mention(Candidate candidate) {
    final int first = candidate.firstToken;
    final int last = candidate.lastToken;
    if (first < 0 || last < first) {
      return unaligned(candidate);
    }
    final List<String> words = new ArrayList<>(last - first + 1);
    for (int t = first; t <= last; t++) {
      words.add(lower[t]);
    }
    final int headToken = candidate.head >= 0 ? candidate.head : headToken(first, last);
    final String head = lower[headToken];
    final String headPrefix = join(words, 0, headToken - first + 1);
    final int end = POSSESSIVE_TAG.equals(tag[last]) || POSSESSIVE_MARKER.equals(lower[last])
        || "'".equals(lower[last]) ? words.size() - 1 : words.size();
    final String normalized = join(words, 0, Math.max(end, 1));
    final boolean pronoun = CorefMention.KIND_PRONOUN.equals(candidate.kind);
    final boolean entity = candidate.entity != CorefMention.NO_ENTITY;
    final boolean proper = entity || PROPER_TAGS.contains(tag[headToken]);
    // An unknown entity type supplies no type evidence, but its proper name can still
    // supply gender and animacy evidence.
    final String type = NameFinderAnnotator.UNTYPED.equals(candidate.type)
        ? null : candidate.type;
    final boolean person = type != null && personTypes.contains(type);

    Number number = Number.UNKNOWN;
    Gender gender = Gender.UNKNOWN;
    Animacy animacy = Animacy.UNKNOWN;
    Person grammaticalPerson = Person.THIRD;
    if (pronoun) {
      final Pronoun form = CorefLexicon.pronoun(head) != null
          ? CorefLexicon.pronoun(head) : CorefLexicon.demonstrative(head);
      number = form.number();
      gender = form.gender();
      animacy = form.animacy();
      grammaticalPerson = form.person();
    } else {
      if (coordinated(first, last)) {
        number = Number.PLURAL;
      } else if (type != null && ORGANIZATION_TYPES.contains(type)) {
        number = Number.UNKNOWN;
      } else if (PLURAL_TAGS.contains(tag[headToken])) {
        number = Number.PLURAL;
      } else if (tag[headToken].startsWith(NOUN_TAG_PREFIX)) {
        number = Number.SINGULAR;
      }
      if (person) {
        gender = nameGender(first, last);
        animacy = Animacy.ANIMATE;
      } else if (type != null) {
        gender = Gender.NEUTRAL;
        animacy = Animacy.INANIMATE;
      } else if (proper) {
        gender = nameGender(first, last);
        animacy = Animacy.ANIMATE;
      } else if (!entity) {
        gender = CorefLexicon.nounGender(head);
        if (CorefLexicon.animateNoun(head)) {
          animacy = Animacy.ANIMATE;
        }
      }
    }
    final boolean indefinite = !pronoun && !proper && indefinite(first, headToken);
    return new Mention(candidate.span, candidate.kind, candidate.entity, type,
        sentenceOfToken[first], first, last, List.copyOf(words), head, headPrefix,
        normalized, proper, indefinite, number, gender, animacy, grammaticalPerson);
  }

  /**
   * Builds the mention of an entity whose span aligns with no token, from the
   * whitespace-delimited words of its text; it has no tag evidence, so its attributes
   * beyond the entity type stay unknown.
   *
   * @param candidate The unaligned entity candidate.
   * @return The mention.
   */
  private Mention unaligned(Candidate candidate) {
    final Span span = candidate.span;
    final String normalized = StringUtil.toLowerCase(
        text.subSequence(span.getStart(), span.getEnd()).toString());
    final List<String> words = new ArrayList<>();
    int start = -1;
    for (int i = 0; i < normalized.length(); i++) {
      if (StringUtil.isWhitespace(normalized.charAt(i))) {
        if (start >= 0) {
          words.add(normalized.substring(start, i));
          start = -1;
        }
      } else if (start < 0) {
        start = i;
      }
    }
    if (start >= 0) {
      words.add(normalized.substring(start));
    }
    final int of = words.indexOf(OF);
    final int headIndex = of > 0 ? of - 1 : words.size() - 1;
    final String head = words.isEmpty() ? null : words.get(headIndex);
    final String type = NameFinderAnnotator.UNTYPED.equals(candidate.type)
        ? null : candidate.type;
    final boolean person = type != null && personTypes.contains(type);
    return new Mention(span, candidate.kind, candidate.entity, type, sentenceOf(span),
        -1, -1, List.copyOf(words), head, join(words, 0, headIndex + 1),
        join(words, 0, words.size()), true, false, Number.UNKNOWN,
        person ? Gender.UNKNOWN : type != null ? Gender.NEUTRAL : Gender.UNKNOWN,
        person ? Animacy.ANIMATE : type != null ? Animacy.INANIMATE : Animacy.UNKNOWN,
        Person.THIRD);
  }

  /**
   * Finds the head token of a token range: the last noun before the first {@code of}
   * or comma past the first token, leaving out a trailing possessive marker, and
   * falling back to the last token of that prefix.
   *
   * @param first The first token index.
   * @param last The last token index.
   * @return The head token index.
   */
  private int headToken(int first, int last) {
    int stop = last;
    for (int t = first + 1; t <= last; t++) {
      if (OF.equals(lower[t]) || COMMA.equals(lower[t])) {
        stop = t - 1;
        break;
      }
    }
    if (stop > first && (POSSESSIVE_TAG.equals(tag[stop]) || POSSESSIVE_MARKER.equals(lower[stop]))) {
      stop--;
    }
    for (int t = stop; t >= first; t--) {
      if (tag[t].startsWith(NOUN_TAG_PREFIX)) {
        return t;
      }
    }
    return stop;
  }

  /**
   * Reads the gender a person name implies from its title or first name.
   *
   * @param first The first token index.
   * @param last The last token index.
   * @return The inferred gender.
   */
  private Gender nameGender(int first, int last) {
    int t = first;
    if (CorefLexicon.title(lower[t])) {
      final Gender titled = CorefLexicon.nounGender(lower[t]);
      if (titled != Gender.UNKNOWN || t == last) {
        return titled;
      }
      t++;
    }
    return CorefLexicon.firstNameGender(lower[t]);
  }

  /**
   * Checks whether a nominal mention is indefinite or generic: opened by an indefinite
   * word, or a bare common noun, plural or singular, without a definite opener.
   *
   * @param first The first token index.
   * @param headToken The head token index.
   * @return Whether the mention is indefinite or generic.
   */
  private boolean indefinite(int first, int headToken) {
    if (CorefLexicon.indefiniteWord(lower[first])) {
      return true;
    }
    return tag[headToken].startsWith(NOUN_TAG_PREFIX)
        && !PROPER_TAGS.contains(tag[headToken])
        && !DEFINITE_OPENER_TAGS.contains(tag[first])
        && !"the".equals(lower[first]);
  }

  /**
   * Joins a word range with spaces.
   *
   * @param words The words.
   * @param from The first included index.
   * @param to The first excluded index.
   * @return The joined text.
   */
  private String join(List<String> words, int from, int to) {
    final StringBuilder joined = new StringBuilder();
    for (int i = from; i < to && i < words.size(); i++) {
      if (i > from) {
        joined.append(' ');
      }
      joined.append(words.get(i));
    }
    return joined.toString();
  }

  /**
   * Finds the first token intersecting a span.
   *
   * @param span The span.
   * @return The first intersecting token index, or {@code -1}.
   */
  private int firstToken(Span span) {
    int low = 0;
    int high = tokens.size();
    while (low < high) {
      final int mid = (low + high) >>> 1;
      if (tokens.get(mid).span().getEnd() <= span.getStart()) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low < tokens.size() && tokens.get(low).span().getStart() < span.getEnd()
        ? low : -1;
  }

  /**
   * Finds the last token intersecting a span.
   *
   * @param span The span.
   * @return The last intersecting token index, or {@code -1}.
   */
  private int lastToken(Span span) {
    int low = 0;
    int high = tokens.size();
    while (low < high) {
      final int mid = (low + high) >>> 1;
      if (tokens.get(mid).span().getStart() < span.getEnd()) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low > 0 && tokens.get(low - 1).span().getEnd() > span.getStart() ? low - 1 : -1;
  }

  /**
   * Finds the sentence containing a span's start.
   *
   * @param span The span.
   * @return The index of the sentence containing its start.
   */
  private int sentenceOf(Span span) {
    for (int s = 0; s < sentences.size(); s++) {
      if (span.getStart() < sentences.get(s).span().getEnd()) {
        return s;
      }
    }
    return sentences.size() - 1;
  }
}
