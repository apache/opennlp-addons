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

Welcome to Apache OpenNLP!
===========

[![Build Status](https://github.com/apache/opennlp-addons/workflows/Java%20CI/badge.svg)](https://github.com/apache/opennlp-addons/actions)
[![Contributors](https://img.shields.io/github/contributors/apache/opennlp-addons)](https://github.com/apache/opennlp-addons/graphs/contributors)
[![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/apache/opennlp-addons.svg)](https://github.com/apache/opennlp-addons/pulls)
[![Stack Overflow](https://img.shields.io/badge/stack%20overflow-opennlp-f1eefe.svg)](https://stackoverflow.com/questions/tagged/opennlp)

The Apache OpenNLP library is a machine learning based toolkit for the processing of natural language text.

These addons of the toolkit are written in Java and provide components for special NLP tasks, such as 
geographic entity linking, Wordnet dictionary access and integration with Liblinear and Morfologik!

The goal of the OpenNLP addons is to provide extra components, potentially in an experimental stage.

OpenNLP addons code can be used programmatically through its Java API, some components even from a terminal through its CLI.

## Useful Links

For additional information, visit the [OpenNLP Home Page](https://opennlp.apache.org/)

You can use OpenNLP with any language, demo models are provided [here](https://downloads.apache.org/opennlp/models/).
The models are fully compatible with the latest release, they can be used for testing or getting started.

> [!NOTE]  
> Please train your own models for all other use cases.

Documentation, including JavaDocs, code usage and command-line interface examples are available [here](https://opennlp.apache.org/docs/)

You can also follow our [mailing lists](https://opennlp.apache.org/mailing-lists.html) for news and updates.

## Overview

Currently, the library has different components:

* `geoentitylinker-addon`: A set of Java classes that allows for detecting and linking geographic entities.
* `japanese-addon`: An addon providing a set of Java classes specialized for processing text written in the Japanese language.
* `jwnl-addon`: An addon that uses [extJWNL](https://github.com/extjwnl/extjwnl) for accessing WordNet-style relational dictionaries.
* `liblinear-addon`: An addon that binds to [LIBLINEAR](https://www.csie.ntu.edu.tw/~cjlin/liblinear/) via [liblinear-java](https://github.com/bwaldvogel/liblinear-java).
* `modelbuilder-addon`: A set of Java classes to build models more conveniently.
* `morfologik-addon`: An addon that binds to [Morfologik](https://github.com/morfologik).

## Getting Started

You can import the addons components directly via Maven, SBT or Gradle after you have built it locally:

#### Maven

```
<dependency>
    <groupId>org.apache.opennlp</groupId>
    <artifactId>opennlp-addons</artifactId>
    <version>${opennlp.version}</version>
</dependency>
```

#### SBT

```
libraryDependencies += "org.apache.opennlp" % "opennlp-addons" % "${opennlp.version}"
```

#### Gradle

```
compile group: "org.apache.opennlp", name: "opennlp-addons", version: "${opennlp.version}"
```

For more details please check our [documentation](https://opennlp.apache.org/docs/)

## Building OpenNLP

At least JDK 21 and Maven 3.3.9 are required to build the addons components.

After cloning the repository go into the destination directory and run:

```
mvn install
```

## Contributing

The Apache OpenNLP project is developed by volunteers and is always looking for new contributors to work on all parts of the project. 
Every contribution is welcome and needed to make it better. 
A contribution can be anything from a small documentation typo fix to a new component.

If you would like to get involved please follow the instructions [here](https://github.com/apache/opennlp/blob/main/.github/CONTRIBUTING.md)
