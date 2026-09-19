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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks header validation independently of the public reference array. */
@Isolated
class FsaHeaderTest {

  /** Null byte arrays follow the public argument-validation contract. */
  @Test
  void testNullHeader() {
    assertThrows(IllegalArgumentException.class, () -> FsaSequenceReader.requireFsaHeader(null));
  }

  /** Mutating the reference array cannot change the accepted file signature. */
  @Test
  void testReferenceArrayCannotChangeValidation() {
    final byte[] original = FsaSequenceReader.MAGIC.clone();
    try {
      FsaSequenceReader.MAGIC[0] = 'x';
      final byte[] valid = FsaTestData.singleSequence(new byte[] {'a'});
      assertDoesNotThrow(() -> FsaSequenceReader.read(new ByteArrayInputStream(valid)));
      valid[0] = 'x';
      assertThrows(IOException.class, () -> FsaSequenceReader.read(new ByteArrayInputStream(valid)));
    } finally {
      System.arraycopy(original, 0, FsaSequenceReader.MAGIC, 0, original.length);
    }
  }
}
