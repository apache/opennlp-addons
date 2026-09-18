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
 * Loads CFSA2 automata and supplies accepted byte sequences.
 *
 * <p>The <a href="https://github.com/morfologik/morfologik-stemming/blob/2.2.0/morfologik-fsa/src/main/java/morfologik/fsa/CFSA2.java">format description</a>
 * specifies the encoding. Reachable nodes and arcs are validated on load.
 * No third-party dependency is needed.</p>
 *
 * <p>Instances are immutable and support concurrent traversal.</p>
 */
public final class CFSA2Reader implements FsaSequenceReader {

  /** Set in the header flags when every node is prefixed with a variable-length entry count. */
  private static final int FLAG_NUMBERS = 0x0100;

  private static final int BIT_TARGET_NEXT = 0x80;
  private static final int BIT_LAST_ARC = 0x40;
  private static final int BIT_FINAL_ARC = 0x20;
  private static final int LABEL_INDEX_MASK = 0x1f;

  private static final int HEADER_SIZE = 8;
  private static final int TERMINAL_NODE = 0;
  private static final int NO_ARC = 0;

  /** Header flags defined by the Morfologik FSA format. */
  private static final int KNOWN_FLAGS = 0x030f;

  /** Maximum byte count of an unsigned 32-bit variable-length integer. */
  private static final int MAX_VINT_BYTES = 5;

  private final byte[] arcs;
  private final byte[] labelMapping;
  private final boolean hasNumbers;
  private final FsaTraversal traversal;

  /**
   * Initializes the reader over the automaton's arc block.
   *
   * @param arcs         The arc block, the automaton bytes after the header and label table.
   * @param labelMapping The label table indexed by an arc's label index.
   * @param hasNumbers   Whether each node is prefixed with a perfect-hash number to skip.
   * @throws IllegalArgumentException If the encoded automaton is malformed.
   */
  private CFSA2Reader(byte[] arcs, byte[] labelMapping, boolean hasNumbers) {
    this.arcs = arcs;
    this.labelMapping = labelMapping;
    this.hasNumbers = hasNumbers;
    final int rootNode = destinationNode(firstArc(TERMINAL_NODE));
    this.traversal = new FsaTraversal(rootNode, arcs.length,
        this::firstArc, this::nextArc, this::destinationNode, this::arcLabel, this::isFinal);
  }

  /**
   * Reads a CFSA2 automaton from a stream.
   *
   * @param in The automaton bytes, referenced by an open {@link InputStream}. Must not be
   *           {@code null}.
   * @return A reader over the automaton.
   * @throws IllegalArgumentException Thrown if {@code in} is {@code null}.
   * @throws IOException If reading fails or the encoded automaton is malformed.
   */
  public static CFSA2Reader read(InputStream in) throws IOException {
    if (in == null) {
      throw new IllegalArgumentException("in must not be null");
    }
    return fromBytes(in.readAllBytes());
  }

  /**
   * Reads a CFSA2 automaton from bytes already in memory.
   *
   * @param bytes The whole automaton, magic header included.
   * @return A reader over the automaton.
   * @throws IllegalArgumentException If {@code bytes} is null.
   * @throws IOException Thrown if {@code bytes} is not a CFSA2 automaton or its header is
   *                     truncated, or reachable nodes or arcs are malformed.
   */
  static CFSA2Reader fromBytes(byte[] bytes) throws IOException {
    FsaSequenceReader.requireFsaHeader(bytes);
    if (bytes.length < HEADER_SIZE) {
      throw new IOException("truncated CFSA2 header: fewer than " + HEADER_SIZE + " bytes");
    }
    if ((bytes[4] & 0xff) != VERSION_CFSA2) {
      throw new IOException("unsupported FSA version 0x"
          + Integer.toHexString(bytes[4] & 0xff) + "; only CFSA2 (0xc6) is read");
    }
    final int flags = ((bytes[5] & 0xff) << 8) | (bytes[6] & 0xff);
    if ((flags & ~KNOWN_FLAGS) != 0) {
      throw new IOException("unrecognized CFSA2 flags: 0x" + Integer.toHexString(flags));
    }
    final int labelMappingSize = bytes[7] & 0xff;
    final int arcsStart = HEADER_SIZE + labelMappingSize;
    if (arcsStart > bytes.length) {
      throw new IOException("truncated CFSA2 header: label table runs past end of data");
    }
    final byte[] labelMapping = Arrays.copyOfRange(bytes, HEADER_SIZE, arcsStart);
    final byte[] arcs = Arrays.copyOfRange(bytes, arcsStart, bytes.length);
    try {
      return new CFSA2Reader(arcs, labelMapping, (flags & FLAG_NUMBERS) != 0);
    } catch (IllegalArgumentException e) {
      throw new IOException("malformed CFSA2 automaton: " + e.getMessage(), e);
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
   * Tests whether an arc accepts a sequence.
   *
   * @param arc The validated arc offset.
   * @return Whether the arc is final.
   */
  private boolean isFinal(int arc) {
    return (flags(arc) & BIT_FINAL_ARC) != 0;
  }

  /**
   * Locates a node's first arc.
   *
   * @param node The offset of a node.
   * @return The offset of that node's first arc, skipping the entry count if the automaton
   *         carries one.
   * @throws IllegalArgumentException If the encoded data is invalid.
   */
  private int firstArc(int node) {
    final int first = hasNumbers ? skipVInt(node) : node;
    FsaTraversal.requireRange(first, 1, arcs.length);
    return first;
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
    return (flags(arc) & BIT_LAST_ARC) != 0 ? NO_ARC : end;
  }

  /**
   * Returns an arc's unsigned label.
   *
   * @param arc The offset of an arc.
   * @return The arc's label, taken from the header's label table when the flags byte indexes it,
   *         otherwise stored inline after the flags byte.
   * @throws IllegalArgumentException If the encoded data is invalid.
   */
  private int arcLabel(int arc) {
    final int index = flags(arc) & LABEL_INDEX_MASK;
    if (index > 0) {
      FsaTraversal.requireRange(index, 1, labelMapping.length);
      return labelMapping[index] & 0xff;
    }
    FsaTraversal.requireRange(arc, 2, arcs.length);
    return arcs[arc + 1] & 0xff;
  }

  /**
   * Resolves an arc's target node.
   *
   * @param arc The offset of an arc.
   * @return The offset of the node the arc points at, which is either the node laid out directly
   *         after the arc's own node or an explicit variable-length address;
   *         {@value #TERMINAL_NODE} if the arc ends a word without continuing.
   * @throws IllegalArgumentException If the encoded data is invalid.
   */
  private int destinationNode(int arc) {
    if ((flags(arc) & BIT_TARGET_NEXT) != 0) {
      int last = arc;
      while ((flags(last) & BIT_LAST_ARC) == 0) {
        last = skipArc(last);
      }
      return skipArc(last);
    }
    return readVInt(arc + ((flags(arc) & LABEL_INDEX_MASK) == 0 ? 2 : 1));
  }

  /**
   * Locates the byte after an arc.
   *
   * @param offset The offset of an arc.
   * @return The offset just past that arc, that is, the start of whatever follows it.
   * @throws IllegalArgumentException If the encoded data is invalid.
   */
  private int skipArc(int offset) {
    final int flag = flags(offset);
    final int prefix = (flag & LABEL_INDEX_MASK) == 0 ? 2 : 1;
    FsaTraversal.requireRange(offset, prefix, arcs.length);
    offset += prefix;
    if ((flag & BIT_TARGET_NEXT) == 0) {
      offset = skipVInt(offset);
    }
    return offset;
  }

  /**
   * Reads the variable-length integer starting at {@code offset}: seven value bits per byte,
   * least significant group first, with the high bit set on every byte but the last.
   *
   * @param offset The offset of the first byte of the integer.
   * @return The decoded value.
   * @throws IllegalArgumentException If the encoded data is invalid.
   */
  private int readVInt(int offset) {
    final int end = skipVInt(offset);
    int value = 0;
    for (int i = offset; i < end; i++) {
      value |= (arcs[i] & 0x7f) << ((i - offset) * 7);
    }
    if (value < 0) {
      throw new IllegalArgumentException("CFSA2 target exceeds the address range");
    }
    return value;
  }

  /**
   * Locates the byte after a 32-bit variable-length integer.
   *
   * @param offset The offset of the first byte of a variable-length integer.
   * @return The offset just past that integer.
   * @throws IllegalArgumentException If the encoded data is invalid.
   */
  private int skipVInt(int offset) {
    for (int i = 0; i < MAX_VINT_BYTES; i++) {
      FsaTraversal.requireRange(offset, i + 1, arcs.length);
      final int value = arcs[offset + i] & 0xff;
      if (i == MAX_VINT_BYTES - 1 && (value & 0xf0) != 0) {
        break;
      }
      if ((value & 0x80) == 0) {
        return offset + i + 1;
      }
    }
    throw new IllegalArgumentException("CFSA2 variable-length integer exceeds 32 bits");
  }

  /**
   * Returns an arc's flags.
   *
   * @param arc The arc offset.
   * @return The unsigned flags byte.
   * @throws IllegalArgumentException If the offset is outside the data.
   */
  private int flags(int arc) {
    FsaTraversal.requireRange(arc, 1, arcs.length);
    return arcs[arc] & 0xff;
  }
}
