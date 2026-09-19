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

# Numeric place profiles

This feature compares places using a user-supplied table of numeric measurements.
It standardizes each column and ranks places by cosine similarity. It does not
encode categorical data or load measurement datasets automatically.

## Human definition

Compare places by measurements such as population density, income, or elevation,
not by matching their names. Place identifiers and metric names must be unique.
The choice of metrics and places determines the scores.

## Prior art and comparable products

- [GeoNames](https://www.geonames.org/export/web-services.html) exposes place metadata including feature classes, population, elevation, and administrative data.
- [Elasticsearch function scoring](https://www.elastic.co/docs/reference/query-languages/query-dsl/query-dsl-function-score-query) supports relevance functions that decay with numeric or geographic distance from a target value.

## Addon module

This feature is part of `org.apache.opennlp.addons:geo`. It builds on the
gazetteer research branch and adds no measurement datasets or dependencies.
