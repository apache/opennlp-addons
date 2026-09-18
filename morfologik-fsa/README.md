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

# morfologik-fsa

Optional OpenNLP components, published as `org.apache.opennlp.addons:morfologik-fsa`.

Build and run the module tests from the repository root:

```sh
mvn -pl morfologik-fsa -am verify -Dopennlp.forkCount=1
```

This research module pins core `3.0.0-SNAPSHOT`. Use exact versions when consuming
snapshots; Maven version ranges may select branch snapshots ahead of releases.

## Source

Migrated from [ai-pipestream/opennlp OPENNLP-XXXX-morfologik-fsa](https://github.com/ai-pipestream/opennlp/tree/4516cc6f1bc901629892def78f6f8a84d2216a57).
The original feature source and tests remain in that commit. Family contracts
live in this module; shared document and normalization contracts come from core.

The readers are provided by this module. The concurrency and tagless-dictionary
fixes to the existing core Morfologik tag dictionary remain on the core
OPENNLP-XXXX-morfologik-fsa branch; they are not duplicated in this artifact.
