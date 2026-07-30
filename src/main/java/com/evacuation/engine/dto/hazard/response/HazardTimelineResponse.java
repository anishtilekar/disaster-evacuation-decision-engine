package com.evacuation.engine.dto.hazard.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A compact read model of the currently-compiled hazard timeline, for a map overlay to shade edges
 * and nodes as the time slider passes each one's lethal onset.
 *
 * <p>Deliberately not the full packed {@code (slot|nodeIndex) x bucket} grid — most of that grid is
 * SAFE for the whole horizon, and a frontend only needs to know, per cell, the one bucket (if any)
 * at which it turns LETHAL, since {@link com.evacuation.engine.graph.time.HazardTimeline}'s own
 * "persists past onset" semantics mean a cell lethal at bucket b is lethal at every bucket after it
 * too. Only cells that become lethal within the horizon are reported at all.
 *
 * @param graphVersion    the snapshot version this timeline was compiled against
 * @param timelineVersion this timeline's own build id
 * @param builtAt         when this timeline was compiled
 * @param horizonBuckets  the compiled horizon, so a frontend's slider range matches exactly
 * @param edgeOnsets      every edge slot that turns LETHAL within the horizon, and when
 * @param nodeOnsets      every node that turns LETHAL within the horizon, and when
 */
public record HazardTimelineResponse(long graphVersion, long timelineVersion, LocalDateTime builtAt,
                                     int horizonBuckets, List<EdgeOnset> edgeOnsets,
                                     List<NodeOnset> nodeOnsets) {

    /** @param slot the CSR edge slot; @param lethalFromBucket the first bucket it is LETHAL at */
    public record EdgeOnset(int slot, int lethalFromBucket) {
    }

    /** @param nodeIndex the dense node index; @param lethalFromBucket the first bucket it is LETHAL at */
    public record NodeOnset(int nodeIndex, int lethalFromBucket) {
    }
}
