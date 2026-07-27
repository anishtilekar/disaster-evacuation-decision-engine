package com.evacuation.engine.algorithm;

import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.graph.spatial.GeoUtils;
import com.evacuation.engine.graph.structure.GraphSnapshot;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The admissible goal-distance estimate {@code AStarShortestPath} steers with: straight-line
 * (haversine) distance to the target, converted into travel <em>time</em>.
 *
 * <p>A* is only optimal if its heuristic never overestimates the true remaining cost, and it must
 * therefore speak the same units as that cost. Edge cost in this engine is
 * {@code edgeTimeMin} — minutes, not kilometres — so a raw haversine distance would not merely be
 * imprecise, it would be meaningless as a bound. Dividing kilometres by an assumed network-wide
 * maximum speed (km/h) and scaling by 60 yields minutes, directly comparable to the accumulated
 * {@code dist} of a search.
 *
 * <p>Admissibility then follows from two bounds that both err in the safe direction and compound:
 * the great-circle distance is a lower bound on any real road distance (no route is shorter than the
 * straight line), and {@code graph.network-max-speed-kmh} is configured to exceed every real edge
 * speed, so dividing by it is an upper bound on achievable speed. A lower-bound distance over an
 * upper-bound speed is a lower-bound time — the estimate cannot exceed the true remaining travel
 * time, by construction rather than by tuning. Raising the configured maximum only ever makes the
 * heuristic weaker (slower search, still correct); lowering it below a real edge speed is what would
 * break optimality, which is why that property lives in configuration with its contract documented.
 *
 * <p>The same two bounds make the estimate consistent as well as admissible (great-circle distance
 * obeys the triangle inequality, and no edge's time is less than its own straight-line lower bound),
 * so A* may settle each node once and never reopen it.
 *
 * <p>Stateless apart from the injected configuration, so a single bean is shared safely across
 * threads and called on the search hot path without allocation.
 */
@Component
@RequiredArgsConstructor
public class HaversineHeuristic {

    /** Minutes per hour, converting the km ÷ (km/h) quotient from hours into minutes. */
    private static final double MINUTES_PER_HOUR = 60.0;

    private final GraphEngineProperties properties;

    /**
     * Estimates the remaining travel time between two nodes as a lower bound, in minutes.
     *
     * @param snapshot      the graph holding both nodes' coordinates
     * @param fromNodeIndex dense index of the node being expanded
     * @param toNodeIndex   dense index of the search target
     * @return a lower bound on the true remaining travel time, in minutes
     */
    public double estimateTimeMinutes(GraphSnapshot snapshot, int fromNodeIndex, int toNodeIndex) {
        double km = GeoUtils.haversineKm(
                snapshot.nodeLat(fromNodeIndex), snapshot.nodeLon(fromNodeIndex),
                snapshot.nodeLat(toNodeIndex), snapshot.nodeLon(toNodeIndex));

        return km / properties.getNetworkMaxSpeedKmh() * MINUTES_PER_HOUR;
    }
}