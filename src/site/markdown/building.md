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

# Building from Source

## Requirements

- JDK 21 or newer
- Maven 3.3.9 or newer for the Java reactor
- Maven 3.6.3 or newer for manual generation

The repository does not include a Maven wrapper.

## Verify every module

Run from the repository root:

```bash
mvn verify
```

Build one module and its reactor dependencies with `-pl` and `-am`:

```bash
mvn -pl subword-addon -am verify
```

## Generate the manual

The manual uses Apache Maven Site Plugin 3.22.0, Doxia Markdown, and Apache
Maven Fluido Skin 2.1.0. These are build plugins and do not become application
dependencies.

Generate only the root documentation site:

```bash
mvn -N site
```

Open `target/site/index.html` in a browser. Use a clean build after changing
`src/site/site.xml` so navigation and resources are regenerated:

```bash
mvn -N clean site
```

## Contributing

Use the [OpenNLP contribution guide](https://github.com/apache/opennlp/blob/main/.github/CONTRIBUTING.md)
and discuss project-wide changes on the
[development mailing list](https://opennlp.apache.org/mailing-lists.html).

An add-on that requires an API from an unmerged OpenNLP branch also needs the
[local core snapshot procedure](building-snapshot.html).
