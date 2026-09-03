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

# Using OpenNLP Add-ons

## Select a module

Each add-on is published as a separate artifact under the
`org.apache.opennlp.addons` group. Select the artifact from the
[module catalog](modules.html). The parent `opennlp-addons` artifact is a build
POM and is not an application dependency.

```xml
<dependency>
  <groupId>org.apache.opennlp.addons</groupId>
  <artifactId>jwnl-addon</artifactId>
  <version>${opennlp-addons.version}</version>
</dependency>
```

Use the same add-ons version for each add-on in one application.

## Current development versions

The repository version is `3.0.0-SNAPSHOT`. Most modules currently compile
against OpenNLP `3.0.0-M5`, which is set by the root `opennlp.version`
property. A module that requires an API still under review can override that
property on its development branch.

Before a 3.x release is available from Maven Central, install the selected
add-on from a local checkout:

```bash
mvn -pl jwnl-addon -am install
```

Then use `3.0.0-SNAPSHOT` as `opennlp-addons.version` in the consuming build.

## Models and data

An add-on may require a model, dictionary, or dataset that is not included in
its JAR. Check the module documentation for required resources and their
licenses. The presence of an Apache-licensed add-on does not change the license
of a separately supplied resource.
