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

# Apache OpenNLP Add-ons

[![Build Status](https://github.com/apache/opennlp-addons/workflows/Java%20CI/badge.svg)](https://github.com/apache/opennlp-addons/actions)
[![Contributors](https://img.shields.io/github/contributors/apache/opennlp-addons)](https://github.com/apache/opennlp-addons/graphs/contributors)
[![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/apache/opennlp-addons.svg)](https://github.com/apache/opennlp-addons/pulls)
[![Stack Overflow](https://img.shields.io/badge/stack%20overflow-opennlp-f1eefe.svg)](https://stackoverflow.com/questions/tagged/opennlp)

Apache OpenNLP Add-ons provides optional Java components that build on the
OpenNLP API. Each add-on is a separate Maven artifact, so applications select
only the integrations they use.

## Add-ons

| Artifact | Purpose |
| --- | --- |
| `geoentitylinker-addon` | Geographic entity detection and linking |
| `japanese-addon` | Features for Japanese text processing |
| `jwnl-addon` | WordNet access through extJWNL |
| `liblinear-addon` | LIBLINEAR integration |
| `modelbuilder-addon` | Utilities for building OpenNLP models |

## Using an add-on

Depend on the module artifact, not the parent `opennlp-addons` POM:

```xml
<dependency>
  <groupId>org.apache.opennlp.addons</groupId>
  <artifactId>jwnl-addon</artifactId>
  <version>${opennlp-addons.version}</version>
</dependency>
```

The current revamp work targets regular Maven Central releases. Until a 3.x
add-ons release is published, build the repository locally before using its
artifacts.

## Building

The build requires JDK 21 or newer and Maven 3.3.9 or newer:

```bash
mvn verify
```

Generate the Markdown-based manual with Maven 3.6.3 or newer:

```bash
mvn -N site
```

The generated manual starts at `target/site/index.html`. Its Markdown sources
are under [`src/site/markdown`](src/site/markdown/index.md).

## Project links

- [OpenNLP website](https://opennlp.apache.org/)
- [OpenNLP documentation](https://opennlp.apache.org/docs/)
- [OpenNLP models](https://opennlp.apache.org/models.html)
- [Mailing lists](https://opennlp.apache.org/mailing-lists.html)
- [Contributing guide](https://github.com/apache/opennlp/blob/main/.github/CONTRIBUTING.md)
