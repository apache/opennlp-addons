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

# Building Against a Local Core Snapshot

An add-on normally uses the OpenNLP version selected by the root
`opennlp.version` property. On this branch, most modules use OpenNLP
`3.0.0-M5`. The `subword-addon` module temporarily overrides the property with
`3.0.0-SNAPSHOT` because its `SubwordTokenizer` contract is still under review
in OPENNLP-1885.

Use a local core build only when the required API is absent from the current
OpenNLP release and Apache snapshot.

## Use an isolated Maven repository

An isolated local repository prevents an experimental core build from
replacing the regular OpenNLP snapshot in the default Maven cache. Set one
absolute path and use it for both builds:

```bash
export OPENNLP_FEATURE_M2=/absolute/path/to/opennlp-feature-m2
```

Build the exact OpenNLP feature branch:

```bash
git clone https://github.com/apache/opennlp.git
cd opennlp
git fetch origin pull/1165/head:opennlp-1885
git switch opennlp-1885
./mvnw install -DskipTests -Drat.skip=true -Dopennlp.forkCount=1 \
  -Dmaven.repo.local="$OPENNLP_FEATURE_M2"
```

Use the same repository when verifying the add-on:

```bash
cd ../opennlp-addons
mvn -pl subword-addon -am verify \
  -Dmaven.repo.local="$OPENNLP_FEATURE_M2"
```

This procedure does not change artifacts in the default Maven cache. Remove
the isolated directory when the local core build is no longer needed.

## Use the published Apache snapshot

After the core PR merges and its snapshot is published, verify the module with
a fresh dependency lookup:

```bash
mvn -pl subword-addon -am verify -U
```

Keep the module's `opennlp.version` override while the add-ons root still
selects `3.0.0-M5`. Remove the override after the root selects an OpenNLP
version that contains the subword API.
