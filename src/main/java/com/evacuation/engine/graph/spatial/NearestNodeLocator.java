package com.evacuation.engine.graph.spatial;

import com.evacuation.engine.graph.structure.GraphSnapshot;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Finds the nearest routable graph node to an arbitrary lat/lon point.
 *
 * <p>Used to snap real OSM shelter facilities — which carry their own coordinates rather than a node
 * foreign key — onto the graph while a {@link GraphSnapshot} is built. At ward scale (low hundreds
 * of nodes) a linear haversine scan is fast enough and far simpler than a spatial index; per the
 * architecture doc, a grid or k-d tree only pays off at city scale, not this project's size, so
 * introducing one here would be complexity without benefit.
 *
 * <p>Inactive nodes are excluded from candidates: a snap target must be a node the router can
 * actually leave from, so snapping a shelter to a disabled node would produce an unusable entry.
 */
@Component
public class NearestNodeLocator {

    /** The nearest node found: its dense index in the snapshot and the great-circle distance to it. */
    public record NearestNodeResult(int nodeIndex, double distanceKm) {
    }

    /**
     * Finds the nearest active node to {@code (lat, lon)}.
     *
     * @param lat      latitude of the query point, in decimal degrees
     * @param lon      longitude of the query point, in decimal degrees
     * @param snapshot the graph to search
     * @return the nearest active node, or empty if the graph has no active nodes
     */
    public Optional<NearestNodeResult> findNearest(double lat, double lon, GraphSnapshot snapshot) {
        int bestIndex = -1;
        double bestDistanceKm = Double.POSITIVE_INFINITY;

        for (int i = 0; i < snapshot.nodeCount(); i++) {
            if (!snapshot.nodeActive(i)) {
                continue; // a snap target must be usable by the router
            }
            double distanceKm = GeoUtils.haversineKm(lat, lon, snapshot.nodeLat(i), snapshot.nodeLon(i));
            if (distanceKm < bestDistanceKm) {
                bestDistanceKm = distanceKm;
                bestIndex = i;
            }
        }

        return bestIndex < 0
                ? Optional.empty()
                : Optional.of(new NearestNodeResult(bestIndex, bestDistanceKm));
    }

    /**
     * Finds the nearest active node to {@code (lat, lon)}, rejecting matches farther than
     * {@code maxDistanceKm}.
     *
     * <p>The cap guards against silently snapping a facility that lies well outside the ward polygon
     * onto a distant node — better to report no match than an implausible one.
     *
     * @param lat           latitude of the query point, in decimal degrees
     * @param lon           longitude of the query point, in decimal degrees
     * @param snapshot      the graph to search
     * @param maxDistanceKm the maximum acceptable snap distance, in kilometres
     * @return the nearest active node within {@code maxDistanceKm}, or empty if there is none
     */
    public Optional<NearestNodeResult> findNearest(double lat, double lon, GraphSnapshot snapshot,
                                                   double maxDistanceKm) {
        return findNearest(lat, lon, snapshot)
                .filter(result -> result.distanceKm() <= maxDistanceKm);
    }
}