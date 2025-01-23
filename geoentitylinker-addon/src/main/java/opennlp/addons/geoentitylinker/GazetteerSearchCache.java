/*
 * Copyright 2013 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package opennlp.addons.geoentitylinker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Caches {@link GazetteerEntry gazetteer} query results statically.
 * Clears itself if more than 10000 results are cached.
 */
public class GazetteerSearchCache {

  private static final Map<String, ArrayList<GazetteerEntry>> CACHE = new HashMap<>();

  /**
   * @param searchString The search string to check for matching entries.
   * @return Retrieves cached entries. Returns {@code null} if the query does not exist in the cache.
   */
  public static synchronized ArrayList<GazetteerEntry> get(String searchString) {
    return CACHE.get(searchString);
  }

  public static synchronized void put(String searchString, ArrayList<GazetteerEntry> hits) {
    if (CACHE.size() > 10000) {
      CACHE.clear();
    }
    if (!CACHE.containsKey(searchString)) {
      CACHE.put(searchString, hits);
    }
  }

}
