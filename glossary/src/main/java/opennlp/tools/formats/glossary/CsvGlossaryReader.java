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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import opennlp.tools.glossary.GlossaryEntry;
import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.StringUtil;

/**
 * Reads CSV and TSV term lists. Each record contains an identifier followed by a term.
 * Additional columns are parsed but their values are ignored.
 *
 * <p>Quoting follows <a href="https://www.rfc-editor.org/rfc/rfc4180#section-2">
 * RFC 4180</a>: quoted fields may contain delimiters, doubled quotes, and line breaks.
 * An unquoted field cannot contain quotes, and only a delimiter, record end, or input
 * end may follow a closing quote. Records may end with LF, CRLF, or CR. Blank lines
 * are skipped, and a leading UTF-8 byte order mark is removed.</p>
 *
 * <p>Invalid UTF-8, malformed quoting, missing columns, and blank identifiers or terms
 * produce {@link InvalidFormatException}. Record errors include a line number.
 * Quoting is checked in headers and ignored columns as well as data fields.</p>
 *
 * <p>Configuration is immutable. Concurrent calls with separate streams are supported.</p>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4180">RFC 4180</a>
 * @since 3.0.0
 */
public final class CsvGlossaryReader implements GlossaryReader {

  private static final char QUOTE = '"';
  private static final char BYTE_ORDER_MARK = '\uFEFF';
  private static final char LINE_FEED = '\n';
  private static final char CARRIAGE_RETURN = '\r';

  /** The character separating fields; comma for CSV, tab for TSV. */
  private final char delimiter;

  /** Whether the first row is a header to drop. */
  private final boolean skipHeader;

  /**
   * Builds a comma-separated reader that treats the first row as data.
   */
  public CsvGlossaryReader() {
    this(',', false);
  }

  /**
   * Builds a reader for a delimiter and header convention.
   *
   * @param delimiter The field separator, for example {@code ','} or {@code '\t'}.
   *                  Must not be the quote character or a line break.
   * @param skipHeader Whether to drop the first row as a header.
   * @throws IllegalArgumentException Thrown if {@code delimiter} is the quote
   *         character or a line break.
   */
  public CsvGlossaryReader(char delimiter, boolean skipHeader) {
    if (delimiter == QUOTE || delimiter == LINE_FEED || delimiter == CARRIAGE_RETURN) {
      throw new IllegalArgumentException(
          "delimiter must not be the quote character or a line break");
    }
    this.delimiter = delimiter;
    this.skipHeader = skipHeader;
  }

  /** {@inheritDoc} */
  @Override
  public List<GlossaryEntry> read(InputStream in) throws IOException {
    if (in == null) {
      throw new IllegalArgumentException("in must not be null");
    }
    final BufferedReader reader = new BufferedReader(new InputStreamReader(in,
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)));
    try {
      return readRecords(reader);
    } catch (CharacterCodingException e) {
      throw new InvalidFormatException("glossary content is not valid UTF-8", e);
    }
  }

  /**
   * Parses decoded CSV records without closing the input.
   *
   * @param reader The decoded character stream.
   * @return The entries in file order.
   * @throws InvalidFormatException Thrown if a record has invalid quoting or fields.
   * @throws IOException Thrown if reading fails.
   */
  private List<GlossaryEntry> readRecords(BufferedReader reader) throws IOException {
    final List<GlossaryEntry> entries = new ArrayList<>();
    final List<String> fields = new ArrayList<>();
    final StringBuilder field = new StringBuilder();
    boolean inQuotes = false;
    boolean quoteClosed = false;
    boolean previousCr = false;
    boolean recordHasContent = false;
    boolean headerPending = skipHeader;
    int line = 1;
    int recordLine = 1;

    int read = reader.read();
    if (read == BYTE_ORDER_MARK) {
      read = reader.read();
    }
    while (read >= 0) {
      final char c = (char) read;
      if (c == CARRIAGE_RETURN || (c == LINE_FEED && !previousCr)) {
        line++;
      }
      previousCr = c == CARRIAGE_RETURN;
      if (inQuotes) {
        if (c == QUOTE) {
          final int next = reader.read();
          if (next == QUOTE) {
            field.append(QUOTE);
          } else {
            inQuotes = false;
            quoteClosed = true;
            read = next;
            continue;
          }
        } else {
          field.append(c);
        }
      } else if (quoteClosed && c != delimiter && c != LINE_FEED && c != CARRIAGE_RETURN) {
        throw new InvalidFormatException(
            "unexpected character after closing quote on line " + line);
      } else if (c == QUOTE) {
        if (!field.isEmpty()) {
          throw new InvalidFormatException("quote in unquoted field on line " + line);
        }
        inQuotes = true;
        recordHasContent = true;
      } else if (c == delimiter) {
        fields.add(field.toString());
        field.setLength(0);
        quoteClosed = false;
        recordHasContent = true;
      } else if (c == LINE_FEED || c == CARRIAGE_RETURN) {
        if (recordHasContent || !field.isEmpty()) {
          headerPending = endRecord(fields, field, entries, recordLine, headerPending);
        }
        recordHasContent = false;
        quoteClosed = false;
        recordLine = line;
      } else {
        field.append(c);
      }
      read = reader.read();
    }
    if (inQuotes) {
      throw new InvalidFormatException(
          "unclosed quoted field in record starting on line " + recordLine);
    }
    if (recordHasContent || !field.isEmpty()) {
      endRecord(fields, field, entries, recordLine, headerPending);
    }
    return entries;
  }

  /**
   * Validates a record, adds an entry unless the record is a header, and clears buffers.
   *
   * @param fields The completed fields of the record; emptied by this call.
   * @param field The trailing field in progress; emptied by this call.
   * @param entries The sink for accepted entries.
   * @param recordLine The line the record started on, for error messages.
   * @param headerPending Whether this record is the header to drop.
   * @return The header flag for the next record: always {@code false}.
   * @throws InvalidFormatException Thrown if the record has fewer than two columns or
   *         a blank id or term.
   */
  private boolean endRecord(List<String> fields, StringBuilder field,
      List<GlossaryEntry> entries, int recordLine, boolean headerPending)
      throws InvalidFormatException {
    fields.add(field.toString());
    field.setLength(0);
    try {
      if (headerPending) {
        return false;
      }
      if (fields.size() < 2) {
        throw new InvalidFormatException(
            "expected at least an id and a term column on line " + recordLine
                + ", found " + fields.size() + " column(s)");
      }
      final String id = fields.get(0);
      final String term = fields.get(1);
      if (StringUtil.isBlank(id)) {
        throw new InvalidFormatException("blank id on line " + recordLine);
      }
      if (StringUtil.isBlank(term)) {
        throw new InvalidFormatException("blank term on line " + recordLine);
      }
      entries.add(new GlossaryEntry(id, term));
      return false;
    } finally {
      fields.clear();
    }
  }
}
