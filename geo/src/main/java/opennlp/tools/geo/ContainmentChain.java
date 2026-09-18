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

package opennlp.tools.geo;

import java.util.List;

/**
 * The containment chain of one resolved place: its enclosing places from the nearest
 * outward, so a neighborhood expands to its borough, city, region, and country in
 * order.
 *
 * @param ancestors The enclosing places, nearest first. Must not be {@code null} or
 *                  empty, and no element may be {@code null}.
 *
 * @since 3.0.0
 */
public record ContainmentChain(List<PlaceAncestor> ancestors) {

  /**
   * Validates the chain and makes an immutable copy of the ancestor list.
   *
   * @throws IllegalArgumentException Thrown if {@code ancestors} is {@code null},
   *         empty, or contains {@code null}.
   */
  public ContainmentChain {
    if (ancestors == null || ancestors.isEmpty()) {
      throw new IllegalArgumentException("ancestors must not be null or empty");
    }
    for (final PlaceAncestor ancestor : ancestors) {
      if (ancestor == null) {
        throw new IllegalArgumentException(
            "ancestors must not contain a null element, got: " + ancestors);
      }
    }
    ancestors = List.copyOf(ancestors);
  }
}
