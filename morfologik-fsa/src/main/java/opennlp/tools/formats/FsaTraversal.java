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

package opennlp.tools.formats;

import java.util.Arrays;
import java.util.BitSet;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/** Validates and traverses encoded acyclic automata without recursive calls. */
final class FsaTraversal {

  private final int root;
  private final IntUnaryOperator firstArc;
  private final IntUnaryOperator nextArc;
  private final IntUnaryOperator destination;
  private final IntUnaryOperator label;
  private final IntPredicate accepting;

  /**
   * Validates reachable nodes and stores access to the immutable encoded data.
   *
   * @param root The root node, or zero for an empty automaton.
   * @param dataLength The encoded data length.
   * @param firstArc Access to a node's first arc.
   * @param nextArc Access to the next arc, or zero at a node's end.
   * @param destination Access to an arc's target, or zero for a terminal arc.
   * @param label Access to an arc's unsigned label byte.
   * @param accepting Tests whether an arc accepts a sequence.
   * @throws IllegalArgumentException If a cycle exists or encoded data is invalid.
   */
  FsaTraversal(int root, int dataLength, IntUnaryOperator firstArc,
      IntUnaryOperator nextArc, IntUnaryOperator destination,
      IntUnaryOperator label, IntPredicate accepting) {
    this.root = root;
    this.firstArc = firstArc;
    this.nextArc = nextArc;
    this.destination = destination;
    this.label = label;
    this.accepting = accepting;
    validate(dataLength);
  }

  /**
   * Checks byte ranges and cycles once per reachable node.
   *
   * @param dataLength The encoded data length.
   * @throws IllegalArgumentException If the automaton is malformed.
   */
  private void validate(int dataLength) {
    if (root == 0) {
      return;
    }
    final BitSet active = new BitSet();
    final BitSet complete = new BitSet();
    final Stack stack = new Stack();
    pushNode(stack, root, dataLength, active);
    while (!stack.isEmpty()) {
      final int arc = stack.arc();
      if (arc == 0) {
        final int node = stack.pop();
        active.clear(node);
        complete.set(node);
        continue;
      }
      label.applyAsInt(arc);
      stack.setArc(nextArc.applyAsInt(arc));
      final int target = destination.applyAsInt(arc);
      if (target != 0) {
        requireRange(target, 1, dataLength);
        if (active.get(target)) {
          throw new IllegalArgumentException("FSA contains a cycle at byte " + target);
        }
        if (!complete.get(target)) {
          pushNode(stack, target, dataLength, active);
        }
      }
    }
  }

  /**
   * Adds a node to the current path.
   *
   * @param stack The current traversal.
   * @param node The node offset.
   * @param dataLength The encoded data length.
   * @param active Nodes on the current path.
   * @throws IllegalArgumentException If the node is outside the data.
   */
  private void pushNode(Stack stack, int node, int dataLength, BitSet active) {
    requireRange(node, 1, dataLength);
    final int first = firstArc.applyAsInt(node);
    active.set(node);
    stack.push(node, first);
  }

  /**
   * Checks a byte range before accessing encoded data.
   *
   * @param offset The starting offset.
   * @param length The required byte count.
   * @param limit The data length.
   * @throws IllegalArgumentException If the range is outside the data.
   */
  static void requireRange(int offset, int length, int limit) {
    if (offset < 0 || length < 0 || offset > limit - length) {
      throw new IllegalArgumentException("FSA byte range outside data: " + offset + ", length " + length);
    }
  }

  /**
   * Passes accepted sequences to an action using a per-call path and stack.
   *
   * @param action The action receiving a new array for each sequence.
   */
  void forEachSequence(Consumer<byte[]> action) {
    if (root == 0) {
      return;
    }
    final GrowableByteSequence path = new GrowableByteSequence();
    final Stack stack = new Stack();
    stack.push(root, firstArc.applyAsInt(root));
    while (!stack.isEmpty()) {
      final int arc = stack.arc();
      if (arc == 0) {
        stack.pop();
        if (!stack.isEmpty()) {
          path.pop();
        }
        continue;
      }
      stack.setArc(nextArc.applyAsInt(arc));
      path.push((byte) label.applyAsInt(arc));
      if (accepting.test(arc)) {
        action.accept(path.toByteArray());
      }
      final int target = destination.applyAsInt(arc);
      if (target == 0) {
        path.pop();
      } else {
        stack.push(target, firstArc.applyAsInt(target));
      }
    }
  }

  /** Node offsets and next arcs on the current path. */
  private static final class Stack {
    private static final int INITIAL_CAPACITY = 32;
    private int[] nodes = new int[INITIAL_CAPACITY];
    private int[] arcs = new int[INITIAL_CAPACITY];
    private int size;

    /** Creates an empty stack. */
    private Stack() {
    }

    /** {@return whether the path is empty} */
    private boolean isEmpty() {
      return size == 0;
    }

    /** {@return the next arc at the current node} */
    private int arc() {
      return arcs[size - 1];
    }

    /**
     * Sets the next arc at the current node.
     *
     * @param arc The next arc offset, or zero.
     */
    private void setArc(int arc) {
      arcs[size - 1] = arc;
    }

    /**
     * Adds a node to the path.
     *
     * @param node The node offset.
     * @param arc The first arc offset.
     */
    private void push(int node, int arc) {
      if (size == nodes.length) {
        nodes = Arrays.copyOf(nodes, size * 2);
        arcs = Arrays.copyOf(arcs, size * 2);
      }
      nodes[size] = node;
      arcs[size++] = arc;
    }

    /** {@return the node removed from the path} */
    private int pop() {
      return nodes[--size];
    }
  }
}
