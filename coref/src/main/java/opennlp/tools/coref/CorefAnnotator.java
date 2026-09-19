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

import opennlp.tools.chunker.ChunkerAnnotator;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;
import opennlp.tools.parser.ParserAnnotator;
import opennlp.tools.parser.ParserAnnotator.Phrase;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

/**
 * Resolves entity, noun phrase, and pronoun mentions into the {@link #CHAINS} document
 * layer. Each annotation contains the original-text span and a {@link CorefMention}.
 *
 * <p>Without a model, the annotator applies the entity-centric sieves of
 * <a href="https://aclanthology.org/J13-4004/">Lee et al. (Computational Linguistics
 * 2013), "Deterministic Coreference Resolution Based on Entity-Centric, Precision-Ranked
 * Rules"</a>. A {@link CorefModel} replaces the head and pronoun sieves with antecedent
 * ranking. Optional {@link WordVectors} and {@link TokenVectors} providers add similarity
 * features to that ranker.</p>
 *
 * <p>First and second person pronouns use the optional {@link #SPEAKERS} layer or quoted
 * speech attribution. All detected mentions are returned, including singleton chains,
 * and chain identifiers follow first-mention order.</p>
 *
 * <p>Without vector providers, the annotator stores no per-call state and is safe to share
 * between threads. With word or token vectors, thread safety follows those providers.</p>
 *
 * @since 3.0.0
 */
public class CorefAnnotator implements DocumentAnnotator {

  /**
   * Coreference mentions; each annotation covers one mention and carries its
   * {@link CorefMention}, ordered by text position.
   */
  public static final LayerKey<CorefMention> CHAINS =
      Layers.key("chains", CorefMention.class);

  /**
   * Hand-annotated coreference chains, the gold counterpart of {@link #CHAINS} under
   * the {@code gold:} convention; {@link CorefTrainer} reads it and the annotator never
   * does.
   */
  public static final LayerKey<CorefMention> GOLD_CHAINS =
      LayerKey.of("gold:" + CHAINS.id(), CorefMention.class);

  /**
   * Speakers, an optional input layer: each annotation covers the text one speaker
   * utters, typically a sentence or a turn, and carries the speaker's label. First and
   * second person pronouns resolve per speaker; without the layer, quotation marks
   * delimit the speakers instead.
   */
  public static final LayerKey<String> SPEAKERS = Layers.key("speakers", String.class);

  /** The message prefix of every absent-required-layer rejection in this annotator. */
  private static final String MISSING_LAYER = "document lacks the required layer ";

  /** Lowercased entity type labels gendered pronouns may resolve to. */
  private final Set<String> personTypes;

  /** Lowercased entity type labels neutral pronouns may resolve to. */
  private final Set<String> neutralTypes;

  /** The ranking model, or {@code null} for rule-based resolution. */
  private final CorefModel model;

  /** The least link probability at which the ranker links a pair. */
  private final double threshold;

  /** Word vectors for the ranker's similarity features, or {@code null}. */
  private final WordVectors vectors;

  /** The contextual token encoder for the ranker's span features, or {@code null}. */
  private final TokenVectors encoder;

  /**
   * The link probability threshold of the model constructors. Pair classifiers see far
   * more unlinked than linked pairs, so their link probabilities run low. This value was
   * selected on the OntoGUM development split for models {@link CorefTrainer} produces.
   */
  public static final double DEFAULT_THRESHOLD = 0.1;

  /**
   * Initializes the rule-based annotator for the conventional entity type names:
   * {@code person} for gendered pronouns and {@code organization} and {@code location}
   * for neutral pronouns.
   */
  public CorefAnnotator() {
    this(Set.of("person"), Set.of("organization", "location"));
  }

  /**
   * Initializes the rule-based annotator.
   *
   * @param personTypes The entity type labels gendered pronouns may resolve to, matched
   *                    case-insensitively. Must not be {@code null} or empty.
   * @param neutralTypes The entity type labels neutral pronouns may resolve to, matched
   *                     case-insensitively. Must not be {@code null} or empty.
   * @throws IllegalArgumentException Thrown if a set is {@code null}, empty, or
   *         contains a blank entry.
   */
  public CorefAnnotator(Set<String> personTypes, Set<String> neutralTypes) {
    this(personTypes, neutralTypes, null, DEFAULT_THRESHOLD);
  }

  /**
   * Initializes a ranking annotator for the conventional entity type names.
   *
   * @param model The ranking model. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code model} is {@code null}.
   */
  public CorefAnnotator(CorefModel model) {
    this(Set.of("person"), Set.of("organization", "location"), requireModel(model),
        DEFAULT_THRESHOLD);
  }

  /**
   * Initializes a ranking annotator.
   *
   * @param personTypes The entity type labels gendered pronouns may resolve to, matched
   *                    case-insensitively. Must not be {@code null} or empty.
   * @param neutralTypes The entity type labels neutral pronouns may resolve to, matched
   *                     case-insensitively. Must not be {@code null} or empty.
   * @param model The ranking model. Must not be {@code null}.
   * @param threshold The least link probability at which a pair is linked, in
   *                  {@code [0, 1]}.
   * @throws IllegalArgumentException Thrown if a set is {@code null}, empty, or
   *         contains a blank entry, {@code model} is {@code null}, or
   *         {@code threshold} lies outside {@code [0, 1]}.
   */
  public CorefAnnotator(Set<String> personTypes, Set<String> neutralTypes, CorefModel model,
      double threshold) {
    this(personTypes, neutralTypes, model, threshold, null);
  }

  /**
   * Initializes an annotator with word vectors for the ranker's head similarity
   * features. The vectors must be the ones the model was trained with; without a model
   * they are unused.
   *
   * @param personTypes The entity type labels gendered pronouns may resolve to, matched
   *                    case-insensitively. Must not be {@code null} or empty.
   * @param neutralTypes The entity type labels neutral pronouns may resolve to, matched
   *                     case-insensitively. Must not be {@code null} or empty.
   * @param model The ranking model, or {@code null} for rule-based resolution.
   * @param threshold The least link probability at which a pair classifier links, in
   *                  {@code [0, 1]}.
   * @param vectors The word vectors, or {@code null} for none.
   * @throws IllegalArgumentException Thrown if a set is {@code null}, empty, or
   *         contains a blank entry, {@code threshold} lies outside {@code [0, 1]}, or
   *         the vector dimension is invalid.
   */
  public CorefAnnotator(Set<String> personTypes, Set<String> neutralTypes, CorefModel model,
      double threshold, WordVectors vectors) {
    this(personTypes, neutralTypes, model, threshold, vectors, null);
  }

  /**
   * Initializes the annotator with a model, word vectors, and a contextual token
   * encoder. The encoder runs once per sentence when a document is annotated or read
   * for training; a model trained with an encoder must be decoded with the same one.
   *
   * @param personTypes The entity types gendered pronouns may resolve to.
   * @param neutralTypes The entity types neutral pronouns may resolve to.
   * @param model The ranking model, or {@code null} for rule-based resolution.
   * @param threshold The least link probability at which a pair classifier links, in
   *                  {@code [0, 1]}.
   * @param vectors The word vectors, or {@code null} for none.
   * @param encoder The token encoder, or {@code null} for none.
   * @throws IllegalArgumentException Thrown if a set is {@code null}, empty, or
   *         contains a blank entry, {@code threshold} lies outside {@code [0, 1]}, a
   *         vector dimension is invalid, or the encoder does not match the model.
   */
  public CorefAnnotator(Set<String> personTypes, Set<String> neutralTypes, CorefModel model,
      double threshold, WordVectors vectors, TokenVectors encoder) {
    this.personTypes = lowered(personTypes, "personTypes");
    this.neutralTypes = lowered(neutralTypes, "neutralTypes");
    this.model = model;
    if (!(threshold >= 0.0 && threshold <= 1.0)) {
      throw new IllegalArgumentException("threshold must lie in [0, 1]: " + threshold);
    }
    this.threshold = threshold;
    this.vectors = vectors;
    this.encoder = encoder;
    validateVectorDimensions();
  }

  /**
   * Validates provider dimensions and the model's contextual-vector requirement.
   *
   * @throws IllegalArgumentException Thrown if a provider dimension is invalid or the
   *         encoder does not match the model.
   */
  private void validateVectorDimensions() {
    if (vectors != null && vectors.dimension() <= 0) {
      throw new IllegalArgumentException(
          "vectors dimension must be positive: " + vectors.dimension());
    }
    if (encoder != null && encoder.dimension() <= 0) {
      throw new IllegalArgumentException(
          "encoder dimension must be positive: " + encoder.dimension());
    }
    if (model == null) {
      return;
    }
    final int expected = model.getTokenVectorDimension();
    if (expected == 0 && encoder != null) {
      throw new IllegalArgumentException("model was trained without a token encoder");
    }
    if (expected > 0 && encoder == null) {
      throw new IllegalArgumentException(
          "model requires a token encoder of dimension " + expected);
    }
    if (encoder != null && encoder.dimension() != expected) {
      throw new IllegalArgumentException("encoder dimension " + encoder.dimension()
          + " does not match model dimension " + expected);
    }
  }

  /**
   * Validates the model-only constructor argument.
   *
   * @param model The model.
   * @return The validated model.
   * @throws IllegalArgumentException Thrown if {@code model} is {@code null}.
   */
  private static CorefModel requireModel(CorefModel model) {
    if (model == null) {
      throw new IllegalArgumentException("model must not be null");
    }
    return model;
  }

  /**
   * Validates and lowercases one type set.
   *
   * @param types The type labels.
   * @param name The parameter name for error messages.
   * @return The lowercased set. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code types} is {@code null}, empty, or
   *         contains a {@code null} or blank entry.
   */
  private Set<String> lowered(Set<String> types, String name) {
    if (types == null || types.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be null or empty");
    }
    final Set<String> lowered = new HashSet<>(types.size());
    for (final String type : types) {
      if (type == null || StringUtil.isBlank(type)) {
        throw new IllegalArgumentException(name + " must not contain blank entries");
      }
      lowered.add(StringUtil.toLowerCase(type));
    }
    return Set.copyOf(lowered);
  }

  /**
   * Resolves coreference over the document and adds the {@link #CHAINS} layer.
   *
   * <p>The required layers must be present, but they may be empty. The token and POS tag
   * layers must have the same size and matching spans. Sentence and token spans must be
   * in text order and must not overlap, and each token and entity mention must fall
   * within one sentence. Chunk and phrase spans must align with tokens and remain in one
   * sentence; phrase heads must align with one token. Speaker spans must be ordered,
   * disjoint, and carry non-blank labels. A document without tokens then yields a
   * present-but-empty chains layer; a document that has tokens needs a non-empty
   * sentence layer. {@link ParserAnnotator#PHRASES} and
   * {@link ChunkerAnnotator#CHUNKS} layers are optional. Noun phrases from every present
   * layer become mentions; an exact duplicate keeps the parser head. Without either
   * layer, only entities and pronouns do. A {@link #SPEAKERS} layer is optional as
   * well.</p>
   *
   * @param document The document to annotate. Must not be {@code null} and must carry the
   *                 {@link Layers#SENTENCES}, {@link Layers#TOKENS},
   *                 {@link Layers#POS_TAGS}, and {@link Layers#ENTITIES} layers.
   * @return A new {@link Document} carrying the {@link #CHAINS} layer. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code document} is {@code null}, a
   *         required layer is absent, the token and POS tag layers do not align, token
   *         sentence, token, or speaker spans overlap or are out of order, an input span
   *         is malformed, or tokens are present but the sentence layer is empty.
   * @throws IllegalStateException Thrown if a vector provider violates its return
   *         contract.
   */
  @Override
  public Document annotate(Document document) {
    final SieveResolver resolver = resolver(document);
    if (resolver == null) {
      return document.with(CHAINS, List.of());
    }
    if (model == null) {
      resolver.resolve();
    } else {
      resolver.resolve(model.getPairModel(), model.isRanking(), threshold);
    }
    final List<Mention> mentions = resolver.mentions();
    final Clusters clusters = resolver.clusters();
    final Map<Integer, Integer> chainIds = new HashMap<>();
    final List<Annotation<CorefMention>> layer = new ArrayList<>(mentions.size());
    for (int i = 0; i < mentions.size(); i++) {
      final int root = clusters.find(i);
      final int chain = chainIds.computeIfAbsent(root, key -> chainIds.size());
      final Mention mention = mentions.get(i);
      layer.add(new Annotation<>(mention.span(),
          new CorefMention(chain, mention.kind(), mention.entity())));
    }
    return document.with(CHAINS, layer);
  }

  /**
   * Validates a document, detects its mentions, and builds the resolver over them with
   * every cluster still a singleton.
   *
   * @param document The document to annotate.
   * @return The resolver, or {@code null} for a document without tokens, whose chains
   *         layer is empty.
   * @throws IllegalArgumentException Thrown if the document is invalid, as
   *         {@link #annotate(Document)} documents.
   * @throws IllegalStateException Thrown if the token encoder violates the
   *         {@link TokenVectors} return contract.
   */
  SieveResolver resolver(Document document) {
    if (document == null) {
      throw new IllegalArgumentException("document must not be null");
    }
    final Set<LayerKey<?>> present = document.layers();
    if (!present.contains(Layers.SENTENCES)) {
      throw new IllegalArgumentException(MISSING_LAYER + Layers.SENTENCES);
    }
    if (!present.contains(Layers.TOKENS)) {
      throw new IllegalArgumentException(MISSING_LAYER + Layers.TOKENS);
    }
    if (!present.contains(Layers.POS_TAGS)) {
      throw new IllegalArgumentException(MISSING_LAYER + Layers.POS_TAGS);
    }
    if (!present.contains(Layers.ENTITIES)) {
      throw new IllegalArgumentException(MISSING_LAYER + Layers.ENTITIES);
    }
    final List<Annotation<String>> sentences = document.get(Layers.SENTENCES);
    final List<Annotation<String>> tokens = document.get(Layers.TOKENS);
    final List<Annotation<String>> tags = document.get(Layers.POS_TAGS);
    final List<Annotation<String>> entities = document.get(Layers.ENTITIES);
    if (tags.size() != tokens.size()) {
      throw new IllegalArgumentException("document needs aligned "
          + Layers.TOKENS + " and " + Layers.POS_TAGS + " layers");
    }
    for (int s = 1; s < sentences.size(); s++) {
      if (sentences.get(s - 1).span().getEnd() > sentences.get(s).span().getStart()) {
        throw new IllegalArgumentException(
            "sentence spans overlap or are out of order at index " + s);
      }
    }
    for (int e = 0; e < entities.size(); e++) {
      final Annotation<String> annotation = entities.get(e);
      final Span entity = annotation.span();
      if (entity.length() == 0
          || StringUtil.isBlank(document.text().subSequence(
              entity.getStart(), entity.getEnd()))) {
        throw new IllegalArgumentException(
            "entity at index " + e + " must cover non-blank text");
      }
      if (StringUtil.isBlank(annotation.value())) {
        throw new IllegalArgumentException(
            "entity at index " + e + " must have a non-blank type");
      }
      boolean contained = false;
      for (final Annotation<String> sentence : sentences) {
        if (sentence.span().contains(entity)) {
          contained = true;
          break;
        }
      }
      if (!contained) {
        throw new IllegalArgumentException(
            "entity at index " + e + " falls outside the sentence layer");
      }
    }
    final List<Annotation<String>> chunks = present.contains(ChunkerAnnotator.CHUNKS)
        ? document.get(ChunkerAnnotator.CHUNKS) : null;
    final List<Annotation<Phrase>> phrases = present.contains(ParserAnnotator.PHRASES)
        ? document.get(ParserAnnotator.PHRASES) : null;
    final List<Annotation<String>> speakers = present.contains(SPEAKERS)
        ? document.get(SPEAKERS) : null;
    if (tokens.isEmpty()) {
      validateChunks(chunks, Map.of(), Map.of(), new int[0]);
      validatePhrases(phrases, Map.of(), Map.of(), new int[0]);
      validateSpeakers(speakers);
      return null;
    }
    if (sentences.isEmpty()) {
      throw new IllegalArgumentException(
          "document needs a non-empty " + Layers.SENTENCES + " layer");
    }
    final String[] forms = new String[tokens.size()];
    final int[] sentenceOfToken = new int[tokens.size()];
    final Map<Integer, Integer> tokenStarts = new HashMap<>(tokens.size());
    final Map<Integer, Integer> tokenEnds = new HashMap<>(tokens.size());
    for (int t = 0, sentence = 0; t < forms.length; t++) {
      final Annotation<String> token = tokens.get(t);
      final Span tokenSpan = token.span();
      final Span tagSpan = tags.get(t).span();
      if (tokenSpan.getStart() != tagSpan.getStart()
          || tokenSpan.getEnd() != tagSpan.getEnd()) {
        throw new IllegalArgumentException("token and POS tag spans differ at index " + t);
      }
      if (t > 0 && tokens.get(t - 1).span().getEnd() > tokenSpan.getStart()) {
        throw new IllegalArgumentException(
            "token spans overlap or are out of order at index " + t);
      }
      forms[t] = token.value();
      while (sentence < sentences.size() - 1
          && tokenSpan.getStart() >= sentences.get(sentence).span().getEnd()) {
        sentence++;
      }
      if (!sentences.get(sentence).span().contains(tokenSpan)) {
        throw new IllegalArgumentException("token at index " + t
            + " falls outside the sentence layer");
      }
      sentenceOfToken[t] = sentence;
      tokenStarts.put(tokenSpan.getStart(), t);
      tokenEnds.put(tokenSpan.getEnd(), t);
    }
    validateChunks(chunks, tokenStarts, tokenEnds, sentenceOfToken);
    validatePhrases(phrases, tokenStarts, tokenEnds, sentenceOfToken);
    validateSpeakers(speakers);
    final List<Mention> mentions = MentionDetector.detect(personTypes, document.text(),
        sentences, tokens, tags, sentenceOfToken, entities, chunks, phrases);
    return new SieveResolver(mentions, new Clusters(mentions), forms, sentenceOfToken,
        speakers, personTypes, neutralTypes, vectors,
        encoder == null ? null : encode(forms, sentenceOfToken));
  }

  /** Validates the optional chunk layer consumed by mention detection. */
  private void validateChunks(List<Annotation<String>> chunks,
      Map<Integer, Integer> tokenStarts, Map<Integer, Integer> tokenEnds,
      int[] sentenceOfToken) {
    if (chunks == null) {
      return;
    }
    for (int c = 0; c < chunks.size(); c++) {
      final Annotation<String> chunk = chunks.get(c);
      if (StringUtil.isBlank(chunk.value())) {
        throw new IllegalArgumentException(
            "chunk at index " + c + " must have a non-blank type");
      }
      validateTokenSpan("chunk", c, chunk.span(), tokenStarts, tokenEnds,
          sentenceOfToken);
      if (c > 0 && chunks.get(c - 1).span().getEnd() > chunk.span().getStart()) {
        throw new IllegalArgumentException(
            "chunk spans overlap or are out of order at index " + c);
      }
    }
  }

  /** Validates the optional parser phrase layer consumed by mention detection. */
  private void validatePhrases(List<Annotation<Phrase>> phrases,
      Map<Integer, Integer> tokenStarts, Map<Integer, Integer> tokenEnds,
      int[] sentenceOfToken) {
    if (phrases == null) {
      return;
    }
    for (int p = 0; p < phrases.size(); p++) {
      final Annotation<Phrase> phrase = phrases.get(p);
      validateTokenSpan("phrase", p, phrase.span(), tokenStarts, tokenEnds,
          sentenceOfToken);
      final Span head = phrase.value().head();
      final Integer headStart = tokenStarts.get(head.getStart());
      final Integer headEnd = tokenEnds.get(head.getEnd());
      if (headStart == null || !headStart.equals(headEnd)
          || !phrase.span().contains(head)) {
        throw new IllegalArgumentException(
            "phrase at index " + p + " must name one of its tokens as its head");
      }
    }
  }

  /** Validates an annotation span that must cover complete tokens in one sentence. */
  private void validateTokenSpan(String layer, int index, Span span,
      Map<Integer, Integer> tokenStarts, Map<Integer, Integer> tokenEnds,
      int[] sentenceOfToken) {
    final Integer first = tokenStarts.get(span.getStart());
    final Integer last = tokenEnds.get(span.getEnd());
    if (first == null || last == null || first > last) {
      throw new IllegalArgumentException(
          layer + " at index " + index + " must align with token boundaries");
    }
    if (sentenceOfToken[first] != sentenceOfToken[last]) {
      throw new IllegalArgumentException(
          layer + " at index " + index + " crosses a sentence boundary");
    }
  }

  /** Validates the optional speaker turns used by the speaker sieve. */
  private void validateSpeakers(List<Annotation<String>> speakers) {
    if (speakers == null) {
      return;
    }
    for (int s = 0; s < speakers.size(); s++) {
      final Annotation<String> speaker = speakers.get(s);
      if (speaker.span().length() == 0 || StringUtil.isBlank(speaker.value())) {
        throw new IllegalArgumentException(
            "speaker at index " + s + " must cover text and have a non-blank label");
      }
      if (s > 0 && speakers.get(s - 1).span().getEnd() > speaker.span().getStart()) {
        throw new IllegalArgumentException(
            "speaker spans overlap or are out of order at index " + s);
      }
    }
  }

  /**
   * Runs the encoder for each sentence and aligns the vectors with the token array.
   *
   * @param forms The token forms.
   * @param sentenceOfToken The sentence index of each token.
   * @return One vector per token.
   * @throws IllegalStateException Thrown if the encoder violates its return contract.
   */
  private float[][] encode(String[] forms, int[] sentenceOfToken) {
    final float[][] tokenVectors = new float[forms.length][];
    int start = 0;
    final int dimension = encoder.dimension();
    while (start < forms.length) {
      int end = start + 1;
      while (end < forms.length && sentenceOfToken[end] == sentenceOfToken[start]) {
        end++;
      }
      final String[] sentence = new String[end - start];
      System.arraycopy(forms, start, sentence, 0, sentence.length);
      final float[][] vectors = encoder.vectors(sentence);
      if (vectors == null || vectors.length != sentence.length) {
        throw new IllegalStateException("token encoder returned "
            + (vectors == null ? "null" : vectors.length + " vectors") + " for "
            + sentence.length + " tokens");
      }
      for (int t = 0; t < vectors.length; t++) {
        if (vectors[t] == null || vectors[t].length == 0) {
          throw new IllegalStateException("token encoder returned a null or empty vector"
              + " for token " + (start + t));
        }
        if (vectors[t].length != dimension) {
          throw new IllegalStateException("token encoder returned dimension "
              + vectors[t].length + " for token " + (start + t)
              + ", expected " + dimension);
        }
        for (int d = 0; d < vectors[t].length; d++) {
          if (!Float.isFinite(vectors[t][d])) {
            throw new IllegalStateException("token encoder returned a non-finite value"
                + " for token " + (start + t) + " at dimension " + d);
          }
        }
      }
      System.arraycopy(vectors, 0, tokenVectors, start, sentence.length);
      start = end;
    }
    return tokenVectors;
  }

  /**
   * Returns the contextual token-vector dimension used by this configuration.
   *
   * @return The provider dimension, or zero without a token encoder.
   */
  int tokenVectorDimension() {
    return encoder == null ? 0 : encoder.dimension();
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> requires() {
    return Set.of(Layers.SENTENCES, Layers.TOKENS, Layers.POS_TAGS, Layers.ENTITIES);
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(CHAINS);
  }
}
