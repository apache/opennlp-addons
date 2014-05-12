/*
 * Copyright 2014 The Apache Software Foundation.
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

import com.spatial4j.core.context.SpatialContext;
import com.spatial4j.core.io.GeohashUtils;
import com.spatial4j.core.shape.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * Clusters a list of lat long points using a simple geohashing/boxing approach
 */
public class PointClustering {

  /**
   * Clusters a set of points from the gazateers. The idea is that locations
   * that matched a name that are closer to each other, the more likely the
   * toponym is to be accurate
   *
   * @param entries
   * @param precision
   * @return
   */
  public Map<String, List<GazetteerEntry>> cluster(List<GazetteerEntry> entries, int precision) {
    Map<String, List<GazetteerEntry>> map = new HashMap<>();
    for (int i = 0; i < entries.size(); i++) {
      GazetteerEntry entry = entries.get(i);
      Double latw = entry.getLatitude();
      Double lonw = entry.getLongitude();

      String key = geoHash(latw, lonw).substring(0, precision);
      if (map.containsKey(key)) {
        map.get(key).add(entry);
      } else {
        List<GazetteerEntry> newlist = new ArrayList<>();
        newlist.add(entry);
        map.put(key, newlist);
      }
    }
    return map;
  }

  public void scoreClusters(Map<String, List<GazetteerEntry>> clusters) {
    Double min = 0d;
    Double max = -1d;
    for (String key : clusters.keySet()) {
      int size = clusters.get(key).size();
      if (size > max) {
        max = Double.valueOf(size);
      }
    }
    for (String key : clusters.keySet()) {
      int size = clusters.get(key).size();
      Double score = normalize(Double.valueOf(size), min, max);
      for (GazetteerEntry entry : clusters.get(key)) {
        entry.getScoreMap().put("geohashbin", score);
      }
    }

  }

  /**
   * Returns a geohash based on Lucene Spatial
   *
   * @param lat the input latitude Y
   * @param lon the input longitude X
   * @return
   */
  public String geoHash(Double lat, Double lon) {
    String encodeLatLon = GeohashUtils.encodeLatLon(lat, lon);
    return encodeLatLon;
  }

  /**
   * Returns the X and Y point for the geohash. Element 0 is the X (longitude)
   * element 1 is the Y (latitude)
   *
   * @param geohash
   * @return
   */
  public double[] geoHashToPoint(String geohash) {
    Point decode = GeohashUtils.decode(geohash, SpatialContext.GEO);
    double[] coords = new double[]{decode.getX(), decode.getY()};
    return coords;
  }

  /**
   * Returns the X and Y point for the geohash. Element 0 is the X (longitude)
   * element 1 is the Y (latitude)
   *
   * @param geohash
   * @return
   */
  public String geoHashToPointStr(String geohash) {
    Point decode = GeohashUtils.decode(geohash, SpatialContext.GEO);
    String point = decode.getX() + "," + decode.getY();
    return point;
  }

  /**
   * Hashes a lat long based on adding 90 or 180 and then interlarding lat lon
   * chars. reduces a set of points to a sortable set
   *
   * @param lat
   * @param lon
   * @return
   */
  public String simpleGeohash(Double lat, Double lon) {
    String geoHash = "";
    lat = lat + 90;
    lon = lon + 180;
    String latString = String.valueOf(lat).replace(".", "");
    String lonString = String.valueOf(lon).replace(".", "");
    int length = latString.length() > lonString.length() ? lonString.length() : latString.length();
    while (length < 12) {
      latString += "0";
      lonString += "0";
      length++;
    }
    latString = latString.substring(0, 10);
    lonString = lonString.substring(0, 10);
    char[] latChars = latString.toCharArray();
    char[] lonChars = lonString.toCharArray();

    for (int i = 0; i < latChars.length; i++) {
      geoHash += String.valueOf(latChars[i]) + String.valueOf(lonChars[i]);
    }
    return geoHash;
  }

  private Double normalize(Double valueToNormalize, Double minimum, Double maximum) {
    Double d = (double) ((1 - 0) * (valueToNormalize - minimum)) / (maximum - minimum) + 0;
    return d;
  }
}
