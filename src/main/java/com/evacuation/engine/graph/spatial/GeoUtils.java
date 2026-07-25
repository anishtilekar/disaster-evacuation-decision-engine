package com.evacuation.engine.graph.spatial;

/**
 * Geodesic helpers shared across the graph engine.
 *
 * <p>Everything here works in plain WGS-84 latitude/longitude. A single great-circle
 * (haversine) distance in kilometres is all the engine needs to sum road-segment lengths while
 * splitting OSM ways, snap coordinates to the nearest graph node ({@code NearestNodeLocator}),
 * and feed the A* time heuristic — so we keep it here rather than depend on a geometry library
 * such as JTS.
 */
public final class GeoUtils {

    /** Mean Earth radius (IUGG R1), in kilometres. */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoUtils() {
        // Static-only helper; never instantiated.
    }

    /**
     * Great-circle distance between two WGS-84 points, in kilometres.
     *
     * <p>Uses the haversine formula with {@link Math#atan2(double, double)} for numerical
     * stability — the {@code atan2} form stays well-conditioned across the full range of
     * separations, where an {@code asin}-based variant loses precision. This is the length
     * source for split OSM segments and the admissible lower bound for the A* time heuristic.
     *
     * @param lat1 latitude of the first point, in decimal degrees
     * @param lon1 longitude of the first point, in decimal degrees
     * @param lat2 latitude of the second point, in decimal degrees
     * @param lon2 longitude of the second point, in decimal degrees
     * @return the great-circle distance between the two points, in kilometres
     */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);

        double sinLat = Math.sin(dLat / 2.0);
        double sinLon = Math.sin(dLon / 2.0);

        double a = sinLat * sinLat + Math.cos(phi1) * Math.cos(phi2) * sinLon * sinLon;
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));

        return EARTH_RADIUS_KM * c;
    }
}