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

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import opennlp.tools.coref.Mention.Animacy;
import opennlp.tools.coref.Mention.Gender;
import opennlp.tools.coref.Mention.Number;
import opennlp.tools.coref.Mention.Person;
import opennlp.tools.namefind.NameFinderAnnotator;

/**
 * A union-find forest over mention indices that carries each cluster's accumulated
 * attributes, so the sieves compare entities instead of individual mentions.
 *
 * <p>A cluster's attribute sets hold the known values of its members; an unknown value
 * contributes nothing. Two clusters agree on an attribute when either side knows
 * nothing or they share a value. The entity type is the one known type of any member;
 * mentions of the unknown type {@link NameFinderAnnotator#UNTYPED}, and mentions without
 * a type, join a cluster of any type and adopt it, but two clusters whose known types
 * differ never merge. The earliest mention of a cluster is its root, so a mention is the
 * first of its cluster exactly when it is its own root.</p>
 */
final class Clusters {

  private final int[] parent;
  private final String[] type;
  private final List<Set<Number>> numbers;
  private final List<Set<Gender>> genders;
  private final List<Set<Animacy>> animacies;
  private final List<Set<Person>> persons;
  private final List<Set<String>> words;
  private final List<Set<String>> heads;
  private final List<Set<String>> normalizedForms;
  private final List<List<Integer>> members;
  private final int[] size;

  /**
   * Initializes one singleton cluster per mention.
   *
   * @param mentions The mentions in text order.
   */
  Clusters(List<Mention> mentions) {
    final int size = mentions.size();
    parent = new int[size];
    type = new String[size];
    numbers = new java.util.ArrayList<>(size);
    genders = new java.util.ArrayList<>(size);
    animacies = new java.util.ArrayList<>(size);
    persons = new java.util.ArrayList<>(size);
    words = new java.util.ArrayList<>(size);
    heads = new java.util.ArrayList<>(size);
    normalizedForms = new java.util.ArrayList<>(size);
    members = new java.util.ArrayList<>(size);
    this.size = new int[size];
    for (int i = 0; i < size; i++) {
      final Mention mention = mentions.get(i);
      parent[i] = i;
      final List<Integer> own = new java.util.ArrayList<>(1);
      own.add(i);
      members.add(own);
      this.size[i] = 1;
      final Set<String> forms = new HashSet<>();
      forms.add(mention.normalized());
      normalizedForms.add(forms);
      type[i] = knownType(mention.type());
      numbers.add(known(Number.class, mention.number(), Number.UNKNOWN));
      genders.add(known(Gender.class, mention.gender(), Gender.UNKNOWN));
      animacies.add(known(Animacy.class, mention.animacy(), Animacy.UNKNOWN));
      persons.add(known(Person.class, mention.person(), Person.UNKNOWN));
      final Set<String> content = new HashSet<>();
      for (final String word : mention.words()) {
        if (!CorefLexicon.stopWord(word)) {
          content.add(word);
        }
      }
      words.add(content);
      final Set<String> head = new HashSet<>();
      if (mention.head() != null) {
        head.add(mention.head());
      }
      heads.add(head);
    }
  }

  /**
   * Creates an enum set containing one known value.
   *
   * @param enumType The enum type.
   * @param value The observed value.
   * @param unknown The value representing missing evidence.
   * @param <E> The enum type.
   * @return An empty set for the unknown value, otherwise a singleton set.
   */
  private <E extends Enum<E>> Set<E> known(Class<E> enumType, E value, E unknown) {
    final Set<E> set = EnumSet.noneOf(enumType);
    if (value != unknown) {
      set.add(value);
    }
    return set;
  }

  /**
   * Normalizes an entity type that supplies no type evidence.
   *
   * @param label The entity type.
   * @return The known type, or {@code null}.
   */
  private String knownType(String label) {
    return label == null || NameFinderAnnotator.UNTYPED.equals(label) ? null : label;
  }

  /**
   * Finds the root of a mention's cluster, compressing the walked path.
   *
   * @param i The mention index.
   * @return The root index, the earliest mention of the cluster.
   */
  int find(int i) {
    int root = i;
    while (parent[root] != root) {
      root = parent[root];
    }
    int node = i;
    while (parent[node] != root) {
      final int next = parent[node];
      parent[node] = root;
      node = next;
    }
    return root;
  }

  /**
   * Checks whether two mentions' clusters may merge by entity type.
   *
   * @param a The first mention index.
   * @param b The second mention index.
   * @return {@code true} unless both clusters know a type and the types differ.
   */
  boolean typesCompatible(int a, int b) {
    final String typeA = type[find(a)];
    final String typeB = type[find(b)];
    return typeA == null || typeB == null || typeA.equals(typeB);
  }

  /**
   * Reads the entity type known for a cluster.
   *
   * @param i The mention index.
   * @return The known entity type of the mention's cluster, or {@code null}.
   */
  String type(int i) {
    return type[find(i)];
  }

  /**
   * Checks whether two clusters agree on number, gender, animacy, and person.
   *
   * @param a The first mention index.
   * @param b The second mention index.
   * @return {@code true} if no attribute has disjoint known values on the two sides.
   */
  boolean attributesAgree(int a, int b) {
    final int rootA = find(a);
    final int rootB = find(b);
    return agree(numbers.get(rootA), numbers.get(rootB))
        && agree(genders.get(rootA), genders.get(rootB))
        && agree(animacies.get(rootA), animacies.get(rootB))
        && agree(persons.get(rootA), persons.get(rootB));
  }

  /**
   * Checks whether two clusters agree on number alone.
   *
   * @param a The first mention index.
   * @param b The second mention index.
   * @return {@code true} if either number set is empty or they intersect.
   */
  boolean numbersAgree(int a, int b) {
    return agree(numbers.get(find(a)), numbers.get(find(b)));
  }

  /**
   * Checks whether two sets of known values intersect or lack evidence.
   *
   * @param a The first set.
   * @param b The second set.
   * @param <E> The value type.
   * @return {@code true} if either set is empty or the sets intersect.
   */
  private <E> boolean agree(Set<E> a, Set<E> b) {
    if (a.isEmpty() || b.isEmpty()) {
      return true;
    }
    for (final E value : a) {
      if (b.contains(value)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks whether a cluster contains a gender.
   *
   * @param i The mention index.
   * @param gender The gender to find.
   * @return Whether the mention's cluster contains the gender.
   */
  boolean hasGender(int i, Gender gender) {
    return genders.get(find(i)).contains(gender);
  }

  /**
   * Checks whether a cluster contains an animacy value.
   *
   * @param i The mention index.
   * @param animacy The animacy to find.
   * @return Whether the mention's cluster contains the animacy.
   */
  boolean hasAnimacy(int i, Animacy animacy) {
    return animacies.get(find(i)).contains(animacy);
  }

  /**
   * Reads the content words accumulated for a cluster.
   *
   * @param i The mention index.
   * @return The content words accumulated in the mention's cluster.
   */
  Set<String> words(int i) {
    return words.get(find(i));
  }

  /**
   * Reads the head words accumulated for a cluster.
   *
   * @param i The mention index.
   * @return The head words accumulated in the mention's cluster.
   */
  Set<String> heads(int i) {
    return heads.get(find(i));
  }

  /**
   * Reads the normalized mention texts accumulated for a cluster.
   *
   * @param i The mention index.
   * @return The normalized mention texts accumulated in the mention's cluster.
   */
  Set<String> normalizedForms(int i) {
    return normalizedForms.get(find(i));
  }

  /**
   * Counts the mentions in a cluster.
   *
   * @param i The mention index.
   * @return The number of mentions in the mention's cluster.
   */
  int size(int i) {
    return size[find(i)];
  }

  /**
   * Reads the mention indexes in a cluster.
   *
   * @param i The mention index.
   * @return The mention indexes in the mention's cluster, in merge order.
   */
  List<Integer> members(int i) {
    return members.get(find(i));
  }

  /**
   * Merges two mentions' clusters unless their known types differ. The earlier root
   * remains and takes the union of both attribute sets.
   *
   * @param a The first mention index.
   * @param b The second mention index.
   * @return {@code true} if the two mentions share a cluster when the call returns,
   *         {@code false} if the types conflict.
   */
  boolean union(int a, int b) {
    final int rootA = find(a);
    final int rootB = find(b);
    if (rootA == rootB) {
      return true;
    }
    if (type[rootA] != null && type[rootB] != null && !type[rootA].equals(type[rootB])) {
      return false;
    }
    final int keep = Math.min(rootA, rootB);
    final int drop = Math.max(rootA, rootB);
    parent[drop] = keep;
    if (type[keep] == null) {
      type[keep] = type[drop];
    }
    numbers.get(keep).addAll(numbers.get(drop));
    genders.get(keep).addAll(genders.get(drop));
    animacies.get(keep).addAll(animacies.get(drop));
    persons.get(keep).addAll(persons.get(drop));
    words.get(keep).addAll(words.get(drop));
    heads.get(keep).addAll(heads.get(drop));
    normalizedForms.get(keep).addAll(normalizedForms.get(drop));
    members.get(keep).addAll(members.get(drop));
    size[keep] += size[drop];
    return true;
  }
}
