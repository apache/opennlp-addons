<!--
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
-->

# Coreference document layer

`CorefAnnotator` resolves mentions that refer to the same entity and records them in a
typed `Document` layer. It can run as a deterministic resolver or with a trained
`CorefModel` antecedent ranker.

## Human definition

It connects phrases such as "Alice", "she", and "the engineer" when they refer to the
same person. Each result retains its exact span in the original text, so applications
can group, highlight, or inspect every mention.

## Implementation

- Mention candidates come from entity, pronoun, chunk, and parser phrase layers.
- The deterministic resolver applies speaker, string, head, attribute, and pronoun
  rules in precision order.
- `CorefTrainer` trains pairwise or mention-ranking models from a
  `gold:opennlp:chains` layer.
- `ConlluCorefDocumentStream` reads gold chains from OntoGUM and CorefUD CoNLL-U data.
- Optional `WordVectors` and `TokenVectors` providers add lexical and contextual
  similarity features. `TokenVectorsDL` supplies contextual vectors from a BERT-style
  ONNX encoder. Each provider declares its vector dimension, and `CorefModel` records
  the contextual dimension required at inference.
- `CorefScorer` reports MUC, B-cubed, CEAF-m, CEAF-e, mention detection, and the CoNLL
  average.

## Recorded evaluation

These measurements predate the addon migration. They have not been rerun as part
of the relocation; the original evaluation harness and model configuration remain.

The OntoGUM runs use gold sentences, tokens, and POS tags, with predicted entities and
chunks. They are not a direct comparison with systems evaluated on fully predicted
input. The 32-document splits include two Reddit documents whose text GUM redacts;
`opennlp.coref.skip.redacted` omits them when a text-complete score is needed.

| Resolver | OntoGUM test CoNLL | GAP development F1 | GAP test F1 |
| --- | ---: | ---: | ---: |
| Rules | 46.8 | 24.3 | 25.7 |
| Ranker trained on all OntoGUM training documents | 50.0 | 45.1 | 45.6 |
| Ranker trained on 59 CC BY OntoGUM training documents | 49.4 | 46.1 | 45.5 |

The GAP runs use predicted sentence, token, POS, entity, and chunk layers. The GAP
paper reports 50.5 development F1 for Stanford dcoref and 41.5 for its random baseline.
The CC BY ranker is above the random baseline but does not yet match dcoref on this
fully predicted input.

The proposed distributable model uses only the 59 CC BY OntoGUM training documents.
The corpus annotations are CC BY 4.0; the evaluation harness keeps all corpus data and
models outside the repository.

## Prior art and products

- [Stanford CoreNLP dcoref](https://www-nlp.stanford.edu/software/dcoref.html)
- [Coreferee](https://github.com/richardpaulhudson/coreferee)
- [FastCoref](https://github.com/shon-otmazgin/fastcoref)

## Addon modules

- `org.apache.opennlp.addons:coref`: resolver, trainer, scorer, and vector contracts.
- `org.apache.opennlp.addons:coref-formats`: CoNLL-U gold-chain reader.
- `org.apache.opennlp.addons:coref-dl`: optional ONNX token-vector adapter.

Java package names are unchanged. The resolver and reader do not have an ONNX
runtime dependency. Core supplies Document, typed layers, standard NLP annotators,
and the shared deep-learning API used by the optional adapter.

## Remaining work

- Evaluate a nonlinear scorer for contextual span vectors.
- Prune near-zero ranker weights before publishing the model.
