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

package opennlp.tools.assets;

import java.io.InputStream;

/** Reads a decoded prefix from a payload already validated by the asset detector. */
final class Base64PayloadInputStream extends InputStream {

  private static final int DIGIT_BITS = 6;

  private final CharSequence text;
  private final int end;
  private int index;
  private int remaining;
  private int bits;
  private int availableBits;

  /**
   * Creates a reader over validated base64 text, permitting either alphabet and line breaks.
   *
   * @param text The original text.
   * @param start The inclusive payload start.
   * @param end The exclusive payload end.
   * @param maxBytes The maximum decoded prefix length.
   */
  Base64PayloadInputStream(CharSequence text, int start, int end, int maxBytes) {
    this.text = text;
    this.index = start;
    this.end = end;
    this.remaining = maxBytes;
  }

  /** {@inheritDoc} */
  @Override
  public int read() {
    if (remaining == 0) {
      return -1;
    }
    while (index < end) {
      final int value = digit(text.charAt(index++));
      if (value < 0) {
        continue;
      }
      bits = (bits << DIGIT_BITS) | value;
      availableBits += DIGIT_BITS;
      if (availableBits >= Byte.SIZE) {
        availableBits -= Byte.SIZE;
        remaining--;
        return (bits >>> availableBits) & 0xff;
      }
    }
    return -1;
  }

  /**
   * Decodes a digit from either base64 alphabet for reading or prefix selection.
   *
   * @param value The encoded character.
   * @return Its six-bit value, or -1 for padding and other characters.
   */
  static int digit(char value) {
    if (value >= 'A' && value <= 'Z') {
      return value - 'A';
    }
    if (value >= 'a' && value <= 'z') {
      return value - 'a' + 26;
    }
    if (value >= '0' && value <= '9') {
      return value - '0' + 52;
    }
    return switch (value) {
      case '+', '-' -> 62;
      case '/', '_' -> 63;
      default -> -1;
    };
  }
}
