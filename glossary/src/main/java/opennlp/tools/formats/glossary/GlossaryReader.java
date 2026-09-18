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

package opennlp.tools.formats.glossary;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import opennlp.tools.glossary.GlossaryEntry;
import opennlp.tools.util.InvalidFormatException;

/**
 * Reads glossary entries in file order. Entries with the same identifier represent
 * aliases for that identifier.
 *
 * <p>Thread safety is implementation specific.</p>
 *
 * @since 3.0.0
 */
public interface GlossaryReader {

  /**
   * Reads glossary entries from a stream.
   *
   * @param in The stream to read. Must not be {@code null}. This method does not close
   *           the stream, including when reading fails.
   * @return The entries in file order. Never {@code null}, possibly empty.
   * @throws IllegalArgumentException Thrown if {@code in} is {@code null}.
   * @throws InvalidFormatException Thrown if the content is not valid for the format.
   * @throws IOException Thrown if reading the stream fails.
   */
  List<GlossaryEntry> read(InputStream in) throws IOException;
}
