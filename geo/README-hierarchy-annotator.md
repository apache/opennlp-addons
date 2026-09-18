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

# Geographic hierarchy annotator

This feature expands a resolved location into its administrative containment chain and records that hierarchy in an OpenNLP Document layer.

## Human definition

It explains that a city belongs to a state or province, which belongs to a country, which belongs to a continent.

## Prior art and comparable products

- [GeoNames hierarchy services](https://www.geonames.org/export/place-hierarchy.html) return the administrative ancestors and children of a place.

## Addon module

`org.apache.opennlp.addons:geo` provides the hierarchy, geocoding, and gazetteer
APIs. Core supplies the Document container and typed layers. Run the geocoding
stage before hierarchy annotation so resolved locations are available.
