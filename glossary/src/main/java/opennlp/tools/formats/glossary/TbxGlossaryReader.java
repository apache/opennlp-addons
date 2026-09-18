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
import java.io.CharConversionException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.events.EntityDeclaration;

import opennlp.tools.glossary.GlossaryEntry;
import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.StringUtil;
import opennlp.tools.util.model.UncloseableInputStream;

/**
 * Reads terms from TBX termbases (ISO 30042, TermBase eXchange). Supported elements
 * are recognized by local name in the ISO 30042 namespace or without a namespace: TBX&#160;2
 * ({@code termEntry}, {@code langSet}, {@code tig} or nested {@code ntig}) and the
 * TBX&#160;3 ({@code conceptEntry}, {@code langSec}, {@code termSec}).
 *
 * <p>The configured language tag matches an equal {@code xml:lang} or a tag with
 * additional subtags, ignoring case. For example, {@code en} selects {@code en-US},
 * but {@code en-GB} does not select {@code en-US}. Terms in matching language sections
 * become entries with the term entry's identifier. A language not present in the file
 * produces an empty list.</p>
 *
 * <p>Terms are read only through the document's {@code text/body}, concept, language,
 * and term containers. Header, back-matter, and metadata subtrees are ignored, including
 * any term-like elements they contain. Inline text inside a selected term is retained.
 * This reader does not validate the complete TBX schema.</p>
 *
 * <p>A {@code DOCTYPE} is accepted, with internal entity expansion enabled. External
 * DTD content is not loaded. External entity declarations, including unused ones,
 * produce {@link InvalidFormatException}. Entries without an unqualified {@code id} attribute,
 * blank terms in selected languages, and malformed XML also produce that exception.
 * Input I/O failures propagate as {@link IOException}.</p>
 *
 * <p>Configuration is immutable. Concurrent calls with separate streams are supported.</p>
 *
 * @see <a href="https://www.iso.org/standard/62510.html">ISO 30042 (TBX)</a>
 * @since 3.0.0
 */
public final class TbxGlossaryReader implements GlossaryReader {

  private static final String ENTITY_DECLARATIONS = "javax.xml.stream.entities";
  private static final String TBX_NAMESPACE = "urn:iso:std:iso:30042:ed-2";

  /** The structural levels used to locate terms without entering metadata subtrees. */
  private enum Scope {
    ROOT, TEXT, BODY, ENTRY, LANGUAGE, NESTED_TERM, TERM_CONTAINER, TERM
  }

  /** The lowercased BCP 47 tag selecting which language sections to read. */
  private final String language;

  /**
   * Builds a reader for one language of a termbase.
   *
   * @param languageTag The BCP 47 tag of the language to read, for example {@code en}
   *                    or {@code en-US}. Must not be {@code null} or blank. Matching
   *                    is case-insensitive and by subtag prefix.
   * @throws IllegalArgumentException Thrown if {@code languageTag} is {@code null}
   *         or blank.
   */
  public TbxGlossaryReader(String languageTag) {
    if (languageTag == null || StringUtil.isBlank(languageTag)) {
      throw new IllegalArgumentException("languageTag must not be null or blank");
    }
    this.language = StringUtil.toLowerCase(languageTag);
  }

  /** {@inheritDoc} */
  @Override
  public List<GlossaryEntry> read(InputStream in) throws IOException {
    if (in == null) {
      throw new IllegalArgumentException("in must not be null");
    }
    final List<GlossaryEntry> entries = new ArrayList<>();
    final XMLStreamReader xml;
    try {
      xml = createSecureFactory().createXMLStreamReader(new UncloseableInputStream(in));
    } catch (XMLStreamException e) {
      throw readFailure(e);
    }
    try {
      readEntries(xml, entries);
    } catch (XMLStreamException e) {
      throw readFailure(e);
    } finally {
      try {
        xml.close();
      } catch (XMLStreamException e) {
        // The XML parser does not own the supplied stream.
      }
    }
    return entries;
  }

  /**
   * Preserves input failures and reports XML parsing failures as invalid content.
   *
   * @param failure The parser failure.
   * @return The input exception or an invalid-content exception with the parser cause.
   */
  private IOException readFailure(XMLStreamException failure) {
    final Throwable cause = failure.getNestedException();
    if (cause instanceof IOException io && !(io instanceof CharConversionException)) {
      return io;
    }
    return new InvalidFormatException("malformed TBX content: " + failure.getMessage(), failure);
  }

  /**
   * Collects terms in matching language sections.
   *
   * @param xml The open stream reader positioned before the root.
   * @param entries The sink for entries in file order.
   * @throws XMLStreamException Thrown if the XML is malformed.
   * @throws InvalidFormatException Thrown if required entry data is missing or an
   *         external entity is declared.
   */
  private void readEntries(XMLStreamReader xml, List<GlossaryEntry> entries)
      throws XMLStreamException, InvalidFormatException {
    while (xml.hasNext()) {
      final int event = xml.next();
      if (event == XMLStreamConstants.DTD) {
        rejectExternalEntities(xml);
      } else if (event == XMLStreamConstants.START_ELEMENT) {
        final String name = xml.getLocalName();
        if ((!"martif".equals(name) && !"tbx".equals(name)) || !isTbxElement(xml)) {
          throw new InvalidFormatException(
              "expected a TBX martif or tbx root element, found: " + xml.getName());
        }
        readChildren(xml, Scope.ROOT, null, entries);
      }
    }
  }

  /**
   * Reads direct children of a supported structural container.
   *
   * @param xml The reader on the container's start element.
   * @param scope The container's structural level.
   * @param entryId The enclosing entry identifier, or {@code null} outside an entry.
   * @param entries The terms collected in file order.
   * @throws XMLStreamException Thrown if the XML is malformed.
   * @throws InvalidFormatException Thrown if an entry identifier or selected term is blank.
   */
  private void readChildren(XMLStreamReader xml, Scope scope, String entryId,
      List<GlossaryEntry> entries) throws XMLStreamException, InvalidFormatException {
    while (xml.hasNext()) {
      final int event = xml.next();
      if (event == XMLStreamConstants.END_ELEMENT) {
        return;
      }
      if (event == XMLStreamConstants.START_ELEMENT) {
        final Scope child = isTbxElement(xml) ? childScope(scope, xml.getLocalName()) : null;
        if (child == null || child == Scope.LANGUAGE && !matchesLanguage(
            xml.getAttributeValue(XMLConstants.XML_NS_URI, "lang"))) {
          skipElement(xml);
        } else if (child == Scope.TERM) {
          final String term = collectText(xml).trim();
          if (StringUtil.isBlank(term)) {
            throw new InvalidFormatException("blank term in entry \"" + entryId + "\"");
          }
          entries.add(new GlossaryEntry(entryId, term));
        } else {
          String childId = entryId;
          if (child == Scope.ENTRY) {
            childId = xml.getAttributeValue(XMLConstants.NULL_NS_URI, "id");
            if (childId == null || StringUtil.isBlank(childId)) {
              throw new InvalidFormatException("term entry must have a nonblank id attribute");
            }
          }
          readChildren(xml, child, childId, entries);
        }
      }
    }
  }

  /**
   * Distinguishes TBX elements from namespaced extension fields.
   *
   * @param xml The reader on a start element.
   * @return Whether the element uses a supported namespace or no namespace.
   */
  private boolean isTbxElement(XMLStreamReader xml) {
    final String namespace = xml.getNamespaceURI();
    return namespace == null || namespace.isEmpty() || TBX_NAMESPACE.equals(namespace);
  }

  /**
   * Identifies supported direct children at each TBX structural level.
   *
   * @param parent The enclosing structural level.
   * @param name The child's local name.
   * @return The child's level, or {@code null} for a subtree to ignore.
   */
  private Scope childScope(Scope parent, String name) {
    return switch (parent) {
      case ROOT -> "text".equals(name) ? Scope.TEXT : null;
      case TEXT -> "body".equals(name) ? Scope.BODY : null;
      case BODY -> "termEntry".equals(name) || "conceptEntry".equals(name) ? Scope.ENTRY : null;
      case ENTRY -> "langSet".equals(name) || "langSec".equals(name) ? Scope.LANGUAGE : null;
      case LANGUAGE -> switch (name) {
        case "tig", "termSec" -> Scope.TERM_CONTAINER;
        case "ntig" -> Scope.NESTED_TERM;
        default -> null;
      };
      case NESTED_TERM -> "termGrp".equals(name) ? Scope.TERM_CONTAINER : null;
      case TERM_CONTAINER -> "term".equals(name) ? Scope.TERM : null;
      case TERM -> null;
    };
  }

  /**
   * Skips an unrelated subtree without allocating its text.
   *
   * @param xml The reader on the subtree's start element.
   * @throws XMLStreamException Thrown if the XML is malformed.
   */
  private void skipElement(XMLStreamReader xml) throws XMLStreamException {
    int depth = 1;
    while (depth > 0) {
      final int event = xml.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        depth++;
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        depth--;
      }
    }
  }

  /**
   * Rejects external entity declarations before reading term content.
   *
   * @param xml The reader positioned on a DTD event.
   * @throws InvalidFormatException Thrown if an external entity is declared.
   */
  private void rejectExternalEntities(XMLStreamReader xml) throws InvalidFormatException {
    final Object declarations = xml.getProperty(ENTITY_DECLARATIONS);
    if (declarations instanceof List<?> entities) {
      for (Object entity : entities) {
        if (entity instanceof EntityDeclaration declaration
            && (declaration.getSystemId() != null || declaration.getPublicId() != null)) {
          throw new InvalidFormatException(
              "external entity declarations are not supported: " + declaration.getName());
        }
      }
    }
  }

  /**
   * Collects the text of the current element including nested inline markup, leaving
   * the reader on the element's end tag.
   *
   * @param xml The reader positioned on a start element.
   * @return The concatenated character data of the element and its descendants.
   * @throws XMLStreamException Thrown if the XML is malformed.
   */
  private String collectText(XMLStreamReader xml) throws XMLStreamException {
    final StringBuilder text = new StringBuilder();
    int depth = 1;
    while (depth > 0) {
      final int event = xml.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        depth++;
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        depth--;
      } else if (event == XMLStreamConstants.CHARACTERS
          || event == XMLStreamConstants.CDATA
          || event == XMLStreamConstants.ENTITY_REFERENCE) {
        text.append(xml.getText());
      }
    }
    return text.toString();
  }

  /**
   * Compares language tags by equality or subtag prefix, ignoring case.
   *
   * @param xmlLang The {@code xml:lang} value of the section, possibly {@code null}.
   * @return {@code true} if the section belongs to the configured language.
   */
  private boolean matchesLanguage(String xmlLang) {
    if (xmlLang == null) {
      return false;
    }
    final String folded = StringUtil.toLowerCase(xmlLang);
    return folded.equals(language) || folded.startsWith(language + "-");
  }

  /**
   * Configures internal entity expansion without external resource access.
   *
   * @return The hardened factory.
   */
  private XMLInputFactory createSecureFactory() {
    final XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.TRUE);
    factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
    factory.setXMLResolver((publicId, systemId, baseUri, namespace) ->
        new ByteArrayInputStream(new byte[0]));
    return factory;
  }
}
