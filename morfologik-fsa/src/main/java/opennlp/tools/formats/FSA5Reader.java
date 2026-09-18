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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Loads FSA5 automata and supplies accepted byte sequences.
 *
 * <p>The <a href="https://github.com/morfologik/morfologik-stemming/blob/2.2.0/morfologik-fsa/src/main/java/morfologik/fsa/FSA5.java">format description</a>
 * specifies the encoding. Reachable nodes and arcs are validated on load.
 * No third-party dependency is needed.</p>
 *
 * <p>Instances are immutable and support concurrent traversal.</p>
 */
public final class FSA5Reader implements FsaSequenceReader {

  private static final int BIT_FINAL_ARC = 0x01;
  private static final int BIT_LAST_ARC = 0x02;
  private static final int BIT_TARGET_NEXT = 0x04;

  /** Offset of the flags/goto field within an arc; the label occupies the byte before it. */
  private static final int ADDRESS_OFFSET = 1;
  private static final int HEADER_SIZE = 8;
  private static final int TERMINAL_NODE = 0;
  private static final int NO_ARC = 0;

  /** Largest address representable as an array index, including arc flags. */
  private static final long MAX_ENCODED_ADDRESS = ((long) Integer.MAX_VALUE << 3) | 7;

  private final byte[] arcs;
  private final int gotoLength;
  private final int nodeDataLength;
  private final FsaTraversal traversal;

  /**
   * Initializes the reader over the automaton's arc block.
   *
   * @param arcs           The arc block, the automaton bytes after the header.
   * @param gotoLength     The width in bytes of an arc's flags and goto address field.
   * @param nodeDataLength The width in bytes of the optional data preceding a node's arcs.
   * @throws IllegalArgumentException If the encoded automaton is malformed.
   */
  private FSA5Reader(byte[] arcs, int gotoLength, int nodeDataLength) {
    this.arcs = arcs;
    this.gotoLength = gotoLength;
    this.nodeDataLength = nodeDataLength;
    // FSA5 keeps a dummy node ahead of the epsilon node: skip the dummy's arc to reach the
    // epsilon node, then the root is its single arc's destination.
    final int epsilonNode = skipArc(firstArc(TERMINAL_NODE));
    final int rootNode = destinationNode(firstArc(epsilonNode));
    this.traversal = new FsaTraversal(rootNode, arcs.length,
        this::firstArc, this::nextArc, this::destinationNode, this::arcLabel, this::isFinal);
  }

  /**
   * Reads an FSA5 automaton from a stream.
   *
   * @param in The automaton bytes, referenced by an open {@link InputStream}. Must not be
   *           {@code null}.
   * @return A reader over the automaton.
   * @throws IllegalArgumentException Thrown if {@code in} is {@code null}.
   * @throws IOException If reading fails or the encoded automaton is malformed.
   */
  public static FSA5Reader read(InputStream in) throws IOException {
    if (in == null) {
      throw new IllegalArgumentException("in must not be null");
    }
    return fromBytes(in.readAllBytes());
  }

  /**
   * Reads an FSA5 automaton from bytes already in memory.
   *
   * @param bytes The whole automaton, magic header included.
   * @return A reader over the automaton.
   * @throws IllegalArgumentException If {@code bytes} is null.
   * @throws IOException Thrown if {@code bytes} is not an FSA5 automaton, its header is
   *                     truncated, or reachable nodes or arcs are malformed.
   */
  static FSA5Reader fromBytes(byte[] bytes) throws IOException {
    FsaSequenceReader.requireFsaHeader(bytes);
    if (bytes.length < HEADER_SIZE) {
      throw new IOException("truncated FSA5 header: fewer than " + HEADER_SIZE + " bytes");
    }
    if ((bytes[4] & 0xff) != VERSION_FSA5) {
      throw new IOException("unsupported FSA version 0x"
          + Integer.toHexString(bytes[4] & 0xff) + "; only FSA5 (0x05) is read here");
    }
    // One header byte packs the per-node data width in its high nibble and the goto address
    // width in its low nibble.
    final int widths = bytes[7] & 0xff;
    final int gotoLength = widths & 0x0f;
    final int nodeDataLength = (widths >>> 4) & 0x0f;
    if (gotoLength < 1) {
      throw new IOException("invalid FSA5 goto length: " + gotoLength);
    }
    final byte[] arcs = Arrays.copyOfRange(bytes, HEADER_SIZE, bytes.length);
    try {
      return new FSA5Reader(arcs, gotoLength, nodeDataLength);
    } catch (IllegalArgumentException e) {
      throw new IOException("malformed FSA5 automaton: " + e.getMessage(), e);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Sequences are produced in stored order with a per-call path buffer.</p>
   */
  @Override
  public void forEachSequence(Consumer<byte[]> action) {
    if (action == null) {
      throw new IllegalArgumentException("action must not be null");
    }
    traversal.forEachSequence(action);
  }

  /**
   * Returns an arc's label.
   *
   * @param arc The validated arc offset.
   * @return The unsigned label byte.
   */
  private int arcLabel(int arc) {
    FsaTraversal.requireRange(arc, 1, arcs.length);
    return arcs[arc] & 0xff;
  }

  /**
   * Tests whether an arc accepts a sequence.
   *
   * @param arc The validated arc offset.
   * @return Whether the arc is final.
   */
  private boolean isFinal(int arc) {
    return (arcs[arc + ADDRESS_OFFSET] & BIT_FINAL_ARC) != 0;
  }

  /**
   * Locates a node's first arc.
   *
   * @param node The offset of a node.
   * @return The offset of that node's first arc, skipping the per-node data the header declares.
   * @throws IllegalArgumentException If the encoded data is invalid.
   */
  private int firstArc(int node) {
    FsaTraversal.requireRange(node, nodeDataLength + 1, arcs.length);
    return nodeDataLength + node;
  }

  /**
   * Locates the next arc at the same node.
   *
   * @param arc The offset of an arc.
   * @return The offset of the following arc of the same node, or {@value #NO_ARC} if {@code arc}
   *         is the last one.
   * @throws IllegalArgumentException If the encoded data is invalid.
   */
  private int nextArc(int arc) {
    final int end = skipArc(arc);
    return (arcs[arc + ADDRESS_OFFSET] & BIT_LAST_ARC) != 0 ? NO_ARC : end;
  }

  /**
   * Resolves an arc's target node.
   *
   * @param arc The offset of an arc.
   * @return The offset of the node the arc points at, which is either the node laid out directly
   *         after the arc or the goto address stored in the arc, whose low three bits carry the
   *         arc flags; {@value #TERMINAL_NODE} if the arc ends a word without continuing.
   * @throws IllegalArgumentException If the encoded data is invalid.
   */
  private int destinationNode(int arc) {
    final int end = skipArc(arc);
    if ((arcs[arc + ADDRESS_OFFSET] & BIT_TARGET_NEXT) != 0) {
      return end;
    }
    long value = 0;
    for (int i = gotoLength - 1; i >= 0; i--) {
      value = (value << 8) | (arcs[arc + ADDRESS_OFFSET + i] & 0xff);
      if (value > MAX_ENCODED_ADDRESS) {
        throw new IllegalArgumentException("FSA5 target exceeds the address range");
      }
    }
    return (int) (value >>> 3);
  }

  /**
   * Locates the byte after an arc.
   *
   * @param arc The offset of an arc.
   * @return The offset just past that arc, that is, the start of whatever follows it.
   * @throws IllegalArgumentException If the encoded data is invalid.
   */
  private int skipArc(int arc) {
    FsaTraversal.requireRange(arc, ADDRESS_OFFSET + 1, arcs.length);
    final int length = ADDRESS_OFFSET
        + ((arcs[arc + ADDRESS_OFFSET] & BIT_TARGET_NEXT) != 0 ? 1 : gotoLength);
    FsaTraversal.requireRange(arc, length, arcs.length);
    return arc + length;
  }
}
