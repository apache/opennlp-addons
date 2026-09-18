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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Validates {@link FSA5Reader} against a ground-truth automaton generated once by morfologik's
 * {@code FSA5Serializer} from the words {@code {cat, cats, do, dog, dogs}}. Also covers the
 * {@link FsaSequenceReader#read(java.io.InputStream)} dispatcher, which has no test class of its
 * own.
 */
public class FSA5ReaderTest {

  private static final String FIXTURE_BASE64 = "XGZzYQVfKwEAAF4GY3BkBm8HZwdzA2EGdGM=";

  private static final List<String> EXPECTED = List.of("cat", "cats", "do", "dog", "dogs");

  private static ByteArrayInputStream fixture() {
    return new ByteArrayInputStream(Base64.getDecoder().decode(FIXTURE_BASE64));
  }

  private static List<String> sequences(FsaSequenceReader reader) {
    final List<String> out = new ArrayList<>();
    reader.forEachSequence(bytes -> out.add(new String(bytes, StandardCharsets.UTF_8)));
    return out;
  }

  /** Every accepted sequence is recovered, in stored lexicographic order. */
  @Test
  void testEnumeratesAllAcceptedSequences() throws IOException {
    Assertions.assertEquals(EXPECTED, sequences(FSA5Reader.read(fixture())));
  }

  /** The format-agnostic dispatcher recognizes and reads FSA5 by its version byte. */
  @Test
  void testDispatcherReadsFsa5() throws IOException {
    Assertions.assertEquals(EXPECTED, sequences(FsaSequenceReader.read(fixture())));
  }

  /** A block cut short after a valid magic and version is reported as truncated. */
  @Test
  void testRejectsTruncatedHeader() {
    final byte[] truncated = Arrays.copyOf(Base64.getDecoder().decode(FIXTURE_BASE64), 6);
    final IOException e = Assertions.assertThrows(IOException.class,
        () -> FSA5Reader.read(new ByteArrayInputStream(truncated)));
    Assertions.assertTrue(e.getMessage().startsWith("truncated FSA5 header"), e.getMessage());
  }

  /** A different FSA version fails loudly rather than misreading. */
  @Test
  void testRejectsUnsupportedVersion() {
    final byte[] altered = Base64.getDecoder().decode(FIXTURE_BASE64);
    altered[4] = (byte) 0x99;
    Assertions.assertThrows(IOException.class,
        () -> FSA5Reader.read(new ByteArrayInputStream(altered)));
  }

  /** The dispatcher rejects a version it cannot read rather than guessing a format. */
  @Test
  void testDispatcherRejectsUnsupportedVersion() {
    final byte[] altered = Base64.getDecoder().decode(FIXTURE_BASE64);
    altered[4] = (byte) 0x99;
    Assertions.assertThrows(IOException.class,
        () -> FsaSequenceReader.read(new ByteArrayInputStream(altered)));
  }

  /** The dispatcher rejects a stream that is not an FSA automaton at all. */
  @Test
  void testDispatcherRejectsNonFsaMagic() {
    Assertions.assertThrows(IOException.class, () -> FsaSequenceReader.read(
        new ByteArrayInputStream("not an fsa header".getBytes(StandardCharsets.UTF_8))));
  }

  /** A null stream is rejected at the boundary, by the reader and by the dispatcher alike. */
  @Test
  void testNullStreamRejected() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> FSA5Reader.read(null));
    Assertions.assertThrows(IllegalArgumentException.class, () -> FsaSequenceReader.read(null));
  }

  /** A null action is rejected at the boundary. */
  @Test
  void testNullActionRejected() throws IOException {
    final FSA5Reader reader = FSA5Reader.read(fixture());
    Assertions.assertThrows(IllegalArgumentException.class, () -> reader.forEachSequence(null));
  }
}
