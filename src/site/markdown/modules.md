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

# Module Catalog

All modules use the Maven group `org.apache.opennlp.addons`.

| Artifact | Function | Review status |
| --- | --- | --- |
| `geoentitylinker-addon` | Detects and links geographic entities | [Reconcile with OPENNLP-1879](reconciliation.html#geoentitylinker-addon) |
| `japanese-addon` | Adds features for Japanese text processing | [Separate from OPENNLP-1894](reconciliation.html#japanese-addon) |
| `jwnl-addon` | Reads WordNet relational dictionaries through extJWNL | [Reconcile with OPENNLP-1880 and OPENNLP-1887](reconciliation.html#jwnl-addon) |
| `liblinear-addon` | Supplies LIBLINEAR-backed model support | No matching modernization PR found |
| `modelbuilder-addon` | Provides model-building utilities | No matching modernization PR found |

Depend on the artifact for the selected module. The root `opennlp-addons` POM
coordinates the reactor build and is not a combined add-ons JAR.

OpenNLP core publishes the Morfologik packages and classes, so the add-ons
reactor does not duplicate them. See the
[reconciliation record](reconciliation.html#morfologik-addon).
