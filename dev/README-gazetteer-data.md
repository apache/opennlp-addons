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

# Gazetteer data: where it comes from and how to regenerate it

The `geo` module reads plain text tables instead of parsing upstream distribution formats. Java developer tools in `geo/src/test/java/opennlp/geo/dev` derive those tables. Their tests run with the module; generation is an explicit command. Jackson and DuckDB are test-scoped dependencies and are not included in the runtime artifact.

## The three gazetteers and their data

| Gazetteer | Data source | License of the data | Shipped in the jar? |
|---|---|---|---|
| `opennlp.geo.BundledGazetteer` | Natural Earth, Populated Places theme | Public domain | Yes: `naturalearth-populated-places.txt` |
| `opennlp.geo.GeoNamesGazetteer` | GeoNames main-table extracts, downloaded by the user | CC-BY 4.0 (see the `readme.txt` alongside the downloads) | No. The user downloads the file; the CC-BY license terms, including attribution, stay with the downloaded files. |
| `opennlp.geo.OvertureGazetteer` | Overture Maps, divisions theme, flattened by `OvertureGenerator` | ODbL 1.0. Attribution and database share-alike terms apply and follow the derived table, which the user builds. | No. Nothing ODbL-licensed is distributed by the project in any form. |

No gazetteer data beyond the bundled public-domain table is added to the project; every other dataset is downloaded by the user and is not redistributed by the project. The license classifications behind this split are on record in LEGAL-732: CDLA-Permissive-2.0 was judged Category A, ODbL Category X, and public-domain/CC0 data needs only a LICENSE section. Note the theme distinction inside Overture: the Places theme is CDLA-Permissive-2.0, but the divisions theme this module consumes is ODbL, and the two must not be conflated.

## Natural Earth regeneration

Download the pinned GeoJSON outside the repository:

```sh
curl -fL https://raw.githubusercontent.com/nvkelso/natural-earth-vector/789c9904087846cc3361302857aa2e76b0ae71ff/geojson/ne_10m_populated_places.geojson \
  -o /tmp/ne_10m_populated_places.geojson
```

From the addon repository root, with the required core snapshot available:

```sh
mvn -pl geo -am -Dopennlp.forkCount=1 test-compile
mvn -f geo/pom.xml org.codehaus.mojo:exec-maven-plugin:3.6.3:exec \
  -Dexec.classpathScope=test -Dexec.executable=java \
  '-Dexec.args=-cp %classpath opennlp.geo.dev.NaturalEarthGenerator /tmp/ne_10m_populated_places.geojson /tmp/naturalearth-populated-places.txt'
```

The generator emits the eleven-field, pure-ASCII, LF-terminated table consumed by
`BundledGazetteer`. Review the output before replacing
`geo/src/main/resources/opennlp/geo/naturalearth-populated-places.txt`.
Its source records the mirror commit and extraction date. The checked-in table
retains its historical derivation header; the Java tool emits an updated header
with repair counts. The 7,342 data rows reproduce the frozen table exactly.

Upstream names sometimes contain encoding damage. The explicit repair tables
retain the verified replacements from the original generator. Unknown anomalies,
duplicate IDs, nonintegral identifiers, invalid coordinates, and stale repair
entries fail generation before it replaces the output. Coordinate rounding uses
five decimal places with ties to even on the source's binary floating-point value.

## Overture division generation

`OvertureGenerator` uses the DuckDB JDBC driver and its `httpfs` and `spatial`
extensions. The command installs and loads those extensions, then reads division
Parquet from the selected public release bucket. It requires network access and
can process a large dataset. Choose a release explicitly; the example below is
not a promise that a particular upstream release remains available.

```sh
mvn -pl geo -am -Dopennlp.forkCount=1 test-compile
mvn -f geo/pom.xml org.codehaus.mojo:exec-maven-plugin:3.6.3:exec \
  -Dexec.classpathScope=test -Dexec.executable=java \
  '-Dexec.args=-cp %classpath opennlp.geo.dev.OvertureGenerator 2026-06-18.0 /tmp/overture-divisions.txt 10000'
```

Output has eight tab-separated fields: id, primary name, comma-separated alternate
names, latitude, longitude, country code, subtype, and population. It retains
countries, dependencies, regions, counties, local administrative areas, and
localities at or above the population floor. Records are ordered by id; alternate
names are sorted and deduplicated. Missing names or points are skipped. Unknown
schema changes, invalid coordinates, field delimiters, and commas inside alternate
names fail explicitly because the loader's format cannot represent them without
changing their meaning. An empty or failed refresh leaves any previous output intact.
The header records the release, date, filters, and ODbL attribution/share-alike terms.
No Overture dataset is bundled or downloaded by the normal test build.

A test with authored Parquet records exercises the actual SQL query and gazetteer
loader. It is opt-in because installing DuckDB's spatial extension needs network
access:

```sh
mvn -pl geo -am -Dopennlp.forkCount=1 -Dopennlp.geo.spatialTest=true \
  -Dtest=OvertureGeneratorParquetTest -Dsurefire.failIfNoSpecifiedTests=false test
```

This test checks the local conversion path. It does not validate a complete remote
Overture release or its current schema.

## GeoNames data (no script needed)

`GeoNamesGazetteer` reads the GeoNames main table format directly, so there is nothing to derive. Download a filtered city extract (for example `cities500.zip`) from the GeoNames export dump area, unzip it, and load the `.txt` with `GeoNamesGazetteer.load(...)`. The loader indexes the whole table in memory, so use the filtered extracts rather than the full `allCountries` dump; memory grows with row and alternate-name count.

## Regeneration checklist

1. Record where the input came from: release id, mirror commit, or download date. The tools embed this in their output headers or carry it in constants at the top of the tool; update those constants when you refresh.
2. Run the generator and review the whole diff of the regenerated table before committing anything.
3. If a generator fails on an anomaly, that is the intended behavior: inspect the new upstream value, extend the audited repair tables only with values you have verified, and rerun.
4. Never commit downloaded upstream files, and never move an attribution-required or share-alike dataset into `src/main/resources`.
