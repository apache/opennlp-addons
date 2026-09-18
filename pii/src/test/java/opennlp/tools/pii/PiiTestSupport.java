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

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Assertions;

/** Shared data and formatting checks for PII tests. */
final class PiiTestSupport {

  /** Prevents construction. */
  private PiiTestSupport() {
  }

  /**
   * Collects the public type constants and checks for duplicate values.
   *
   * @return The type values.
   * @throws IllegalAccessException If a public constant cannot be read.
   */
  static Set<String> declaredTypes() throws IllegalAccessException {
    final Set<String> types = new HashSet<>();
    for (final var field : PiiMention.class.getFields()) {
      if (field.getType() == String.class && Modifier.isStatic(field.getModifiers())
          && field.getName().startsWith("TYPE_")) {
        Assertions.assertTrue(types.add((String) field.get(null)), field.getName());
      }
    }
    return Set.copyOf(types);
  }

  /**
   * Inserts one separator after each complete group, without a trailing separator.
   *
   * @param number The compact number.
   * @param size The positive number of characters in each group.
   * @param separator The character between groups.
   * @return The formatted number.
   */
  static String grouped(String number, int size, char separator) {
    final StringBuilder result = new StringBuilder();
    for (int index = 0; index < number.length(); index++) {
      if (index > 0 && index % size == 0) {
        result.append(separator);
      }
      result.append(number.charAt(index));
    }
    return result.toString();
  }
}
