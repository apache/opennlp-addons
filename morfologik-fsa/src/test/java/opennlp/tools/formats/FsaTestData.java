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

/** Builds small FSA5 automata for dictionary-reader tests. */
final class FsaTestData {

  private static final byte[] HEADER = {
      '\\', 'f', 's', 'a', 0x05, '_', '+', 0x01, 0, 0x02, 0, 0x06
  };

  /** Prevents utility-class instantiation. */
  private FsaTestData() {
  }

  /**
   * Builds an automaton accepting one non-empty byte sequence.
   *
   * @param labels The accepted bytes.
   * @return The serialized FSA5 automaton.
   * @throws IllegalArgumentException If labels is null or empty.
   */
  static byte[] singleSequence(byte[] labels) {
    if (labels == null || labels.length == 0) {
      throw new IllegalArgumentException("labels must not be null or empty");
    }
    final byte[] out = Arrays.copyOf(HEADER, HEADER.length + labels.length * 2);
    for (int i = 0; i < labels.length; i++) {
      out[HEADER.length + i * 2] = labels[i];
      out[HEADER.length + i * 2 + 1] = (byte) (i == labels.length - 1 ? 0x03 : 0x06);
    }
    return out;
  }

  /**
   * Builds a CFSA2 automaton accepting one non-empty sequence.
   *
   * @param labels The accepted bytes.
   * @return The serialized automaton.
   * @throws IllegalArgumentException If labels is null or empty.
   */
  static byte[] singleCfsaSequence(byte[] labels) {
    if (labels == null || labels.length == 0) {
      throw new IllegalArgumentException("labels must not be null or empty");
    }
    final byte[] header = {'\\', 'f', 's', 'a', (byte) 0xc6, 0, 7, 0, (byte) 0xc0, '^'};
    final byte[] out = Arrays.copyOf(header, header.length + labels.length * 2 + 1);
    for (int i = 0; i < labels.length; i++) {
      out[header.length + i * 2] = (byte) (i == labels.length - 1 ? 0x60 : 0xc0);
      out[header.length + i * 2 + 1] = labels[i];
    }
    return out;
  }
}
