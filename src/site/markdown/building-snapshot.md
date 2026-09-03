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
`opennlp.version` property. A feature branch may temporarily override that
property when its required API is still under OpenNLP review.

Use a local core build only when the required API is absent from the current
OpenNLP release and Apache snapshot.

## Use an isolated Maven repository

An isolated local repository prevents an experimental core build from
replacing the regular OpenNLP snapshot in the default Maven cache. Set one
absolute path and use it for both builds:

```bash
export OPENNLP_FEATURE_M2=/absolute/path/to/opennlp-feature-m2
export OPENNLP_CORE_REF=pull/NUMBER/head
export OPENNLP_ADDON_MODULE=the-addon-module
```

Build the exact OpenNLP feature branch:

```bash
git clone https://github.com/apache/opennlp.git
cd opennlp
git fetch origin "$OPENNLP_CORE_REF"
git switch --detach FETCH_HEAD
./mvnw install -DskipTests -Drat.skip=true -Dopennlp.forkCount=1 \
  -Dmaven.repo.local="$OPENNLP_FEATURE_M2"
```

Use the same repository when verifying the add-on:

```bash
cd ../opennlp-addons
mvn -pl "$OPENNLP_ADDON_MODULE" -am verify \
  -Dmaven.repo.local="$OPENNLP_FEATURE_M2"
```

This procedure does not change artifacts in the default Maven cache. Remove
the isolated directory when the local core build is no longer needed.

## Return to the Apache snapshot

After the core PR merges and its snapshot is published, remove the module's
temporary `opennlp.version` override. Verify with a fresh dependency lookup:

```bash
mvn -pl "$OPENNLP_ADDON_MODULE" -am verify -U
```

The add-on PR should compile against the published Apache artifacts before it
is opened for review.
