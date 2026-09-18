<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# neural-postag

Optional OpenNLP components, published as `org.apache.opennlp.addons:neural-postag`.

Build and run the module tests from the repository root:

```sh
mvn -pl neural-postag -am verify -Dopennlp.forkCount=1
```

This research module pins core `3.0.0-SNAPSHOT`. Use exact versions when consuming
snapshots; Maven version ranges may select branch snapshots ahead of releases.

## Source

Migrated from [ai-pipestream/opennlp OPENNLP-XXXX-ff-postagger](https://github.com/ai-pipestream/opennlp/tree/ebfb2ec886c37007a9a2b769f52911e3470c4deb).
The original feature source and tests remain in that commit. Family contracts
live in this module; shared document and normalization contracts come from core.

The parser constructors accepting a supplied POSTagger are retained in core.
Training and direct tagging through this module use the current core API.

BiLSTM source: ai-pipestream/opennlp 1983e1be51c9abc5fe28836da21e2602c3fd7291.
The opt-in CoNLL-U evaluator is included in this module. Run it with
-Dopennlp.postag.ud.dir pointing to train.conllu and test.conllu.
Optional vector preparation is in dev/fetch-fasttext-vectors.sh.
The historical 96.294 UPOS result is not an evaluation of this migrated code.
The research accuracy target remains 97.0 UPOS; no new accuracy claim is made.
