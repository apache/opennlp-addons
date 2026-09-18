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
 * Validates {@link CFSA2Reader} against a ground-truth automaton. The fixture below was generated
 * once by morfologik's {@code CFSA2Serializer} from the words {@code {cat, cats, do, dog, dogs}}
 * and committed as the expected encoding; the reader must recover exactly those sequences.
 */
public class CFSA2ReaderTest {

  private static final String FIXTURE_BASE64 = "XGZzYcYABwgAdHNvZ2RjYUBeAwYKxePkYgDHYQg=";

  private static final List<String> EXPECTED = List.of("cat", "cats", "do", "dog", "dogs");

  private static ByteArrayInputStream fixture() {
    return new ByteArrayInputStream(Base64.getDecoder().decode(FIXTURE_BASE64));
  }

  private static List<String> sequences(FsaSequenceReader reader) {
    final List<String> out = new ArrayList<>();
    reader.forEachSequence(bytes -> out.add(new String(bytes, StandardCharsets.UTF_8)));
    return out;
  }

  /** Every accepted sequence is recovered, in the automaton's stored lexicographic order. */
  @Test
  void testEnumeratesAllAcceptedSequences() throws IOException {
    Assertions.assertEquals(EXPECTED, sequences(CFSA2Reader.read(fixture())));
  }

  /** The format-agnostic dispatcher recognizes and reads CFSA2 by its version byte. */
  @Test
  void testDispatcherReadsCfsa2() throws IOException {
    Assertions.assertEquals(EXPECTED, sequences(FsaSequenceReader.read(fixture())));
  }

  /** A stream that is not an FSA automaton fails loudly. */
  @Test
  void testRejectsNonFsaMagic() {
    Assertions.assertThrows(IOException.class, () -> CFSA2Reader.read(
        new ByteArrayInputStream("not an fsa header".getBytes(StandardCharsets.UTF_8))));
  }

  /** A block cut short after a valid magic and version is reported as truncated. */
  @Test
  void testRejectsTruncatedHeader() {
    final byte[] truncated = Arrays.copyOf(Base64.getDecoder().decode(FIXTURE_BASE64), 6);
    final IOException e = Assertions.assertThrows(IOException.class,
        () -> CFSA2Reader.read(new ByteArrayInputStream(truncated)));
    Assertions.assertTrue(e.getMessage().startsWith("truncated CFSA2 header"), e.getMessage());
  }

  /** An FSA of a different version than CFSA2 fails loudly rather than misreading. */
  @Test
  void testRejectsUnsupportedVersion() {
    final byte[] altered = Base64.getDecoder().decode(FIXTURE_BASE64);
    altered[4] = 0x05;
    Assertions.assertThrows(IOException.class,
        () -> CFSA2Reader.read(new ByteArrayInputStream(altered)));
  }

  /** A null stream is rejected at the boundary. */
  @Test
  void testNullStreamRejected() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> CFSA2Reader.read(null));
  }

  /** A null action is rejected at the boundary. */
  @Test
  void testNullActionRejected() throws IOException {
    final CFSA2Reader reader = CFSA2Reader.read(fixture());
    Assertions.assertThrows(IllegalArgumentException.class, () -> reader.forEachSequence(null));
  }
}
