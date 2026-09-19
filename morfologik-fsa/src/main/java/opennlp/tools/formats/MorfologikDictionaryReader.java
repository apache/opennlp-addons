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
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;

import opennlp.tools.lemmatizer.DictionaryLemmatizer;
import opennlp.tools.util.StringUtil;

/**
 * Builds a {@link DictionaryLemmatizer} from a morfologik-format morphological dictionary: an
 * FSA5 or CFSA2 automaton (read by {@link FsaSequenceReader}) whose accepted byte sequences are
 * {@code surfaceForm SEP encodedBase SEP tag}, paired with the {@code .info} metadata that
 * declares the separator byte, the character encoding, and the base-form encoder.
 *
 * <p>This reader adds no third-party dependency. The base form (lemma) is stored relative to the
 * surface form to save space; all four encoders are decoded here. Each is a run of control bytes
 * offset by {@code 'A'} modulo 256, followed by bytes to append:</p>
 * <ul>
 *   <li>{@code NONE}: the encoded bytes are the base form verbatim.</li>
 *   <li>{@code SUFFIX} ({@code K}): drop {@code K} bytes from the end of the form, then append.</li>
 *   <li>{@code PREFIX} ({@code P},{@code K}): drop {@code P} from the front and {@code K} from the
 *       end of the form, then append.</li>
 *   <li>{@code INFIX} ({@code I},{@code L},{@code K}): drop {@code L} bytes at offset {@code I} and
 *       {@code K} from the end of the form, then append.</li>
 * </ul>
 * <p>A removal count of 255 replaces the complete surface form with the appended bytes.
 * An infix position of 255 is an ordinary byte offset. Control bytes may equal the field
 * separator. See the
 * <a href="https://github.com/morfologik/morfologik-stemming/tree/2.2.0/morfologik-stemming/src/main/java/morfologik/stemming">Morfologik encoders</a>
 * for the format.</p>
 *
 * <p>Surface forms are lower-cased with {@link StringUtil#toLowerCase(String)} for both
 * stored entries and query tokens. Tags and lemmas retain their decoded contents,
 * including {@code #}, tabs and line breaks. Invalid character data is rejected on load.</p>
 *
 * <p>Loading expands every entry into an in-memory lookup map. Lookup uses that map,
 * not the compressed automaton. Budget heap for the decoded forms, tags and lemmas,
 * including temporary collections during loading; file size alone is not a memory estimate.</p>
 *
 * <p>Dictionary data is supplied by the caller; none is bundled with OpenNLP. This class is
 * stateless, so its methods may be called concurrently.</p>
 */
public final class MorfologikDictionaryReader {

  /** The base-form encoder declared by a dictionary's {@code fsa.dict.encoder}. */
  public enum BaseFormEncoding {

    /** The encoded bytes are the base form verbatim. */
    NONE,

    /** The base form drops trailing bytes of the surface form, then appends the encoded rest. */
    SUFFIX,

    /** As {@link #SUFFIX}, and leading bytes of the surface form are dropped too. */
    PREFIX,

    /** As {@link #SUFFIX}, and a run of bytes inside the surface form is dropped too. */
    INFIX
  }

  /** Offset added to a control value before conversion to a byte. */
  private static final int CONTROL_OFFSET = 'A';

  /** Removal count for replacement of the complete surface form. */
  private static final int REPLACE_FORM = 255;

  private static final String KEY_SEPARATOR = "fsa.dict.separator";
  private static final String KEY_ENCODING = "fsa.dict.encoding";
  private static final String KEY_ENCODER = "fsa.dict.encoder";

  /** Prevents utility-class instantiation. */
  private MorfologikDictionaryReader() {
  }

  /**
   * Reads a morfologik dictionary into a {@link DictionaryLemmatizer} using an explicit
   * separator, encoder, and charset.
   *
   * @param dictionary The FSA5 or CFSA2 automaton, referenced by an open {@link InputStream}.
   *                   Must not be {@code null}.
   * @param separator  The byte separating the form, encoded base, and tag fields.
   * @param encoding   The base-form encoder. Must not be {@code null}.
   * @param charset    The character encoding of the dictionary bytes. Must not be {@code null}.
   * @return A {@link DictionaryLemmatizer} over the decoded entries.
   * @throws IllegalArgumentException Thrown if {@code dictionary}, {@code encoding}, or
   *                                  {@code charset} is {@code null}.
   * @throws IOException Thrown on IO errors, if the stream is not a supported FSA automaton, or
   *                     if an entry has no form separator, incomplete controls, invalid removal ranges
   *                     or invalid character data.
   */
  public static DictionaryLemmatizer read(InputStream dictionary, byte separator,
      BaseFormEncoding encoding, Charset charset) throws IOException {
    if (dictionary == null) {
      throw new IllegalArgumentException("dictionary must not be null");
    }
    if (encoding == null) {
      throw new IllegalArgumentException("encoding must not be null");
    }
    if (charset == null) {
      throw new IllegalArgumentException("charset must not be null");
    }

    return readEntries(dictionary, separator, encoding, charset).toLemmatizer();
  }

  /**
   * Collects decoded entries from an FSA stream.
   *
   * @param dictionary The automaton stream.
   * @param separator The separator byte.
   * @param encoding The base-form encoder.
   * @param charset The dictionary charset.
   * @return The collected entries.
   * @throws IOException If the dictionary cannot be read or contains invalid entries.
   */
  private static LemmatizerEntries readEntries(InputStream dictionary, byte separator,
      BaseFormEncoding encoding, Charset charset) throws IOException {
    final FsaSequenceReader automaton = FsaSequenceReader.read(dictionary);
    final LemmatizerEntries entries = new LemmatizerEntries();
    final CharsetDecoder decoder = charset.newDecoder();
    try {
      automaton.forEachSequence(sequence -> addEntry(sequence, separator, encoding, decoder, entries));
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }

    return entries;
  }

  /**
   * Reads a morfologik dictionary into a {@link DictionaryLemmatizer}, taking the separator,
   * charset, and encoder from the dictionary's UTF-8 {@code .info} metadata.
   * The separator character must encode as a single byte in the dictionary charset.
   * Optional {@code fsa.dict.input-conversion} pairs apply in metadata order after
   * lower-casing each query token. Dictionary lookup remains case-insensitive.
   * Tags and lemmas are unchanged. Spelling options do not affect lemmatizer lookup.
   *
   * @param dictionary The FSA5 or CFSA2 automaton, referenced by an open {@link InputStream}.
   *                   Must not be {@code null}.
   * @param info       The {@code .info} metadata properties, referenced by an open
   *                   {@link InputStream}. Must not be {@code null} and must declare
   *                   {@code fsa.dict.separator}, {@code fsa.dict.encoding}, and
   *                   {@code fsa.dict.encoder}.
   * @return A {@link DictionaryLemmatizer} over the decoded entries.
   * @throws IllegalArgumentException Thrown if an argument is {@code null}, a required metadata
   *                                  key is missing or a metadata value is invalid.
   * @throws IOException Thrown on IO errors, invalid dictionary content or invalid UTF-8 metadata.
   */
  public static DictionaryLemmatizer read(InputStream dictionary, InputStream info)
      throws IOException {
    if (dictionary == null) {
      throw new IllegalArgumentException("dictionary must not be null");
    }
    if (info == null) {
      throw new IllegalArgumentException("info must not be null");
    }
    final Properties properties = new Properties();
    properties.load(new InputStreamReader(info, StandardCharsets.UTF_8.newDecoder()));

    final String separator = required(properties, KEY_SEPARATOR);
    if (separator.length() != 1) {
      throw new IllegalArgumentException(KEY_SEPARATOR + " must be a single character");
    }
    final Charset charset = Charset.forName(required(properties, KEY_ENCODING));
    final BaseFormEncoding encoding =
        BaseFormEncoding.valueOf(StringUtil.toUpperCase(required(properties, KEY_ENCODER).trim()));
    final byte separatorByte = separatorByte(separator, charset);
    final String inputConversion = properties.getProperty(MorfologikInputConversion.PROPERTY);
    final MorfologikInputConversion conversion = inputConversion == null ? null
        : MorfologikInputConversion.parse(inputConversion);
    final LemmatizerEntries entries = readEntries(dictionary, separatorByte, encoding, charset);
    if (conversion == null || conversion.isEmpty()) {
      return entries.toLemmatizer();
    }
    final DictionaryLemmatizer lemmatizer = conversion.newLemmatizer();
    entries.copyTo(lemmatizer);
    return lemmatizer;
  }

  /**
   * Encodes the metadata separator in the dictionary charset.
   *
   * @param separator The single separator character.
   * @param charset The dictionary encoding.
   * @return The separator byte.
   * @throws IllegalArgumentException If the separator cannot encode as a single byte.
   */
  private static byte separatorByte(String separator, Charset charset) {
    try {
      final ByteBuffer encoded = charset.newEncoder().encode(CharBuffer.wrap(separator));
      if (encoded.remaining() != 1) {
        throw new IllegalArgumentException(KEY_SEPARATOR + " must encode as one byte in " + charset);
      }
      return encoded.get();
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException(KEY_SEPARATOR + " cannot be encoded in " + charset, e);
    }
  }

  /**
   * Looks up a metadata key that the dictionary must declare.
   *
   * @param properties The parsed {@code .info} metadata.
   * @param key        The key to read.
   * @return The declared value.
   * @throws IllegalArgumentException Thrown if {@code key} is not declared.
   */
  private static String required(Properties properties, String key) {
    final String value = properties.getProperty(key);
    if (value == null) {
      throw new IllegalArgumentException("missing required metadata key: " + key);
    }
    return value;
  }

  /**
   * Decodes one accepted automaton sequence and records it under its form and tag. The form is
   * folded to lower case with {@link StringUtil#toLowerCase(String)}, matching the conversion
   * applied to query tokens by the returned lemmatizer.
   *
   * @param sequence  The accepted bytes: form, separator, encoded base, separator, tag. A missing
   *                  trailing separator is read as an empty tag.
   * @param separator The byte separating the three fields.
   * @param encoding  The base-form encoder the dictionary declares.
   * @param decoder   Converts fields to text, reporting malformed or unmappable bytes.
   * @param entries   Collects lemmas for each form and tag in first-seen order.
   * @throws UncheckedIOException Thrown if the sequence has no separator or its encoded base is
   *                              malformed, or a field contains invalid character data.
   *                              The public read method unwraps this exception.
   */
  private static void addEntry(byte[] sequence, byte separator, BaseFormEncoding encoding,
      CharsetDecoder decoder, LemmatizerEntries entries) {
    final int firstSeparator = indexOf(sequence, separator, 0);
    if (firstSeparator < 0) {
      throw new UncheckedIOException(new IOException(
          "morfologik entry has no separator"));
    }
    final int secondSeparator = indexOf(sequence, separator,
        firstSeparator + 1 + controlBytes(encoding));
    final int baseEnd = secondSeparator < 0 ? sequence.length : secondSeparator;

    final byte[] form = Arrays.copyOfRange(sequence, 0, firstSeparator);
    final byte[] encodedBase = Arrays.copyOfRange(sequence, firstSeparator + 1, baseEnd);
    final byte[] base;
    try {
      base = decodeBaseForm(form, encodedBase, encoding);
    } catch (IllegalArgumentException e) {
      throw new UncheckedIOException(new IOException(
          "malformed morfologik entry", e));
    }

    try {
      final String surface = decoder.decode(ByteBuffer.wrap(form)).toString();
      final String lemma = decoder.decode(ByteBuffer.wrap(base)).toString();
      final String tag = secondSeparator < 0 ? ""
          : decoder.decode(ByteBuffer.wrap(sequence, secondSeparator + 1,
              sequence.length - secondSeparator - 1)).toString();
      entries.add(surface, tag, lemma);
    } catch (CharacterCodingException e) {
      throw new UncheckedIOException(new IOException("invalid morfologik character data", e));
    }
  }

  /**
   * Recovers a base form from a surface form and its encoded representation.
   *
   * @param form     The surface form bytes.
   * @param encoded  The encoded base bytes: control bytes followed by literal bytes to append.
   * @param encoding The encoder that produced {@code encoded}.
   * @return The decoded base form bytes.
   * @throws IllegalArgumentException Thrown if {@code encoded} is too short for the encoder,
   *                                  or removal ranges overlap or exceed {@code form}.
   */
  static byte[] decodeBaseForm(byte[] form, byte[] encoded, BaseFormEncoding encoding) {
    final int prefixBytes = controlBytes(encoding);
    require(encoded, prefixBytes, encoding);
    switch (encoding) {
      case NONE:
        return encoded.clone();
      case SUFFIX: {
        final int suffix = control(encoded[0]);
        final int keep = suffix == REPLACE_FORM ? 0 : bounded(form.length - suffix, form);
        return join(form, 0, keep, encoded, prefixBytes);
      }
      case PREFIX: {
        final int prefix = control(encoded[0]);
        final int suffix = control(encoded[1]);
        if (prefix == REPLACE_FORM || suffix == REPLACE_FORM) {
          return Arrays.copyOfRange(encoded, prefixBytes, encoded.length);
        }
        final int start = bounded(prefix, form);
        final int end = bounded(form.length - suffix, form);
        requireOrdered(start, end);
        return join(form, start, end, encoded, prefixBytes);
      }
      case INFIX: {
        final int position = control(encoded[0]);
        final int length = control(encoded[1]);
        final int suffix = control(encoded[2]);
        if (length == REPLACE_FORM || suffix == REPLACE_FORM) {
          return Arrays.copyOfRange(encoded, prefixBytes, encoded.length);
        }
        final int cut = bounded(position, form);
        final int resume = bounded(cut + length, form);
        final int end = bounded(form.length - suffix, form);
        requireOrdered(resume, end);
        return join3(form, cut, resume, end, encoded, prefixBytes);
      }
      default:
        throw new IllegalArgumentException("unknown encoder: " + encoding);
    }
  }

  /**
   * Counts the control bytes preceding the appended bytes.
   *
   * @param encoding The encoder.
   * @return The control byte count.
   */
  private static int controlBytes(BaseFormEncoding encoding) {
    return switch (encoding) {
      case NONE -> 0;
      case SUFFIX -> 1;
      case PREFIX -> 2;
      case INFIX -> 3;
    };
  }

  /**
   * Converts a control byte to an unsigned removal count or position.
   *
   * @param b A control byte of an encoded base form.
   * @return The number of bytes it stands for.
   */
  private static int control(byte b) {
    return (b - CONTROL_OFFSET) & 0xff;
  }

  /**
   * Checks that removed ranges do not overlap.
   *
   * @param start The start of the retained range.
   * @param end The end of the retained range.
   * @throws IllegalArgumentException If {@code start} exceeds {@code end}.
   */
  private static void requireOrdered(int start, int end) {
    if (start > end) {
      throw new IllegalArgumentException("encoded base removal ranges overlap");
    }
  }

  /**
   * Checks that an encoded base form carries the control bytes its encoder needs.
   *
   * @param encoded     The encoded base bytes.
   * @param prefixBytes The number of leading control bytes {@code encoding} requires.
   * @param encoding    The encoder that produced {@code encoded}.
   * @throws IllegalArgumentException Thrown if {@code encoded} is shorter than {@code prefixBytes}.
   */
  private static void require(byte[] encoded, int prefixBytes, BaseFormEncoding encoding) {
    if (encoded.length < prefixBytes) {
      throw new IllegalArgumentException(
          encoding + " encoded base needs at least " + prefixBytes + " control byte(s)");
    }
  }

  /**
   * Checks that a decoded control byte addresses a position inside the surface form.
   *
   * @param index The position a control byte resolved to.
   * @param form  The surface form the position must fall in.
   * @return {@code index}, unchanged.
   * @throws IllegalArgumentException Thrown if {@code index} lies outside {@code form}.
   */
  private static int bounded(int index, byte[] form) {
    if (index < 0 || index > form.length) {
      throw new IllegalArgumentException(
          "encoded base addresses byte " + index + " outside a form of length " + form.length);
    }
    return index;
  }

  /**
   * Concatenates one kept run of the surface form with the literal bytes of the encoded base.
   *
   * @param form       The surface form bytes.
   * @param from       The first kept byte of {@code form}.
   * @param to         The position just past the last kept byte of {@code form}.
   * @param encoded    The encoded base bytes.
   * @param appendFrom The first literal byte of {@code encoded}, that is, the position just past
   *                   its control bytes.
   * @return The decoded base form bytes.
   */
  private static byte[] join(byte[] form, int from, int to, byte[] encoded, int appendFrom) {
    final int kept = to - from;
    final int appended = encoded.length - appendFrom;
    final byte[] out = new byte[kept + appended];
    System.arraycopy(form, from, out, 0, kept);
    System.arraycopy(encoded, appendFrom, out, kept, appended);
    return out;
  }

  /**
   * Concatenates two kept runs of the surface form, the infix between them dropped, with the
   * literal bytes of the encoded base.
   *
   * @param form       The surface form bytes.
   * @param headEnd    The position just past the last kept byte before the dropped infix.
   * @param tailFrom   The first kept byte after the dropped infix.
   * @param tailEnd    The position just past the last kept byte of {@code form}.
   * @param encoded    The encoded base bytes.
   * @param appendFrom The first literal byte of {@code encoded}, that is, the position just past
   *                   its control bytes.
   * @return The decoded base form bytes.
   */
  private static byte[] join3(byte[] form, int headEnd, int tailFrom, int tailEnd,
      byte[] encoded, int appendFrom) {
    final int tail = tailEnd - tailFrom;
    final int appended = encoded.length - appendFrom;
    final byte[] out = new byte[headEnd + tail + appended];
    System.arraycopy(form, 0, out, 0, headEnd);
    System.arraycopy(form, tailFrom, out, headEnd, tail);
    System.arraycopy(encoded, appendFrom, out, headEnd + tail, appended);
    return out;
  }

  /**
   * @param array The bytes to scan.
   * @param value The byte to look for.
   * @param from  The position to start scanning at.
   * @return The position of the first {@code value} at or after {@code from}, or {@code -1} if
   *         there is none.
   */
  private static int indexOf(byte[] array, byte value, int from) {
    for (int i = from; i < array.length; i++) {
      if (array[i] == value) {
        return i;
      }
    }
    return -1;
  }
}
