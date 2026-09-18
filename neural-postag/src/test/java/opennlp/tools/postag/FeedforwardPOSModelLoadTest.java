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

package opennlp.tools.postag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the fail-loud loading contract of {@link FeedforwardPOSModel#load(InputStream)}
 * on streams that begin with a valid format marker but continue with corrupt length
 * fields: a negative or implausibly large count must surface as an {@link IOException}
 * naming the field, never as a {@link NegativeArraySizeException} or an
 * {@link OutOfMemoryError} from allocating whatever the garbage bytes claim.
 */
public class FeedforwardPOSModelLoadTest {

  /** Writes the body of a corrupt model stream after the format marker. */
  private interface Body {
    void write(DataOutputStream data) throws IOException;
  }

  /**
   * Builds an in-memory stream carrying the given format marker and body.
   *
   * @param magic The format marker to head the stream with.
   * @param body The bytes to follow the marker.
   * @return The assembled stream. Never {@code null}.
   * @throws IOException Thrown if the in-memory assembly fails.
   */
  private static InputStream stream(String magic, Body body) throws IOException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final DataOutputStream data = new DataOutputStream(bytes);
    data.writeUTF(magic);
    body.write(data);
    data.flush();
    return new ByteArrayInputStream(bytes.toByteArray());
  }

  /**
   * The base model fields in format order, as the number of zero ints writing each
   * empty field takes: the four vocabulary sizes, the tag count, the embedding size,
   * the embedding matrix, the hidden weights, the hidden bias, the output weights,
   * and the output bias. An empty matrix takes two zeros, rows and columns.
   */
  private static final int[] INTS_PER_FIELD = {1, 1, 1, 1, 1, 1, 2, 2, 1, 2, 1};

  /**
   * Writes an empty base model up to but excluding the field under test, so a test
   * can place its corrupt length exactly where the format expects the next field.
   *
   * @param data The stream to write to.
   * @param fields The number of leading fields of {@link #INTS_PER_FIELD} to write.
   * @throws IOException Thrown if the in-memory assembly fails.
   */
  private static void writeEmptyBase(DataOutputStream data, int fields) throws IOException {
    for (int field = 0; field < fields; field++) {
      for (int i = 0; i < INTS_PER_FIELD[field]; i++) {
        data.writeInt(0);
      }
    }
  }

  /**
   * Asserts that loading the stream fails with an {@link IOException} whose message
   * names the corrupt field, so garbage after a valid marker can never escape as an
   * unchecked throwable.
   *
   * @param in The corrupt stream to load.
   * @param field The field name the error message must contain.
   */
  private static void assertRejected(InputStream in, String field) {
    final IOException e =
        assertThrows(IOException.class, () -> FeedforwardPOSModel.load(in));
    assertTrue(e.getMessage() != null && e.getMessage().contains(field),
        "the error must name the corrupt field '" + field + "': " + e.getMessage());
  }

  /** A negative vocabulary count must be a format error, not an unchecked failure. */
  @Test
  void testNegativeVocabularyCountIsAFormatError() throws IOException {
    assertRejected(stream("ONLP-FFPT-1", data -> data.writeInt(-1)),
        "word vocabulary");
  }

  /** A vocabulary count far beyond any sane model must be a format error. */
  @Test
  void testHugeVocabularyCountIsAFormatError() throws IOException {
    assertRejected(stream("ONLP-FFPT-1", data -> data.writeInt(Integer.MAX_VALUE)),
        "word vocabulary");
  }

  /** A negative tag count must be a format error, not a negative array size. */
  @Test
  void testNegativeTagCountIsAFormatError() throws IOException {
    assertRejected(stream("ONLP-FFPT-1", data -> {
      writeEmptyBase(data, 4);
      data.writeInt(-3);
    }), "tag count");
  }

  /** A negative embedding size must be rejected instead of loading a broken model. */
  @Test
  void testNegativeEmbeddingSizeIsAFormatError() throws IOException {
    assertRejected(stream("ONLP-FFPT-1", data -> {
      writeEmptyBase(data, 5);
      data.writeInt(-4);
    }), "embedding size");
  }

  /** A matrix row count far beyond any sane model must not be allocated. */
  @Test
  void testHugeMatrixRowCountIsAFormatError() throws IOException {
    assertRejected(stream("ONLP-FFPT-1", data -> {
      writeEmptyBase(data, 6);
      data.writeInt(Integer.MAX_VALUE);
      data.writeInt(16);
    }), "embedding matrix");
  }

  /** A negative matrix column count must be a format error, not a negative array size. */
  @Test
  void testNegativeMatrixColumnCountIsAFormatError() throws IOException {
    assertRejected(stream("ONLP-FFPT-1", data -> {
      writeEmptyBase(data, 6);
      data.writeInt(1);
      data.writeInt(-2);
    }), "embedding matrix");
  }

  /**
   * Matrix dimensions that pass individually but multiply to an implausible element
   * count must be rejected before any row is allocated.
   */
  @Test
  void testOverflowingMatrixElementCountIsAFormatError() throws IOException {
    assertRejected(stream("ONLP-FFPT-1", data -> {
      writeEmptyBase(data, 6);
      data.writeInt(1 << 17);
      data.writeInt(1 << 17);
    }), "embedding matrix");
  }

  /** A negative vector length must be a format error, not a negative array size. */
  @Test
  void testNegativeVectorLengthIsAFormatError() throws IOException {
    assertRejected(stream("ONLP-FFPT-1", data -> {
      writeEmptyBase(data, 8);
      data.writeInt(-7);
    }), "hidden bias");
  }

  /** The pretrained block of the versioned format must guard its size field alike. */
  @Test
  void testNegativePretrainedSizeIsAFormatError() throws IOException {
    assertRejected(stream("ONLP-FFPT-2", data -> {
      writeEmptyBase(data, 11);
      data.writeInt(-5);
    }), "pretrained vector size");
  }
}
