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
 *
 * Caches gazateer query results statically. Clears itself if more than 10000 results are cached.
 */
public class GazetteerSearchCache {

  private static Map<String, ArrayList<GazetteerEntry>> gazCache = new HashMap<>();

/**
 * returns the cached entries. Returns null if the query does not exists in the cache
 * @param searchString
 * @return
 */
  public static synchronized ArrayList<GazetteerEntry> get(String searchString) {
    return gazCache.get(searchString);
  }

  public static synchronized void put(String searchString, ArrayList<GazetteerEntry> hits) {
    if (gazCache.size() > 10000) {
      gazCache.clear();
    }
    if (!gazCache.containsKey(searchString)) {
      gazCache.put(searchString, hits);
    }
  }


}
