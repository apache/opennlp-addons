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

/**
 * A bounded JSON syntax cursor used to validate decoded JWT objects without adding a
 * JSON library to the runtime. It recognizes
 * <a href="https://www.rfc-editor.org/rfc/rfc8259">RFC 8259</a> values and can capture one
 * string-valued member from the outermost object.
 */
final class JsonObjectParser {

  private static final int MAX_DEPTH = 64;

  private final String json;
  private int index;
  private int depth;
  private String captured;
  private boolean memberSeen;
  private boolean duplicate;

  /**
   * Parses a JSON object and optionally captures one top-level string member.
   *
   * @param json The complete JSON text.
   * @param member The member to capture, or {@code null} when only validation is needed.
   * @return The parse result. Never {@code null}.
   */
  static Result parse(String json, String member) {
    if (json == null) {
      return new Result(false, null);
    }
    final JsonObjectParser parser = new JsonObjectParser(json);
    parser.skipWhitespace();
    final boolean valid = parser.object(member, true);
    parser.skipWhitespace();
    return new Result(valid && parser.index == json.length() && !parser.duplicate,
        parser.captured);
  }

  /**
   * Creates one parser over the supplied text.
   *
   * @param json The JSON text.
   */
  private JsonObjectParser(String json) {
    this.json = json;
  }

  /**
   * Parses an object at the cursor.
   *
   * @param member The top-level member to capture.
   * @param outer Whether this is the outermost object.
   * @return {@code true} when a complete object was consumed.
   */
  private boolean object(String member, boolean outer) {
    if (!enter('{')) {
      return false;
    }
    skipWhitespace();
    if (take('}')) {
      leave();
      return true;
    }
    while (index < json.length()) {
      final String key = string();
      if (key == null) {
        return false;
      }
      skipWhitespace();
      if (!take(':')) {
        return false;
      }
      skipWhitespace();
      if (outer && member != null && member.equals(key)) {
        duplicate |= memberSeen;
        memberSeen = true;
        if (peek('"')) {
          captured = string();
          if (captured == null) {
            return false;
          }
        } else {
          if (!value()) {
            return false;
          }
        }
      } else if (!value()) {
        return false;
      }
      skipWhitespace();
      if (take('}')) {
        leave();
        return true;
      }
      if (!take(',')) {
        return false;
      }
      skipWhitespace();
    }
    return false;
  }

  /** @return {@code true} when one complete JSON value was consumed. */
  private boolean value() {
    if (index >= json.length()) {
      return false;
    }
    return switch (json.charAt(index)) {
      case '"' -> string() != null;
      case '{' -> object(null, false);
      case '[' -> array();
      case 't' -> literal("true");
      case 'f' -> literal("false");
      case 'n' -> literal("null");
      default -> number();
    };
  }

  /** @return {@code true} when one complete array was consumed. */
  private boolean array() {
    if (!enter('[')) {
      return false;
    }
    skipWhitespace();
    if (take(']')) {
      leave();
      return true;
    }
    while (index < json.length()) {
      if (!value()) {
        return false;
      }
      skipWhitespace();
      if (take(']')) {
        leave();
        return true;
      }
      if (!take(',')) {
        return false;
      }
      skipWhitespace();
    }
    return false;
  }

  /** @return The decoded string at the cursor, or {@code null} when malformed. */
  private String string() {
    if (!take('"')) {
      return null;
    }
    final StringBuilder value = new StringBuilder();
    while (index < json.length()) {
      final char c = json.charAt(index++);
      if (c == '"') {
        return value.toString();
      }
      if (c < 0x20) {
        return null;
      }
      if (c != '\\') {
        value.append(c);
        continue;
      }
      if (index >= json.length()) {
        return null;
      }
      final char escaped = json.charAt(index++);
      switch (escaped) {
        case '"', '\\', '/' -> value.append(escaped);
        case 'b' -> value.append('\b');
        case 'f' -> value.append('\f');
        case 'n' -> value.append('\n');
        case 'r' -> value.append('\r');
        case 't' -> value.append('\t');
        case 'u' -> {
          final int codePoint = hexQuad();
          if (codePoint < 0) {
            return null;
          }
          value.append((char) codePoint);
        }
        default -> {
          return null;
        }
      }
    }
    return null;
  }

  /** @return The decoded four-digit hexadecimal escape, or -1 when malformed. */
  private int hexQuad() {
    if (index + 4 > json.length()) {
      return -1;
    }
    int value = 0;
    for (int i = 0; i < 4; i++) {
      final int digit = hexDigit(json.charAt(index++));
      if (digit < 0) {
        return -1;
      }
      value = (value << 4) | digit;
    }
    return value;
  }

  /**
   * Converts one ASCII hexadecimal digit.
   *
   * @param c The character to convert.
   * @return The digit value, or -1 for any other character.
   */
  private int hexDigit(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'A' && c <= 'F') {
      return c - 'A' + 10;
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    return -1;
  }

  /** @return {@code true} when one RFC 8259 number was consumed. */
  private boolean number() {
    final int start = index;
    take('-');
    if (take('0')) {
      if (index < json.length() && isDigit(json.charAt(index))) {
        return false;
      }
    } else if (!digits()) {
      return false;
    }
    if (take('.')) {
      if (!digits()) {
        return false;
      }
    }
    if (take('e') || take('E')) {
      if (!take('+')) {
        take('-');
      }
      if (!digits()) {
        return false;
      }
    }
    return index > start;
  }

  /** @return {@code true} when at least one ASCII digit was consumed. */
  private boolean digits() {
    final int start = index;
    while (index < json.length() && isDigit(json.charAt(index))) {
      index++;
    }
    return index > start;
  }

  /**
   * Consumes a literal at the cursor.
   *
   * @param literal The expected text.
   * @return {@code true} when the literal was consumed.
   */
  private boolean literal(String literal) {
    if (index + literal.length() > json.length()) {
      return false;
    }
    for (int i = 0; i < literal.length(); i++) {
      if (json.charAt(index + i) != literal.charAt(i)) {
        return false;
      }
    }
    index += literal.length();
    return true;
  }

  /**
   * Enters a container while enforcing the nesting bound.
   *
   * @param opening The opening bracket or brace.
   * @return {@code true} when the container was entered.
   */
  private boolean enter(char opening) {
    if (!take(opening) || depth == MAX_DEPTH) {
      return false;
    }
    depth++;
    return true;
  }

  /** Leaves the current container. */
  private void leave() {
    depth--;
  }

  /** Skips the four whitespace characters admitted by RFC 8259. */
  private void skipWhitespace() {
    while (index < json.length()) {
      final char c = json.charAt(index);
      if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
        return;
      }
      index++;
    }
  }

  /**
   * Consumes one expected character.
   *
   * @param expected The expected character.
   * @return {@code true} when the character was consumed.
   */
  private boolean take(char expected) {
    if (index < json.length() && json.charAt(index) == expected) {
      index++;
      return true;
    }
    return false;
  }

  /**
   * Checks the character at the cursor without advancing.
   *
   * @param expected The expected character.
   * @return {@code true} when the expected character is at the cursor.
   */
  private boolean peek(char expected) {
    return index < json.length() && json.charAt(index) == expected;
  }

  /**
   * Tests for an ASCII digit.
   *
   * @param c The character to test.
   * @return {@code true} for an ASCII digit.
   */
  private boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  /**
   * A complete-object validation result and an optional captured member.
   *
   * @param valid Whether the complete text is one valid JSON object.
   * @param member The captured string member, or {@code null}.
   */
  record Result(boolean valid, String member) {
  }
}
