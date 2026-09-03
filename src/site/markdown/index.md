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

OpenNLP Add-ons provides optional components that use the public OpenNLP API
for integrations and specialized NLP tasks. Each module is packaged as its own
Maven artifact. Applications can add one integration without adding the
dependencies used by the other modules.

## Start here

- [Using add-ons](using-addons.html) explains module dependencies and version
  selection.
- [Module catalog](modules.html) lists the available artifacts.
- [Feature reconciliation](reconciliation.html) records overlaps with OpenNLP
  core and active pull requests.
- [Build from source](building.html) covers the repository build and this
  manual.

## Relationship to OpenNLP core

Add-ons use contracts from `opennlp-api` and may use other published OpenNLP
artifacts. An add-on can provide a model-format reader, an optional third-party
integration, or a specialized component without expanding the core runtime.

Shared contracts remain in OpenNLP core. Their implementations can live here
when they use a distinct model format, external data, or optional libraries.
This keeps applications in control of their dependency set while giving each
integration an Apache release path.

## Documentation

The manual is written in CommonMark Markdown under `src/site/markdown` and
rendered by Apache Maven Site and Doxia. Navigation is defined in
`src/site/site.xml`.
