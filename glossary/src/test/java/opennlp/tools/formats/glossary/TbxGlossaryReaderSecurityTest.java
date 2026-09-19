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

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.glossary.GlossaryEntry;
import opennlp.tools.util.InvalidFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks rejection of external entity declarations in TBX input. */
public class TbxGlossaryReaderSecurityTest {

  /**
   * Rejects external entity declarations, including references inside nonempty terms.
   *
   * @param term The term containing the external reference.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "before &external; after", "prefix&external;", "&external;suffix",
      "<hi>&external;</hi>suffix", "term without a reference"
  })
  void testExternalEntityInsideNonemptyTerm(String term) {
    final String xml = "<?xml version=\"1.0\"?>"
        + "<!DOCTYPE martif [<!ENTITY external SYSTEM \"file:///nonexistent-entity\">]>"
        + "<martif><text><body><termEntry id=\"c1\"><langSet xml:lang=\"en\">"
        + "<tig><term>" + term + "</term></tig>"
        + "</langSet></termEntry></body></text></martif>";
    assertThrows(InvalidFormatException.class, () -> new TbxGlossaryReader("en")
        .read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
  }

  /**
   * Accepts an external DTD declaration without issuing an HTTP request.
   *
   * @throws Exception If the local test server or input fails.
   */
  @Test
  @Timeout(10)
  void testExternalDtdIsNotFetched() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      final var requests = Executors.newSingleThreadExecutor();
      try {
        final var connected = requests.submit(() -> {
          try (Socket client = server.accept()) {
            client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
            return true;
          } catch (SocketException e) {
            if (server.isClosed()) {
              return false;
            }
            throw e;
          }
        });
        final String xml = "<!DOCTYPE martif SYSTEM \"http://127.0.0.1:"
            + server.getLocalPort() + "/terms.dtd\">"
            + "<martif><text><body><termEntry id=\"Q1\"><langSet xml:lang=\"en\">"
            + "<tig><term>term</term></tig></langSet></termEntry></body></text></martif>";
        assertEquals(List.of(new GlossaryEntry("Q1", "term")), new TbxGlossaryReader("en")
            .read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        server.close();
        assertFalse(connected.get(5, TimeUnit.SECONDS));
      } finally {
        server.close();
        requests.shutdownNow();
      }
    }
  }

  /** Rejects malformed UTF-8 as invalid XML content, not an input transport failure. */
  @Test
  void testMalformedUtf8() {
    final byte[] prefix = "<martif><text><body>".getBytes(StandardCharsets.UTF_8);
    final byte[] content = new byte[prefix.length + 2];
    System.arraycopy(prefix, 0, content, 0, prefix.length);
    content[prefix.length] = (byte) 0xC3;
    content[prefix.length + 1] = 0x28;
    assertThrows(InvalidFormatException.class, () -> new TbxGlossaryReader("en")
        .read(new ByteArrayInputStream(content)));
  }
}
