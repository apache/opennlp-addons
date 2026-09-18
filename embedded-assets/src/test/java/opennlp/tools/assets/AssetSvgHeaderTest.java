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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Tests SVG root names and namespaces in encoded attachments. */
class AssetSvgHeaderTest {

  private static final String NAMESPACE = "http://www.w3.org/2000/svg";
  private static final String FORMAT = "svg";
  private static final String MEDIA_TYPE = "image/svg+xml";
  private static final String IMAGE = new String(AssetTestSupport.svg(), StandardCharsets.UTF_8);
  private static final List<String> TRANSPORTS = List.of("standard", "url", "mime64", "mime76", "uri");
  private static final int HEADER_LIMIT = 64 * 1024;

  private final AssetDetector detector = new CursorAssetDetector();

  /** {@return original SVG documents with declarations, prefixes and encoded characters} */
  private static Stream<Arguments> images() {
    return Stream.of(IMAGE,
        "<?xml version='1.0'?>" + IMAGE,
        "\ufeff" + IMAGE,
        " \r\n\t<!-- Survey icon --><?creator fieldwork?>" + IMAGE,
        "<drawing:svg xmlns:drawing='" + NAMESPACE + "'/>",
        "<svg1:svg xmlns:svg1='" + NAMESPACE + "'/>",
        "<svg:svg xmlns:svg='" + NAMESPACE + "'/>",
        "<\u03b1:svg xmlns:\u03b1='" + NAMESPACE + "'/>",
        "<svg data-note='&gt; &quot; &amp;' xmlns='" + NAMESPACE + "'/>",
        "<svg xmlns='http://www.w3.org/2000/sv&#x67;'/>",
        "<!DOCTYPE svg SYSTEM 'urn:opennlp:unused-dtd'>" + IMAGE)
        .flatMap(xml -> TRANSPORTS.stream().map(transport -> Arguments.of(xml, transport)));
  }

  /**
   * The root element's expanded name identifies SVG independently of its prefix.
   *
   * @param xml The original document.
   * @param transport The payload encoding.
   */
  @ParameterizedTest
  @MethodSource("images")
  void testImage(String xml, String transport) {
    AssetTestSupport.assertIdentified(detector, xml.getBytes(StandardCharsets.UTF_8),
        FORMAT, MEDIA_TYPE, transport);
  }

  /**
   * XML byte-order marks and declarations are handled by the XML reader.
   *
   * @param charset The declared encoding.
   */
  @ParameterizedTest
  @ValueSource(strings = {"UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE"})
  void testEncoding(String charset) {
    final String xml = "<?xml version='1.0' encoding='" + charset + "'?>" + IMAGE;
    final byte[] bytes = xml.getBytes(Charset.forName(charset));
    AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, "standard");
    AssetTestSupport.assertIdentified(detector, bytes, FORMAT, MEDIA_TYPE, "uri");
  }

  /**
   * Similar element names, wrong namespaces and malformed start tags do not identify SVG.
   *
   * @param xml The non-SVG or malformed document.
   */
  @ParameterizedTest
  @ValueSource(strings = {"<svg-notes>Survey</svg-notes>", "<svgNotes/>", "<svg/>",
      "<svg xmlns='urn:survey'/>", "<svg xmlns='http://www.w3.org/2000/svg-notes'/>",
      "<svg xmlns:other='http://www.w3.org/2000/svg'/>",
      "<svg xmlns='HTTP://www.w3.org/2000/svg'/>",
      "<svg xmlns='http://www.w3.org/2000/svg' xmlns='urn:survey'/>",
      "<svg xmlns='http://www.w3.org/2000/svg' a='1' a='2'/>",
      "<svg:notes xmlns:svg='http://www.w3.org/2000/svg'/>",
      "<svg:not-svg xmlns:svg='http://www.w3.org/2000/svg'/>",
      "<svg:svg/>", "<svg xmlns=http://www.w3.org/2000/svg/>",
      "<svg xmlns='http://www.w3.org/2000/svg'", "<svg", "<svg ",
      "<svg\u0000 xmlns='http://www.w3.org/2000/svg'/>",
      "<!-- <svg xmlns='http://www.w3.org/2000/svg'/> --><notes/>",
      "<notes><svg xmlns='http://www.w3.org/2000/svg'/></notes>",
      "<SVG xmlns='http://www.w3.org/2000/svg'/>"})
  void testNotSvg(String xml) {
    AssetTestSupport.assertUnrecognized(detector, xml.getBytes(StandardCharsets.UTF_8));
  }

  /** The namespace declaration may follow attributes longer than the default header probe. */
  @Test
  void testLaterNamespace() {
    final String xml = "<svg data-note='" + "survey ".repeat(1000) + "' xmlns='" + NAMESPACE + "'/>";
    AssetTestSupport.assertIdentified(detector, xml.getBytes(StandardCharsets.UTF_8),
        FORMAT, MEDIA_TYPE, "mime76");
  }

  /** The root start tag must fit within the bounded XML prefix. */
  @Test
  void testHeaderLimit() {
    final String prefix = "<svg data-note='";
    final String suffix = "' xmlns='" + NAMESPACE + "'/>";
    final String xml = prefix + "x".repeat(HEADER_LIMIT - prefix.length() - suffix.length()) + suffix;
    AssetTestSupport.assertIdentified(detector, xml.getBytes(StandardCharsets.UTF_8),
        FORMAT, MEDIA_TYPE, "uri");
    AssetTestSupport.assertUnrecognized(detector,
        (prefix + "x" + xml.substring(prefix.length())).getBytes(StandardCharsets.UTF_8));
  }

  /** XML lookahead cannot consume encoded data beyond the decoded-prefix budget. */
  @Test
  void testBoundedSourceRead() {
    final String xml = "<svg data-note='" + "x".repeat(HEADER_LIMIT * 2) + "' xmlns='" + NAMESPACE + "'/>";
    final String encoded = Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    final int encodedLimit = (HEADER_LIMIT * 8 + 5) / 6;
    final CharSequence guarded = new CharSequence() {
      /** {@inheritDoc} */
      @Override
      public int length() {
        return encoded.length();
      }

      /** {@inheritDoc} */
      @Override
      public char charAt(int index) {
        if (index >= encodedLimit) {
          throw new AssertionError("XML header read exceeded the decoded byte limit");
        }
        return encoded.charAt(index);
      }

      /** {@inheritDoc} */
      @Override
      public CharSequence subSequence(int start, int end) {
        throw new AssertionError("XML header reader must use the source without copying it");
      }
    };
    assertFalse(new SvgHeader().matches(guarded, 0, guarded.length()));
  }

  /** Recognition stops at the root start tag without checking image content. */
  @Test
  void testIncompleteImage() {
    final String xml = "<svg xmlns='" + NAMESPACE + "'><invalid";
    AssetTestSupport.assertIdentified(detector, xml.getBytes(StandardCharsets.UTF_8),
        FORMAT, MEDIA_TYPE, "standard");
  }

  /** Generic XML retains its existing signature-based classification. */
  @Test
  void testOtherXml() {
    final byte[] bytes = "<?xml version='1.0'?><notes/>".getBytes(StandardCharsets.UTF_8);
    AssetTestSupport.assertIdentified(detector, bytes, "xml", "application/xml", "uri");
  }

  /** An explicit media type does not assert that the root element was recognized. */
  @Test
  void testDeclaredType() {
    final byte[] bytes = "<svg-notes>Survey</svg-notes>".getBytes(StandardCharsets.UTF_8);
    final String text = "data:" + MEDIA_TYPE + ";base64," + Base64.getEncoder().encodeToString(bytes);
    final var assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals("svg+xml", assets.get(0).format());
    assertEquals(MEDIA_TYPE, assets.get(0).mediaType());
    assertArrayEquals(bytes, assets.get(0).decode(text));
  }

  /**
   * DTD and stylesheet references do not trigger network requests.
   *
   * @throws Exception If the local HTTP fixture cannot be started or stopped.
   */
  @Test
  void testNoExternalRequests() throws Exception {
    final AtomicInteger requests = new AtomicInteger();
    try (var executor = Executors.newSingleThreadExecutor(); var server = new ServerSocket()) {
      server.bind(new InetSocketAddress("127.0.0.1", 0));
      final var listener = executor.submit(() -> {
        try (Socket connection = server.accept()) {
          requests.incrementAndGet();
          connection.getOutputStream().write(
              "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
          if (!server.isClosed()) {
            throw new UncheckedIOException(e);
          }
        }
      });
      final String url = "http://127.0.0.1:" + server.getLocalPort() + "/unused.dtd";
      final String xml = "<?xml-stylesheet href='" + url + "'?>"
          + "<!DOCTYPE svg SYSTEM '" + url + "'>" + IMAGE;
      AssetTestSupport.assertIdentified(detector, xml.getBytes(StandardCharsets.UTF_8),
          FORMAT, MEDIA_TYPE, "uri");
      server.close();
      listener.get(5, TimeUnit.SECONDS);
      assertEquals(0, requests.get());
    }
  }

  /**
   * External declarations cannot supply the namespace used for recognition.
   *
   * @param directory The temporary fixture directory.
   * @throws IOException If the original DTD fixture cannot be written.
   */
  @Test
  void testExternalNamespace(@TempDir Path directory) throws IOException {
    final Path dtd = directory.resolve("namespace.dtd");
    Files.writeString(dtd, "<!ATTLIST svg xmlns CDATA '" + NAMESPACE + "'>");
    final String xml = "<!DOCTYPE svg SYSTEM '" + dtd.toUri() + "'><svg/>";
    AssetTestSupport.assertUnrecognized(detector, xml.getBytes(StandardCharsets.UTF_8));
  }

  /** Internal DTD entities are not expanded into namespace declarations. */
  @Test
  void testInternalNamespaceEntity() {
    final String xml = "<!DOCTYPE svg [<!ENTITY ns '" + NAMESPACE + "'>]>"
        + "<svg xmlns='&ns;'/>";
    AssetTestSupport.assertUnrecognized(detector, xml.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * A shared detector uses independent XML readers for concurrent requests.
   *
   * @throws Exception If a worker fails or does not complete.
   */
  @Test
  void testConcurrentDetection() throws Exception {
    try (var executor = Executors.newFixedThreadPool(4)) {
      final var results = IntStream.range(0, 32).mapToObj(index -> executor.submit(() -> {
        final String xml = "<!-- request " + index + " -->" + IMAGE;
        AssetTestSupport.assertIdentified(detector, xml.getBytes(StandardCharsets.UTF_8),
            FORMAT, MEDIA_TYPE, "uri");
        AssetTestSupport.assertUnrecognized(detector,
            "<svg xmlns='urn:not-an-image'/>".getBytes(StandardCharsets.UTF_8));
      })).toList();
      for (final var result : results) {
        result.get(5, TimeUnit.SECONDS);
      }
    }
  }

  /** Tests the manual's original circle attachment. */
  @Test
  void testManualExample() {
    final String text = "data:;base64," + Base64.getEncoder().encodeToString(
        IMAGE.getBytes(StandardCharsets.UTF_8));
    final var assets = detector.detect(text);
    assertEquals(1, assets.size());
    assertEquals(FORMAT, assets.get(0).format());
    assertEquals(MEDIA_TYPE, assets.get(0).mediaType());
    assertEquals(IMAGE, new String(assets.get(0).decode(text), StandardCharsets.UTF_8));
  }
}
