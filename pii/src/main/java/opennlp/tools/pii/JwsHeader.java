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

package opennlp.tools.pii;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Parses JSON header syntax and a unique, non-empty ASCII {@code alg} string.
 * Other member values are checked for JSON syntax, not JOSE semantics.
 * Nesting uses an explicit stack; each instance parses a decoded header.
 */
final class JwsHeader {

  private static final String ALGORITHM = "alg";
  private static final int ALGORITHM_NAME = 1;
  private static final int ASCII_STRING = 2;
  private static final int NON_EMPTY_STRING = 4;

  /** The next JSON token required by an object or array. */
  private enum State {
    OBJECT_FIRST_KEY, OBJECT_KEY, OBJECT_COLON, OBJECT_VALUE, OBJECT_END,
    ARRAY_FIRST_VALUE, ARRAY_VALUE, ARRAY_END
  }

  private final CharSequence text;
  private final Deque<State> states = new ArrayDeque<>();
  private int position;
  private boolean algorithmNext;
  private boolean algorithmSeen;

  /**
   * Creates a reader for a decoded UTF-8 header.
   *
   * @param text The decoded, non-null header.
   */
  JwsHeader(CharSequence text) {
    this.text = text;
  }

  /**
   * Parses a JSON object and checks the top-level algorithm member.
   *
   * @return {@code true} when the complete header meets the syntax checks.
   */
  boolean isValid() {
    skipWhitespace();
    if (!consume('{')) {
      return false;
    }
    states.push(State.OBJECT_FIRST_KEY);
    while (!states.isEmpty()) {
      skipWhitespace();
      final State state = states.pop();
      switch (state) {
        case OBJECT_FIRST_KEY, OBJECT_KEY -> {
          if (state == State.OBJECT_FIRST_KEY && consume('}')) {
            continue;
          }
          final int flags = readString();
          if (flags < 0) {
            return false;
          }
          if (states.isEmpty()) {
            algorithmNext = (flags & ALGORITHM_NAME) != 0;
            if (algorithmNext && algorithmSeen) {
              return false;
            }
          }
          states.push(State.OBJECT_COLON);
        }
        case OBJECT_COLON -> {
          if (!consume(':')) {
            return false;
          }
          states.push(State.OBJECT_VALUE);
        }
        case OBJECT_VALUE -> {
          final boolean algorithm = states.isEmpty() && algorithmNext;
          states.push(State.OBJECT_END);
          if (algorithm) {
            final int flags = readString();
            final int required = ASCII_STRING | NON_EMPTY_STRING;
            if (flags < 0 || (flags & required) != required) {
              return false;
            }
            algorithmSeen = true;
          } else if (!readValue()) {
            return false;
          }
        }
        case OBJECT_END -> {
          if (consume(',')) {
            states.push(State.OBJECT_KEY);
          } else if (!consume('}')) {
            return false;
          }
        }
        case ARRAY_FIRST_VALUE, ARRAY_VALUE -> {
          if (state == State.ARRAY_FIRST_VALUE && consume(']')) {
            continue;
          }
          states.push(State.ARRAY_END);
          if (!readValue()) {
            return false;
          }
        }
        case ARRAY_END -> {
          if (consume(',')) {
            states.push(State.ARRAY_VALUE);
          } else if (!consume(']')) {
            return false;
          }
        }
      }
    }
    skipWhitespace();
    return position == text.length() && algorithmSeen;
  }

  /**
   * Parses a scalar value or starts a nested container without recursion.
   *
   * @return {@code true} when a value starts at the current position.
   */
  private boolean readValue() {
    if (position == text.length()) {
      return false;
    }
    return switch (text.charAt(position)) {
      case '"' -> readString() >= 0;
      case '{' -> {
        position++;
        states.push(State.OBJECT_FIRST_KEY);
        yield true;
      }
      case '[' -> {
        position++;
        states.push(State.ARRAY_FIRST_VALUE);
        yield true;
      }
      case 't' -> readLiteral("true");
      case 'f' -> readLiteral("false");
      case 'n' -> readLiteral("null");
      default -> readNumber();
    };
  }

  /**
   * Parses and checks a JSON string without retaining the contents.
   *
   * @return Algorithm-name, ASCII and non-empty flags, or {@code -1} for invalid syntax.
   */
  private int readString() {
    if (!consume('"')) {
      return -1;
    }
    boolean algorithm = true;
    boolean ascii = true;
    int length = 0;
    while (position < text.length()) {
      int c = text.charAt(position++);
      if (c == '"') {
        return (algorithm && length == ALGORITHM.length() ? ALGORITHM_NAME : 0)
            | (ascii ? ASCII_STRING : 0) | (length > 0 ? NON_EMPTY_STRING : 0);
      }
      if (c < 0x20) {
        return -1;
      }
      if (c == '\\') {
        c = readEscape();
        if (c < 0) {
          return -1;
        }
      }
      algorithm &= length < ALGORITHM.length() && c == ALGORITHM.charAt(length);
      ascii &= c < 0x80;
      length++;
    }
    return -1;
  }

  /**
   * Parses a JSON escape after the backslash.
   *
   * @return The decoded UTF-16 unit, or {@code -1} for an invalid escape.
   */
  private int readEscape() {
    if (position == text.length()) {
      return -1;
    }
    final char c = text.charAt(position++);
    return switch (c) {
      case '"', '\\', '/' -> c;
      case 'b' -> '\b';
      case 'f' -> '\f';
      case 'n' -> '\n';
      case 'r' -> '\r';
      case 't' -> '\t';
      case 'u' -> readUnicodeEscape();
      default -> -1;
    };
  }

  /**
   * Parses 4 ASCII hexadecimal digits from a Unicode escape.
   *
   * @return The decoded UTF-16 unit, or {@code -1} for invalid digits.
   */
  private int readUnicodeEscape() {
    if (text.length() - position < 4) {
      return -1;
    }
    int value = 0;
    for (int i = 0; i < 4; i++) {
      final int digit = Ascii.hexValue(text.charAt(position++));
      if (digit < 0) {
        return -1;
      }
      value = value * 16 + digit;
    }
    return value;
  }

  /**
   * Parses JSON number syntax without converting the numeric value.
   *
   * @return {@code true} for a valid integer, decimal, or exponent form.
   */
  private boolean readNumber() {
    consume('-');
    if (consume('0')) {
      if (position < text.length() && Ascii.isDigit(text.charAt(position))) {
        return false;
      }
    } else if (!readDigits()) {
      return false;
    }
    if (consume('.') && !readDigits()) {
      return false;
    }
    if (consume('e') || consume('E')) {
      if (!consume('+')) {
        consume('-');
      }
      return readDigits();
    }
    return true;
  }

  /**
   * Parses one or more ASCII decimal digits.
   *
   * @return {@code true} if at least one digit was read.
   */
  private boolean readDigits() {
    final int start = position;
    while (position < text.length() && Ascii.isDigit(text.charAt(position))) {
      position++;
    }
    return position > start;
  }

  /**
   * Parses a JSON keyword.
   *
   * @param literal The expected keyword.
   * @return {@code true} if it was consumed.
   */
  private boolean readLiteral(String literal) {
    for (int i = 0; i < literal.length(); i++) {
      if (!consume(literal.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /** Skips the 4 whitespace characters permitted by JSON. */
  private void skipWhitespace() {
    while (position < text.length()) {
      final char c = text.charAt(position);
      if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
        break;
      }
      position++;
    }
  }

  /**
   * Consumes an expected character.
   *
   * @param expected The character to consume.
   * @return {@code true} if it was present.
   */
  private boolean consume(char expected) {
    if (position < text.length() && text.charAt(position) == expected) {
      position++;
      return true;
    }
    return false;
  }
}
