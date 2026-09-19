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

import opennlp.tools.util.StringUtil;

/**
 * One mention in a coreference chain, including its kind and optional source entity.
 *
 * <p>Chain identifiers start at zero in first-mention order. Singleton chains preserve
 * mentions without a coreferent partner. The kind is an open string; the constants on
 * this record name the kinds produced by {@code CorefAnnotator}. An entity-backed
 * mention refers to its entity by layer index.</p>
 *
 * @param chain The chain identifier. Must not be negative.
 * @param kind The mention kind, for example {@link #KIND_ENTITY}. Must not be
 *             {@code null} or blank.
 * @param entity The index of the source entity in the entity layer, or
 *               {@link #NO_ENTITY} for mentions not backed by an entity.
 *
 * @since 3.0.0
 */
public record CorefMention(int chain, String kind, int entity) {

  /**
   * A mention lifted from the entity layer. When a chunk layer is present the mention
   * covers the noun phrase the entity heads, which may be wider than the entity span.
   */
  public static final String KIND_ENTITY = "entity";

  /** A noun phrase mention lifted from the chunk layer that no entity heads. */
  public static final String KIND_NOMINAL = "nominal";

  /** A pronoun mention. */
  public static final String KIND_PRONOUN = "pronoun";

  /** A mention read from a corpus annotation instead of detected from text. */
  public static final String KIND_GOLD = "gold";

  /** The entity index of a mention that is not backed by an entity. */
  public static final int NO_ENTITY = -1;

  /**
   * Validates the mention.
   *
   * @throws IllegalArgumentException Thrown if {@code chain} is negative, {@code kind}
   *         is {@code null} or blank, or {@code entity} is below {@link #NO_ENTITY}.
   */
  public CorefMention {
    if (chain < 0) {
      throw new IllegalArgumentException("chain must not be negative: " + chain);
    }
    if (kind == null || StringUtil.isBlank(kind)) {
      throw new IllegalArgumentException("kind must not be null or blank");
    }
    if (entity < NO_ENTITY) {
      throw new IllegalArgumentException("entity must be an index or NO_ENTITY: " + entity);
    }
  }
}
