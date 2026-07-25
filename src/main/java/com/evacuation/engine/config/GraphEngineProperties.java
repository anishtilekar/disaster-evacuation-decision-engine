package com.evacuation.engine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Externalised configuration for the graph engine, bound from the {@code graph.*} keys in
 * {@code application.properties}.
 *
 * <p>Holding the ward, Overpass settings, shelter parameters, and seeding behaviour here — rather
 * than hardcoding them — follows the architecture doc's "externalised configuration" principle and
 * lets the pilot ward be retargeted (a different Pune ward, different shelter rules) without a code
 * change.
 */
@Component
@ConfigurationProperties(prefix = "graph")
@Getter
@Setter
public class GraphEngineProperties {

    /**
     * Whether to import OSM and seed shelters/disaster at startup when the graph is empty. Enabled
     * by default so a fresh {@code create-drop} database comes up ready to route; set {@code false}
     * in tests so they stay hermetic.
     */
    private boolean seedOnStartup = true;

    /**
     * Assumed maximum network speed, in km/h. Used as the divisor in the A* time heuristic — so it
     * must exceed every real edge speed for the heuristic to stay admissible — and as a fallback
     * speed for edges with no usable {@code maxspeed}.
     */
    private double networkMaxSpeedKmh = 90.0;

    /**
     * OSM/Overpass import and shelter settings. Populated in place by JavaBean binding via its
     * getter, so the field is intentionally {@code final}.
     */
    private final Osm osm = new Osm();

    /**
     * OpenStreetMap import, ward, and shelter settings, bound from {@code graph.osm.*}.
     */
    @Getter
    @Setter
    public static class Osm {

        /** Human-readable label for the target ward. */
        private String wardName = "Shivajinagar-Ghole Road";

        /**
         * Classpath-relative location of the ward boundary GeoJSON, loaded by
         * {@code WardPolygonLoader} and used to scope the Overpass query and clip imported data.
         */
        private String wardPolygonFile = "ward/shivajinagar-ghole-road.geojson";

        /** Overpass API endpoint queried for the ward's routable ways and nodes. */
        private String overpassUrl = "https://overpass-api.de/api/interpreter";

        /**
         * Directory (relative to the working dir) where raw Overpass responses are cached, so
         * repeated boots stay deterministic and offline after the first fetch.
         */
        private String cacheDir = "osm-cache";

        /** Overpass server-side query timeout, in seconds. */
        private int timeoutSeconds = 90;

        /**
         * OSM {@code amenity} values treated as candidate shelters. Mutable so property overrides
         * bind cleanly.
         */
        private List<String> shelterAmenities =
                new ArrayList<>(List.of("school", "hospital", "community_centre"));

        /**
         * Estimated capacity per amenity type — OSM carries none, so we assume by kind. Mutable so
         * property overrides bind cleanly.
         */
        private Map<String, Integer> shelterCapacityByAmenity = new LinkedHashMap<>(Map.of(
                "hospital", 300,
                "school", 500,
                "community_centre", 150
        ));
    }
}