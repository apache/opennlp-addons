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

# Gazetteer-backed geocoding annotator

This feature resolves location mentions to gazetteer places and records the selected geographic identities in an OpenNLP Document layer.

## Human definition

It turns a place name in text into a specific real-world place instead of leaving it as an unlinked name.

## Prior art and comparable products

- [Mordecai](https://github.com/openeventdata/mordecai) performs full-text geoparsing and event geocoding.
- [Nominatim](https://nominatim.osm.org/ui/about.html) resolves names and addresses against OpenStreetMap data.

## Addon modules

The `geo` module provides geocoding and region voting. Run `GeocodeAnnotator`
before `DocumentRegionAnnotator`: the ballot consumes the locations layer and
adds country-name and flag evidence. The `numeric` module supplies the downstream
region-aware currency adapter. Core supplies Document and the flag decoder.
