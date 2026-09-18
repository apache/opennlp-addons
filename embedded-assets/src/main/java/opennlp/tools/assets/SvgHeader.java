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

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Identifies an SVG root element without resolving external XML resources. */
final class SvgHeader {

  static final String FORMAT_NAME = "svg";
  static final KnownMagics.Format FORMAT = new KnownMagics.Format(FORMAT_NAME, "image/svg+xml");
  private static final String NAMESPACE = "http://www.w3.org/2000/svg";
  private static final int MAX_HEADER_BYTES = 64 * 1024;

  /** Creates a stateless header reader. */
  SvgHeader() {
  }

  /**
   * Checks the first element within a 64 KiB decoded prefix. Each call owns its XML reader.
   * DTD processing and external entities are disabled; image content is not validated.
   *
   * @param text The source text.
   * @param start The inclusive validated payload start.
   * @param end The exclusive validated payload end.
   * @return Whether the root is svg in the SVG namespace.
   */
  boolean matches(CharSequence text, int start, int end) {
    final XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    factory.setProperty(XMLInputFactory.IS_VALIDATING, false);
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setProperty(XMLConstants.USE_CATALOG, false);
    factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
      throw new XMLStreamException("External XML resources are disabled");
    });
    try {
      final XMLStreamReader reader = factory.createXMLStreamReader(
          new Base64PayloadInputStream(text, start, end, MAX_HEADER_BYTES));
      try {
        while (reader.hasNext()) {
          if (reader.next() == XMLStreamConstants.START_ELEMENT) {
            return FORMAT_NAME.equals(reader.getLocalName()) && NAMESPACE.equals(reader.getNamespaceURI());
          }
        }
        return false;
      } finally {
        reader.close();
      }
    } catch (XMLStreamException e) {
      return false;
    }
  }

  /**
   * Selects XML-like headers, including whitespace and UTF-8/UTF-16 byte-order marks.
   *
   * @param header The decoded prefix.
   * @return Whether XML root inspection is applicable.
   */
  boolean isCandidate(byte[] header) {
    if (header.length == 0) {
      return false;
    }
    return switch (header[0] & 0xff) {
      case '<', ' ', '\t', '\r', '\n' -> true;
      case 0 -> header.length >= 2 && header[1] == '<';
      case 0xef -> header.length >= 3 && (header[1] & 0xff) == 0xbb && (header[2] & 0xff) == 0xbf;
      case 0xfe -> header.length >= 2 && (header[1] & 0xff) == 0xff;
      case 0xff -> header.length >= 2 && (header[1] & 0xff) == 0xfe;
      default -> false;
    };
  }
}
