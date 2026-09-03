<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# SentencePiece Subword Add-on

SentencePiece model inference in pure Java, implementing the
`SubwordTokenizer` contract from `opennlp-api` (package
`opennlp.tools.tokenize`). Every produced `SubwordPiece` includes the piece
string in the model's normalized form, its vocabulary id, and the exact UTF-16
span in the caller's original text.

The shared interface and offset contract are part of OpenNLP core. Model
loading, SentencePiece normalization, and unigram and byte-pair inference are
implemented by this add-on.

## Dependency

```xml
<dependency>
  <groupId>org.apache.opennlp.addons</groupId>
  <artifactId>subword-addon</artifactId>
  <version>${opennlp-addons.version}</version>
</dependency>
```

## Usage

`SentencePieceTokenizer` (package `opennlp.subword.sentencepiece`) runs a
trained SentencePiece `.model` file purely in Java, with no native library.
The file contains the vocabulary with piece scores and types, the segmentation
algorithm (unigram language model or byte-pair encoding), and the text
normalizer the model was trained with. The resulting instance is immutable and
thread-safe. No model is bundled.

```java
SentencePieceTokenizer tokenizer = SentencePieceTokenizer.load(Path.of("spiece.model"));

for (SubwordPiece piece : tokenizer.encode("Subword pieces keep their original offsets.")) {
  System.out.println(piece.piece() + " -> id " + piece.id()
      + ", original text [" + piece.start() + ", " + piece.end() + ")");
}

int[] ids = tokenizer.encodeToIds("Ready for the embedding layer.");
```

`SentencePieceUsageExampleTest` asserts the load-and-encode workflow shown
here. The vocabulary can be inspected through `vocabularySize`, `idToPiece`,
`pieceToId`, and `score`, and the `algorithm` method reports whether the model
uses the unigram or the byte-pair encoding algorithm.

The model normalizer is also exposed directly: `SentencePieceTokenizer`
implements `OffsetAwareNormalizer`, so `normalize` applies the model's
normalization rules to arbitrary text and `normalizeAligned` additionally
returns the character alignment. Other processing can therefore use the same
normalized text and source alignment.

## Offset behavior

Every non-empty span indexes the input Java `String`, before model
normalization. A model can replace, remove, or combine characters without
breaking that source mapping. Control pieces and continuation bytes that add
no source text use empty spans.

## Verification

Test fixtures compare pieces, ids, spans, and normalized text with output from the reference
[sentencepiece](https://github.com/google/sentencepiece) package rather than
the Java code under test. `SentencePieceAlignmentTest` checks the span rules,
including supplementary-plane characters, collapsed whitespace, and ligature
replacement, and `SentencePieceParityTest` checks pieces, ids, and spans
against the fixtures. The fixture guide in
`subword-addon/src/test/resources/opennlp/subword/sentencepiece/README.md`
documents how the bundled test models were created and how to regenerate and
validate them, including against published models
(`-Dopennlp.subword.eval.dir`).

## Building

This module builds against `opennlp-api:3.0.0-SNAPSHOT` (the
`SubwordTokenizer` contract is not in the 3.0.0-M5 release). Until the core
contract is available in Apache snapshots, follow the
[local core snapshot procedure](building-snapshot.html).
