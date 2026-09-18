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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests ancestor order, invalid input and defensive copying. */
public class ContainmentChainTest {

  private static final PlaceAncestor BROOKLYN =
      new PlaceAncestor("421205765", "Brooklyn", "borough");

  private static final PlaceAncestor NEW_YORK =
      new PlaceAncestor("85977539", "New York", "locality");

  /** The supplied nearest-first order is retained. */
  @Test
  void testHoldsAncestorsNearestFirst() {
    final ContainmentChain chain = new ContainmentChain(List.of(BROOKLYN, NEW_YORK));
    assertEquals(List.of(BROOKLYN, NEW_YORK), chain.ancestors());
  }

  /** Null lists are rejected. */
  @Test
  void testRejectsNullAncestors() {
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> new ContainmentChain(null));
    assertTrue(e.getMessage().startsWith("ancestors must not be null or empty"), e.getMessage());
  }

  /** Empty lists cannot represent a containment chain. */
  @Test
  void testRejectsEmptyAncestors() {
    assertThrows(IllegalArgumentException.class, () -> new ContainmentChain(List.of()));
  }

  /** Null elements produce the documented argument error. */
  @Test
  void testRejectsNullAncestorElement() {
    final List<PlaceAncestor> withNull = Arrays.asList(BROOKLYN, null);
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> new ContainmentChain(withNull));
    assertTrue(e.getMessage().startsWith("ancestors must not contain a null element"),
        e.getMessage());
  }

  /** Later list edits cannot affect the immutable chain. */
  @Test
  void testCopiesTheSuppliedListToAnImmutableView() {
    final List<PlaceAncestor> supplied = new ArrayList<>(List.of(BROOKLYN));
    final ContainmentChain chain = new ContainmentChain(supplied);
    supplied.add(NEW_YORK);
    assertEquals(List.of(BROOKLYN), chain.ancestors());
    assertThrows(UnsupportedOperationException.class, () -> chain.ancestors().add(NEW_YORK));
  }
}
