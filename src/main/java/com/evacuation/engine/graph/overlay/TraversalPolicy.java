package com.evacuation.engine.graph.overlay;

import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimeline;

/**
 * The single decision point every routing/dispatch search consults during neighbor expansion: "can I
 * traverse this edge slot at this bucket, and if so, is the destination node usable then too?"
 *
 * <p>It pairs a {@link GraphSnapshot} (the immutable base topology) with a {@link HazardTimeline} (the
 * time-bucketed hazard state compiled against that exact snapshot version), keeping topology and
 * time-varying hazard state decoupled per the overlay principle. Every algorithm — plain Dijkstra
 * today, a time-expanded search later — gets one bucket-aware gate instead of re-implementing the
 * "check the edge and its destination node" logic. Traversability deliberately splits the two
 * signals: an edge slot can be clear while the node it leads into is not (a blocked intersection), so
 * both must be non-lethal for the move to be allowed.
 *
 * <p>The constructor enforces version consistency: a timeline is only meaningful paired with the exact
 * snapshot it was compiled from, so a mismatch (a stale timeline applied to a newer snapshot, or the
 * reverse) fails loudly rather than routing against inconsistent state.
 *
 * <p>The legacy no-bucket overloads answer an instantaneous "right now" query by reading bucket 0.
 * That is valid because {@code HazardTimelineCompiler} anchors a freshly compiled timeline so its own
 * bucket 0 is the moment of compilation — after a fresh reload, "now" is always bucket 0.
 */
public final class TraversalPolicy {

    private final GraphSnapshot snapshot;
    private final HazardTimeline timeline;

    /**
     * Pairs a snapshot with a hazard timeline compiled against it.
     *
     * @param snapshot the immutable base topology
     * @param timeline the hazard timeline compiled against {@code snapshot}
     * @throws IllegalStateException if the timeline's graph version does not match the snapshot's
     */
    public TraversalPolicy(GraphSnapshot snapshot, HazardTimeline timeline) {
        if (snapshot.graphVersion() != timeline.graphVersion()) {
            throw new IllegalStateException(String.format(
                    "hazard timeline was compiled against graph version %d but snapshot is version %d",
                    timeline.graphVersion(), snapshot.graphVersion()));
        }
        this.snapshot = snapshot;
        this.timeline = timeline;
    }

    /**
     * Whether an edge slot can be traversed at a given bucket: the edge itself must be non-lethal and
     * the destination node it leads into must be non-lethal at that same bucket.
     *
     * @param slot   the CSR edge slot
     * @param bucket the arrival/traversal bucket
     * @return {@code true} only if both the edge and its destination node are non-lethal then
     */
    public boolean isTraversable(int slot, int bucket) {
        return !timeline.isEdgeLethal(slot, bucket)
                && !timeline.isNodeLethal(snapshot.edgeTo(slot), bucket);
    }

    /**
     * Instantaneous ("right now") traversability check — delegates to {@link #isTraversable(int, int)}
     * at bucket 0, relying on the "bucket 0 = this timeline's compile-time now" convention.
     *
     * @param slot the CSR edge slot
     * @return {@code true} if the slot is traversable at the timeline's current epoch
     */
    public boolean isTraversable(int slot) {
        return isTraversable(slot, 0);
    }

    /**
     * Whether a node is usable at a given bucket — for validating a proposed source/target node before
     * a search begins.
     *
     * @param nodeIndex the dense node index
     * @param bucket    the bucket to check
     * @return {@code true} if the node is non-lethal at that bucket
     */
    public boolean isNodeUsable(int nodeIndex, int bucket) {
        return !timeline.isNodeLethal(nodeIndex, bucket);
    }

    /**
     * Instantaneous ("right now") node-usability check — delegates to {@link #isNodeUsable(int, int)}
     * at bucket 0, per the "bucket 0 = this timeline's compile-time now" convention.
     *
     * @param nodeIndex the dense node index
     * @return {@code true} if the node is usable at the timeline's current epoch
     */
    public boolean isNodeUsable(int nodeIndex) {
        return isNodeUsable(nodeIndex, 0);
    }

    /**
     * The edge's risk factor at a bucket, for a future cost model to price RISKY exposure without its
     * own timeline reference. Unused internally in this phase.
     *
     * @param slot   the CSR edge slot
     * @param bucket the bucket to read
     * @return the edge's risk factor at that bucket ({@code 0.0f} unless RISKY)
     */
    public float riskAt(int slot, int bucket) {
        return timeline.edgeRiskAt(slot, bucket);
    }

    /** @return the underlying immutable base topology. */
    public GraphSnapshot snapshot() {
        return snapshot;
    }

    /** @return the underlying hazard timeline. */
    public HazardTimeline timeline() {
        return timeline;
    }
}
