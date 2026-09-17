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

Welcome to Apache OpenNLP Add-ons!
===========

[![GitHub license](https://img.shields.io/badge/license-Apache%202-blue.svg)](https://raw.githubusercontent.com/apache/opennlp-addons/main/LICENSE)
[![Build Status](https://github.com/apache/opennlp-addons/workflows/Java%20CI/badge.svg)](https://github.com/apache/opennlp-addons/actions)
[![Contributors](https://img.shields.io/github/contributors/apache/opennlp-addons)](https://github.com/apache/opennlp-addons/graphs/contributors)
[![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/apache/opennlp-addons.svg)](https://github.com/apache/opennlp-addons/pulls)

Apache OpenNLP Add-ons is a set of optional components built on the Apache OpenNLP API.

Each add-on is published as its own Maven artifact, so an application can take a single integration without taking the dependencies of the others. Shared contracts stay in OpenNLP core; an implementation belongs here when it needs a distinct model format, external data, or an optional third-party library.

## Useful Links

- [OpenNLP home page](https://opennlp.apache.org/)
- [OpenNLP documentation](https://opennlp.apache.org/docs/)
- [Mailing lists](https://opennlp.apache.org/mailing-lists.html)
- [OpenNLP questions on Stack Overflow](https://stackoverflow.com/questions/tagged/opennlp)

## Overview

Currently, the repository has the following modules:

* `geoentitylinker` : Links place names found in text to entries of a gazetteer index.
* `japanese` : Feature generators and a name finder factory for Japanese text.
* `liblinear` : LIBLINEAR based machine learning implementation.
* `opennlp-addons-docs` : The DocBook sources of the add-ons manual.

## Getting Started

Depend on the module you need, not on the parent `opennlp-addons` POM:

#### Maven

```
<dependency>
    <groupId>org.apache.opennlp.addons</groupId>
    <artifactId>japanese</artifactId>
    <version>${opennlp-addons.version}</version>
</dependency>
```

#### Gradle

```
compile group: "org.apache.opennlp.addons", name: "japanese", version: "${opennlp-addons.version}"
```

> [!NOTE]
> No 3.x add-ons release is published yet. Until one is, build this repository locally to use its artifacts.

## Building OpenNLP Add-ons

At least JDK 21 and Maven 3.9.6 are required to build the add-ons.

After cloning the repository go into the destination directory and run:

```
mvn install
```

A `verify` build additionally runs Checkstyle, forbidden API checks, the RAT license header check, and a dependency license report. Use `-Pjacoco` for coverage.

The manual is written in DocBook XML under [`opennlp-addons-docs/src/docbkx`](opennlp-addons-docs/src/docbkx) and builds with the rest of the reactor.

## Contributing

Every contribution is welcome, from a documentation typo fix to a new add-on. To get involved, please follow the instructions [here](https://github.com/apache/opennlp/blob/main/.github/CONTRIBUTING.md)
