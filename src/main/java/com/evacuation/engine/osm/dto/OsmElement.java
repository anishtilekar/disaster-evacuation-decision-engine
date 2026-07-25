package com.evacuation.engine.osm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * One element of an Overpass API response — either a {@code "node"} (carrying {@code lat}/
 * {@code lon}) or a {@code "way"} (carrying an ordered {@link #nodes} reference list and
 * {@link #tags}).
 *
 * <p>A deliberately simple, public-field internal DTO: it exists only to receive the Overpass
 * JSON before {@code OsmWaySplitter} turns it into graph nodes and edges. Unknown Overpass
 * metadata is ignored so the parser stays tolerant across API versions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OsmElement {

    /** Element kind, e.g. {@code "node"} or {@code "way"}. */
    public String type;

    /** OSM identifier of this element. */
    public long id;

    /** Latitude in decimal degrees; meaningful only for nodes. */
    public double lat;

    /** Longitude in decimal degrees; meaningful only for nodes. */
    public double lon;

    /** Ordered OSM node ids making up a way; {@code null} for nodes. */
    public List<Long> nodes;

    /** Raw OSM tags (e.g. {@code highway}, {@code oneway}, {@code maxspeed}); may be {@code null}. */
    public Map<String, String> tags;

    /** @return {@code true} if this element is an OSM node. */
    public boolean isNode() {
        return "node".equals(type);
    }

    /** @return {@code true} if this element is an OSM way. */
    public boolean isWay() {
        return "way".equals(type);
    }

    /**
     * Null-safe lookup of a single OSM tag.
     *
     * @param key the tag key
     * @return the tag value, or {@code null} if there are no tags or the key is absent
     */
    public String tag(String key) {
        return tags == null ? null : tags.get(key);
    }
}