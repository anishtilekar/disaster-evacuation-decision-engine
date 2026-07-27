package com.evacuation.engine.osm;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link OsmSpeedTable}'s sibling: turns an OSM {@code highway} type (and optional {@code lanes}
 * tag) into an assumed road capacity, in persons per hour.
 *
 * <p>Vehicle-mode only, for now. This ward's Overpass import filter pulls in the drivable
 * classification range — motorway through service — and never footway/path/pedestrian ways, so a
 * vehicle-capacity assumption matches exactly what actually gets imported. A PEDESTRIAN mode is a
 * documented future extension rather than something built speculatively here: nothing in this
 * engine currently models vehicle eligibility or pedestrian-only edges, so a pedestrian throughput
 * table would have no caller and no data to key off.
 *
 * <p>Like {@code OsmSpeedTable}'s own defaults, every constant below is internal rather than bound
 * from {@code GraphEngineProperties}. Per-lane throughput and default lane counts are standard
 * traffic-engineering assumptions, not operator levers an evacuation coordinator would tune per
 * deployment — they belong with the code that interprets OSM tags, not in the config surface
 * dispatch weights live on.
 */
@Component
public class OsmCapacityTable {

    /**
     * Average persons per vehicle assumed for an evacuation trip. Set above typical commute
     * occupancy (usually ~1.2-1.5) because an evacuating household travels together rather than
     * commuting alone — this is a capacity assumption for the crisis, not for rush hour.
     */
    private static final double PERSONS_PER_VEHICLE = 3.0;

    /** Fallback per-lane throughput (vehicles/hour) for unknown or missing highway types. */
    private static final double DEFAULT_BASE_VEHICLES_PER_HOUR_PER_LANE = 900.0;

    /** Fallback lane count for a highway type absent from {@link #DEFAULT_LANES_BY_TYPE} entirely. */
    private static final int DEFAULT_LANES_FALLBACK = 1;

    /** Standard per-lane hourly vehicle throughput by OSM highway classification. */
    private static final Map<String, Double> BASE_VEHICLES_PER_HOUR_PER_LANE = Map.ofEntries(
            Map.entry("motorway", 2000.0),
            Map.entry("trunk", 1900.0),
            Map.entry("primary", 1800.0),
            Map.entry("secondary", 1600.0),
            Map.entry("tertiary", 1400.0),
            Map.entry("unclassified", 1200.0),
            Map.entry("residential", 900.0),
            Map.entry("living_street", 400.0),
            Map.entry("service", 600.0),
            Map.entry("road", 1200.0)
    );

    /** Assumed lane count by OSM highway classification, used when OSM's own {@code lanes} tag is missing. */
    private static final Map<String, Integer> DEFAULT_LANES_BY_TYPE = Map.ofEntries(
            Map.entry("motorway", 3),
            Map.entry("trunk", 2),
            Map.entry("primary", 2),
            Map.entry("secondary", 2),
            Map.entry("tertiary", 1),
            Map.entry("unclassified", 1),
            Map.entry("residential", 1),
            Map.entry("living_street", 1),
            Map.entry("service", 1),
            Map.entry("road", 1)
    );

    /**
     * Resolves the capacity to assume for an edge, in persons per hour.
     *
     * <p>A real OSM {@code lanes} tag wins over the category default when present and positive —
     * it is ground truth, the default is only a stand-in for the frequent case where OSM omits it.
     * {@code *_link} ramps inherit their parent type's throughput and lane default, the same
     * reasoning {@link OsmSpeedTable#speedKmh} applies to speed. A {@code null} or unrecognised
     * highway type falls through to the global fallback constants rather than throwing, matching
     * this project's existing tolerance for patchy OSM tagging.
     *
     * @param highway the OSM {@code highway} tag value (may be {@code null})
     * @param lanesTag the OSM {@code lanes} tag, already parsed to an integer (may be {@code null}
     *                 or non-positive, either of which falls back to the category default)
     * @return the assumed capacity in persons per hour, always positive
     */
    public double capacityPersonsPerHour(String highway, Integer lanesTag) {
        String type = null;
        if (highway != null) {
            type = highway.trim().toLowerCase();
            if (type.endsWith("_link")) {
                type = type.substring(0, type.length() - "_link".length());
            }
        }

        int lanes = (lanesTag != null && lanesTag > 0)
                ? lanesTag
                : DEFAULT_LANES_BY_TYPE.getOrDefault(type, DEFAULT_LANES_FALLBACK);

        double perLane = BASE_VEHICLES_PER_HOUR_PER_LANE.getOrDefault(
                type, DEFAULT_BASE_VEHICLES_PER_HOUR_PER_LANE);

        return lanes * perLane * PERSONS_PER_VEHICLE;
    }
}