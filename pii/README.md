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

# pii

Optional OpenNLP components, published as `org.apache.opennlp.addons:pii`.

Build and run the module tests from the repository root:

```sh
mvn -pl pii -am verify -Dopennlp.forkCount=1
```

This research module pins core `3.0.0-SNAPSHOT`. Use exact versions when consuming
snapshots; Maven version ranges may select branch snapshots ahead of releases.

## Reference data

The Bash commands in `dev/` use JDK 21 source launch mode. They need no Python
interpreter or additional Java libraries. Offline input environment variables and
`--check` retain the documented behavior. Generated Java tables are unchanged by
the migration. The generator sources and their tests live under `pii/src/test/java`.

Run the generator validation and command tests with:

```sh
mvn -pl pii -am test -Dopennlp.forkCount=1 \
  '-Dtest=ReferenceDataGenerator*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

## Source

Migrated from [ai-pipestream/opennlp OPENNLP-XXXX-pii](https://github.com/ai-pipestream/opennlp/tree/306c4be8f270bcdceb91feaa0133e76d30ca1c4f).
All detector, masking, pseudonymization and audit classes and their existing tests
are preserved. Only the data-generation tooling was rewritten in Java.
