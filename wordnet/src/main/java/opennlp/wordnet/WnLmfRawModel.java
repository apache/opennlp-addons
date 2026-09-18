/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package opennlp.wordnet;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;

import opennlp.tools.wordnet.WordNetPOS;

/**
 * Immutable XML records used by {@link WnLmfReader} while composing lexicon extensions.
 * Entry and sense identifiers let external declarations refer to base content.
 *
 * <p>Collections are copied, and lexical maps retain document order.</p>
 */
final class WnLmfRawModel {

  /** Not instantiable. */
  private WnLmfRawModel() {
  }

  /** The two top-level lexicon forms of a WN-LMF lexical resource. */
  enum Kind {

    /** An ordinary, self-contained {@code Lexicon}. */
    LEXICON,

    /** A {@code LexiconExtension}, additive over a separately supplied base. */
    EXTENSION
  }

  /**
   * A parsed document with lexicons and extensions in document order.
   *
   * @param lexicons The raw lexicons in document order.
   */
  record RawResource(List<RawLexicon> lexicons) {

    /** Copies the lexicon list. */
    RawResource {
      lexicons = List.copyOf(lexicons);
    }
  }

  /**
   * A parsed or composed lexicon. Composition resolves and removes external declarations.
   *
   * @param kind             Whether the source element was a Lexicon or a LexiconExtension.
   * @param id               The lexicon id.
   * @param label            The human-readable label.
   * @param language         The BCP 47 language tag.
   * @param version          The version string.
   * @param metadata         The remaining identity attributes, namespace aware.
   * @param requires         The {@code Requires} declarations in source order.
   * @param extendsRef       The {@code Extends} reference, or {@code null} for a Lexicon.
   * @param entries          The lexical entries by id, in document order.
   * @param senses           The senses by id, in document order; for a composed lexicon, base
   *                         senses first, extension senses after in extension source order.
   * @param synsets          The synsets by id, in document order.
   * @param externalEntries  The {@code ExternalLexicalEntry} declarations in document order.
   * @param externalSynsets  The {@code ExternalSynset} declarations in document order.
   * @param line             The line the element started on, or {@code -1} when unknown.
   */
  record RawLexicon(
      Kind kind,
      String id,
      String label,
      String language,
      String version,
      Map<QName, String> metadata,
      List<WnLmfDependency> requires,
      WnLmfDependency extendsRef,
      Map<String, RawEntry> entries,
      Map<String, RawSense> senses,
      Map<String, RawSynset> synsets,
      List<RawExternalEntry> externalEntries,
      List<RawExternalSynset> externalSynsets,
      int line) {

    /** Copies collections and retains lexical map order. */
    RawLexicon {
      metadata = Map.copyOf(metadata);
      requires = List.copyOf(requires);
      entries = ordered(entries);
      senses = ordered(senses);
      synsets = ordered(synsets);
      externalEntries = List.copyOf(externalEntries);
      externalSynsets = List.copyOf(externalSynsets);
    }
  }

  /**
   * A lexical entry and the lemma used for lookup.
   *
   * @param id    The entry id.
   * @param lemma The written form of the entry's lemma.
   * @param pos   The entry's part of speech.
   * @param line  The line the element started on.
   */
  record RawEntry(String id, String lemma, WordNetPOS pos, int line) {
  }

  /**
   * One parsed {@code Sense}, owned by the entry that declared it.
   *
   * @param id        The sense id.
   * @param entryId   The owning lexical entry's id.
   * @param synsetId  The target synset id.
   * @param relations The sense relations in source order; for a composed lexicon, base
   *                  relations first, extension additions after.
   * @param line      The line the element started on.
   */
  record RawSense(String id, String entryId, String synsetId,
                  List<RawSenseRelation> relations, int line) {

    /** Copies the relation list. */
    RawSense {
      relations = List.copyOf(relations);
    }
  }

  /**
   * One parsed {@code Synset}.
   *
   * @param id           The synset id.
   * @param pos          The part of speech.
   * @param members      The {@code members} attribute value, or {@code null} when absent.
   * @param extraMembers Entry ids composition appended after the base members, empty outside
   *                     composed lexicons.
   * @param gloss        The definition text, or {@code null} if no definition exists.
   * @param relations    The synset relations in source order; for a composed lexicon, base
   *                     relations first, extension additions after.
   * @param line         The line the element started on.
   */
  record RawSynset(String id, WordNetPOS pos, String members, List<String> extraMembers,
                   String gloss, List<RawRelation> relations, int line) {

    /** Copies the added members and relations. */
    RawSynset {
      extraMembers = List.copyOf(extraMembers);
      relations = List.copyOf(relations);
    }
  }

  /**
   * One parsed {@code SynsetRelation}, kept by name until the target synset is known.
   *
   * @param relType The WN-LMF relation name.
   * @param target  The target synset id.
   * @param line    The line the element started on.
   */
  record RawRelation(String relType, String target, int line) {
  }

  /**
   * One parsed {@code SenseRelation}, kept by name until both sense ids are known.
   *
   * @param relType The WN-LMF relation name.
   * @param target  The target sense id.
   * @param line    The line the element started on.
   */
  record RawSenseRelation(String relType, String target, int line) {
  }

  /**
   * A base entry reference with external senses. New senses are stored in the extension's
   * sense table, owned by this entry identifier.
   *
   * @param id             The base lexical entry id this declaration targets.
   * @param externalSenses The nested {@code ExternalSense} declarations in source order.
   * @param line           The line the element started on.
   */
  record RawExternalEntry(String id, List<RawExternalSense> externalSenses, int line) {

    /** Copies the external sense list. */
    RawExternalEntry {
      externalSenses = List.copyOf(externalSenses);
    }
  }

  /**
   * One parsed {@code ExternalSense}: a reference to a base sense plus added relations.
   *
   * @param id        The base sense id this declaration targets.
   * @param relations The added sense relations in source order.
   * @param line      The line the element started on.
   */
  record RawExternalSense(String id, List<RawSenseRelation> relations, int line) {

    /** Copies the added relations. */
    RawExternalSense {
      relations = List.copyOf(relations);
    }
  }

  /**
   * One parsed {@code ExternalSynset}: a reference to a base synset plus added content.
   *
   * @param id         The base synset id this declaration targets.
   * @param definition The first nested definition, or {@code null} when there is none.
   * @param relations  The added synset relations in source order.
   * @param line       The line the element started on.
   */
  record RawExternalSynset(String id, String definition, List<RawRelation> relations, int line) {

    /** Copies the added relations. */
    RawExternalSynset {
      relations = List.copyOf(relations);
    }
  }

  /**
   * Copies a map into an unmodifiable view that keeps the source's iteration order, which
   * {@link Map#copyOf(Map)} does not.
   *
   * @param source The insertion-ordered source map.
   * @param <V>    The value type.
   * @return An unmodifiable copy in the source's iteration order.
   */
  private static <V> Map<String, V> ordered(Map<String, V> source) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }
}
