package com.evacuation.engine.algorithm.spacetime;

/**
 * The design's virtual state {@code (v, b)}: node {@code v} at bucket {@code b}.
 *
 * <p>This is the vertex of the time-expanded graph {@code TimeExpandedDijkstra} searches, and the
 * word "virtual" is load-bearing — that graph is never built. Its {@code V x T} vertices and their
 * arcs are generated on demand from the CSR row of {@code v}, so this record exists to <em>name</em> a
 * state in reconstructed output, not to be stored by the million.
 *
 * <p>Which is why the search's own scratch does not hold these objects. Boxing a record into a
 * {@code HashMap} key for every one of hundreds of thousands of states would undo exactly the
 * dense-array discipline the CSR was built for, so {@link #flatIndex(int, int, int)} folds a state
 * into a single {@code int} — {@code nodeIndex * horizonBuckets + bucket} — that indexes a plain
 * {@code double[]} or {@code int[]} directly, with {@link #fromFlatIndex(int, int)} unfolding it again
 * at reconstruction time. It is the same {@code (index * horizon + bucket)} packing
 * {@code HazardTimeline} already uses, so a state, its hazard cell, and its search scratch all address
 * alike.
 *
 * @param nodeIndex the dense node index — the design's {@code v}
 * @param bucket    the time bucket offset from the plan epoch — the design's {@code b}, where bucket 0
 *                  is "now"
 */
public record SpaceTimeState(int nodeIndex, int bucket) {

    public SpaceTimeState {
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("nodeIndex must be >= 0, got: " + nodeIndex);
        }
        if (bucket < 0) {
            throw new IllegalArgumentException("bucket must be >= 0, got: " + bucket);
        }
    }

    /**
     * Folds a state into its dense array index.
     *
     * @param nodeIndex      the dense node index
     * @param bucket         the time bucket
     * @param horizonBuckets T — the row width the state space is packed against
     * @return the flat index of {@code (nodeIndex, bucket)}
     */
    public static int flatIndex(int nodeIndex, int bucket, int horizonBuckets) {
        return nodeIndex * horizonBuckets + bucket;
    }

    /**
     * Unfolds a dense array index back into the state it encodes.
     *
     * @param flatIndex      a flat index produced by {@link #flatIndex(int, int, int)}
     * @param horizonBuckets T — the same row width it was packed against
     * @return the state that index encodes
     */
    public static SpaceTimeState fromFlatIndex(int flatIndex, int horizonBuckets) {
        return new SpaceTimeState(flatIndex / horizonBuckets, flatIndex % horizonBuckets);
    }
}