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

import java.util.List;

import opennlp.tools.util.Span;

/**
 * One candidate mention with the attributes the sieves consult. Attribute values are
 * {@code UNKNOWN} when the text gives no evidence; an unknown attribute never blocks a
 * link.
 *
 * @param span The span the mention covers in the document text.
 * @param kind The mention kind, one of the {@link CorefMention} kind constants.
 * @param entity The index of the source entity in the entity layer, or
 *               {@link CorefMention#NO_ENTITY}.
 * @param type The lowercased entity type, or {@code null} for a mention without one.
 * @param sentence The index of the sentence the mention starts in.
 * @param firstToken The index of the mention's first token, or {@code -1} when the
 *                   mention aligns with no token.
 * @param lastToken The index of the mention's last token, or {@code -1}.
 * @param words The lowercased forms of the mention's tokens.
 * @param head The lowercased head word, or {@code null} for a blank mention.
 * @param headPrefix The lowercased text from the first word through the head word.
 * @param normalized The lowercased mention text without a trailing possessive marker.
 * @param proper Whether the mention is a proper name.
 * @param indefinite Whether the mention is indefinite or generic and so never anaphoric.
 * @param number The grammatical number.
 * @param gender The gender.
 * @param animacy The animacy.
 * @param person The grammatical person, known for pronouns only.
 */
record Mention(Span span, String kind, int entity, String type, int sentence,
    int firstToken, int lastToken, List<String> words, String head, String headPrefix,
    String normalized, boolean proper, boolean indefinite, Number number, Gender gender,
    Animacy animacy, Person person) {

  /** Grammatical number. */
  enum Number { SINGULAR, PLURAL, UNKNOWN }

  /** Gender, with {@code NEUTRAL} for things and organizations. */
  enum Gender { MALE, FEMALE, NEUTRAL, UNKNOWN }

  /** Animacy. */
  enum Animacy { ANIMATE, INANIMATE, UNKNOWN }

  /** Grammatical person. */
  enum Person { FIRST, SECOND, THIRD, UNKNOWN }

  /** {@return whether the mention is a pronoun} */
  boolean pronoun() {
    return CorefMention.KIND_PRONOUN.equals(kind);
  }

  /** {@return whether the mention is backed by an entity annotation} */
  boolean namedEntity() {
    return entity != CorefMention.NO_ENTITY;
  }

  /**
   * Checks whether one mention's span lies inside the other's, the i-within-i
   * configuration no sieve may link.
   *
   * @param other The other mention.
   * @return {@code true} if either span contains the other.
   */
  boolean nests(Mention other) {
    return span.contains(other.span) || other.span.contains(span);
  }
}
