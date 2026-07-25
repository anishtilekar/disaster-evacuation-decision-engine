package com.evacuation.engine.osm;

import com.evacuation.engine.config.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable polygon describing the target Pune ward's boundary.
 *
 * <p>A single ring is the source of truth for scoping the OSM import: {@link #toOverpassPoly()}
 * turns it into the Overpass {@code poly:} filter so roads are fetched ward-shaped rather than as a
 * loose rectangle, and {@link #contains(double, double)} clips imported roads and shelters to that
 * exact boundary. Driving both the download and the clip test from one polygon keeps the fetched
 * area and the retained area perfectly consistent.
 */
public final class WardBoundary {

    /** A single boundary vertex, in WGS84 decimal degrees. */
    public record Vertex(double lat, double lon) {
    }

    private final List<Vertex> ring;

    /**
     * Builds a ward boundary from an ordered ring of vertices, stored as a defensive, unmodifiable
     * copy. A ring may be supplied open or explicitly closed (first vertex repeated as last); a
     * duplicated closing vertex is dropped so the internal ring is always open.
     *
     * @param ring the ordered boundary vertices
     * @throws IllegalArgumentException if {@code ring} is {@code null} or has fewer than 3 vertices
     */
    public WardBoundary(List<Vertex> ring) {
        if (ring == null) {
            throw new IllegalArgumentException("Ward boundary ring must not be null");
        }

        List<Vertex> working = new ArrayList<>(ring);
        int size = working.size();
        if (size >= 2 && working.get(0).equals(working.get(size - 1))) {
            working.remove(size - 1); // drop the explicit closing vertex
        }

        if (working.size() < 3) {
            throw new IllegalArgumentException(
                    "Ward boundary ring must have at least 3 vertices, got: " + working.size());
        }

        this.ring = List.copyOf(working);
    }

    /**
     * Tests whether a coordinate lies within the ward, using standard ray-casting (PNPOLY) with
     * longitude as x and latitude as y. Points on the boundary count as inside.
     *
     * @param lat latitude of the point, in decimal degrees
     * @param lon longitude of the point, in decimal degrees
     * @return {@code true} if the point is inside or on the boundary
     */
    public boolean contains(double lat, double lon) {
        int n = ring.size();
        boolean inside = false;

        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring.get(i).lon();
            double yi = ring.get(i).lat();
            double xj = ring.get(j).lon();
            double yj = ring.get(j).lat();

            // Inclusive boundary: a point exactly on an edge (or vertex) counts as inside.
            if (isOnSegment(lon, lat, xj, yj, xi, yi)) {
                return true;
            }

            boolean crosses = ((yi > lat) != (yj > lat))
                    && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi);
            if (crosses) {
                inside = !inside;
            }
        }

        return inside;
    }

    /**
     * Renders the ring as a single space-separated {@code "lat lon lat lon ..."} string, the order
     * Overpass's {@code poly:} filter expects. The closing vertex is not repeated (the ring is
     * stored open).
     *
     * @return the Overpass {@code poly:} coordinate string
     */
    public String toOverpassPoly() {
        StringBuilder sb = new StringBuilder();
        for (Vertex v : ring) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(v.lat()).append(' ').append(v.lon());
        }
        return sb.toString();
    }

    /**
     * Returns the axis-aligned bounding box enclosing the ring, used for map centering and as a
     * coarse pre-filter before the exact {@link #contains(double, double)} clip.
     *
     * @return a {@link BoundingBox} of {@code (south=minLat, west=minLon, north=maxLat, east=maxLon)}
     */
    public BoundingBox toBoundingBox() {
        double minLat = Double.POSITIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;

        for (Vertex v : ring) {
            minLat = Math.min(minLat, v.lat());
            maxLat = Math.max(maxLat, v.lat());
            minLon = Math.min(minLon, v.lon());
            maxLon = Math.max(maxLon, v.lon());
        }

        return new BoundingBox(minLat, minLon, maxLat, maxLon);
    }

    /** @return the number of vertices in the (open) ring. */
    public int vertexCount() {
        return ring.size();
    }

    /**
     * Exact collinear on-segment test: true if {@code (px, py)} lies on the segment from
     * {@code (ax, ay)} to {@code (bx, by)}, endpoints inclusive.
     */
    private static boolean isOnSegment(double px, double py, double ax, double ay, double bx, double by) {
        double cross = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
        if (cross != 0.0) {
            return false; // not collinear with the edge
        }
        return px >= Math.min(ax, bx) && px <= Math.max(ax, bx)
                && py >= Math.min(ay, by) && py <= Math.max(ay, by);
    }
}