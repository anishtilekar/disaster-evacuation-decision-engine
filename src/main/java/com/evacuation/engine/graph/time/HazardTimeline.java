package com.evacuation.engine.graph.time;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, queryable hazard-timeline snapshot — STRIDE's generalization of the old boolean
 * "is this edge blocked" overlay into "what is this edge's/node's safety state at bucket b."
 *
 * <p>The timeline is packed into flat primitive arrays rather than a {@code Map<Long, List<Interval>>}
 * so that lookups on the routing hot path are O(1) array reads, matching the CSR's own dense-array
 * discipline. Each per-cell array is indexed as {@code (slot * horizonBuckets + bucket)} — edge slots
 * for {@code edgeState}/{@code edgeRisk}, dense node indices for {@code nodeState} — storing each
 * cell's {@link HazardState#ordinal()} as a {@code byte} alongside its risk factor as a {@code float}.
 *
 * <p>Bucket-clamping convention: a hazard state is taken to persist indefinitely past the horizon, so
 * any bucket index {@code >= horizonBuckets} is clamped to the last bucket ({@code horizonBuckets - 1})
 * before indexing rather than throwing or reading out of bounds. A negative bucket is a caller bug —
 * buckets are always relative to the current plan epoch, never in the past — and throws
 * {@link IllegalArgumentException}.
 *
 * <p>Built once per compile by a separate compiler class and held behind a separate cache; this class
 * carries validated data and exposes read-only access only, with no compilation logic of its own. It
 * is valid only paired with the exact {@link com.evacuation.engine.graph.structure.GraphSnapshot} it
 * was compiled against; it merely carries that {@code graphVersion} for the traversal policy to check.
 */
public final class HazardTimeline {

    /** Cached enum constants, so an ordinal byte maps back to a {@link HazardState} without allocating. */
    private static final HazardState[] STATES = HazardState.values();

    /** T — the number of buckets this timeline covers. */
    private final int horizonBuckets;
    /** Flat {@code edgeSlotCount * horizonBuckets}; each cell = {@link HazardState#ordinal()} as a byte. */
    private final byte[] edgeState;
    /** Same flat indexing as {@link #edgeState}; risk factor (rho) for RISKY cells, 0.0f otherwise. */
    private final float[] edgeRisk;
    /** Flat {@code nodeCount * horizonBuckets}, indexed by dense node index; ordinal-as-byte per cell. */
    private final byte[] nodeState;

    private final long graphVersion;
    private final long timelineVersion;
    private final LocalDateTime builtAt;

    /** Derived from the array lengths for O(1) bounds-checking of the slot/nodeIndex arguments. */
    private final int edgeSlotCount;
    private final int nodeCount;

    /**
     * Holds the fully-built, pre-validated timeline arrays by reference (mirroring
     * {@code GraphSnapshot}'s immutable-holder style — no copies). The per-cell dimensions
     * ({@code edgeSlotCount}, {@code nodeCount}) are derived from the array lengths against
     * {@code horizonBuckets}, so they cannot disagree with the packed data.
     *
     * @param horizonBuckets  the number of buckets covered ({@code >= 1})
     * @param edgeState       packed edge states, size {@code edgeSlotCount * horizonBuckets}
     * @param edgeRisk        packed edge risk factors, same indexing as {@code edgeState}
     * @param nodeState       packed node states, size {@code nodeCount * horizonBuckets}
     * @param graphVersion    the snapshot version this timeline was compiled against
     * @param timelineVersion this timeline's own monotonic build id
     * @param builtAt         when this timeline was compiled
     */
    public HazardTimeline(int horizonBuckets,
                          byte[] edgeState,
                          float[] edgeRisk,
                          byte[] nodeState,
                          long graphVersion,
                          long timelineVersion,
                          LocalDateTime builtAt) {
        this.horizonBuckets = horizonBuckets;
        this.edgeState = edgeState;
        this.edgeRisk = edgeRisk;
        this.nodeState = nodeState;
        this.graphVersion = graphVersion;
        this.timelineVersion = timelineVersion;
        this.builtAt = builtAt;
        this.edgeSlotCount = edgeState.length / horizonBuckets;
        this.nodeCount = nodeState.length / horizonBuckets;
    }

    // --- Edge accessors (slot in [0, edgeSlotCount); bucket clamped to the horizon) ---

    /**
     * @param slot   the CSR edge slot
     * @param bucket the time bucket (clamped to the horizon; must be {@code >= 0})
     * @return the edge's hazard state at that bucket
     */
    public HazardState edgeStateAt(int slot, int bucket) {
        Objects.checkIndex(slot, edgeSlotCount);
        return STATES[edgeState[slot * horizonBuckets + clampBucket(bucket)]];
    }

    /**
     * @param slot   the CSR edge slot
     * @param bucket the time bucket (clamped to the horizon; must be {@code >= 0})
     * @return {@code true} if the edge is LETHAL — never traversable — at that bucket
     */
    public boolean isEdgeLethal(int slot, int bucket) {
        return edgeStateAt(slot, bucket) == HazardState.LETHAL;
    }

    /**
     * @param slot   the CSR edge slot
     * @param bucket the time bucket (clamped to the horizon; must be {@code >= 0})
     * @return the edge's risk factor at that bucket ({@code 0.0f} unless RISKY)
     */
    public float edgeRiskAt(int slot, int bucket) {
        Objects.checkIndex(slot, edgeSlotCount);
        return edgeRisk[slot * horizonBuckets + clampBucket(bucket)];
    }

    // --- Node accessors (nodeIndex in [0, nodeCount); bucket clamped to the horizon) ---

    /**
     * @param nodeIndex the dense node index
     * @param bucket    the time bucket (clamped to the horizon; must be {@code >= 0})
     * @return the node's hazard state at that bucket
     */
    public HazardState nodeStateAt(int nodeIndex, int bucket) {
        Objects.checkIndex(nodeIndex, nodeCount);
        return STATES[nodeState[nodeIndex * horizonBuckets + clampBucket(bucket)]];
    }

    /**
     * @param nodeIndex the dense node index
     * @param bucket    the time bucket (clamped to the horizon; must be {@code >= 0})
     * @return {@code true} if the node is LETHAL — never traversable — at that bucket
     */
    public boolean isNodeLethal(int nodeIndex, int bucket) {
        return nodeStateAt(nodeIndex, bucket) == HazardState.LETHAL;
    }

    // --- Metadata ---

    /** @return T, the number of buckets this timeline covers. */
    public int horizonBuckets() {
        return horizonBuckets;
    }

    /** @return the {@code GraphSnapshot.graphVersion()} this timeline was compiled against. */
    public long graphVersion() {
        return graphVersion;
    }

    /** @return this timeline's own monotonic build id (timestamp millis). */
    public long timelineVersion() {
        return timelineVersion;
    }

    /** @return when this timeline was compiled. */
    public LocalDateTime builtAt() {
        return builtAt;
    }

    /**
     * The edge slots and node indices that transitioned into LETHAL between two compiles of the same
     * graph, restricted to buckets from {@code fromBucket} onward.
     *
     * @param edgeSlots   CSR slots newly LETHAL in {@code after} but not in {@code before}
     * @param nodeIndices dense node indices newly LETHAL in {@code after} but not in {@code before}
     */
    public record NewlyLethalCells(Set<Integer> edgeSlots, Set<Integer> nodeIndices) {
    }

    /**
     * Diffs two timelines compiled against the same graph, reporting exactly the cells that became
     * LETHAL from {@code fromBucket} onward — the set a repair pass needs to know what to replan
     * around, and no more. Past buckets are settled history no repair can act on, so the scan starts
     * at {@code fromBucket} rather than 0.
     *
     * <p>Diffing two full compiled states rather than re-deriving a hazard's footprint from its own
     * geometry is deliberate: recomputing it here would mean reimplementing the compiler's
     * distance-from-front reasoning and hoping the two agree. Asking the timeline what actually
     * changed is a single source of truth for the one question that decides which platoons get
     * replanned.
     *
     * @param before     the timeline as it stood before the event that triggered this compile
     * @param after      the newly compiled timeline
     * @param fromBucket the first bucket worth reporting a change in, typically "now"
     * @return the newly-LETHAL edge slots and node indices, each possibly empty but never {@code null}
     */
    public static NewlyLethalCells newlyLethal(HazardTimeline before, HazardTimeline after,
                                               int fromBucket) {
        Set<Integer> edgeSlots = new HashSet<>();
        for (int slot = 0; slot < after.edgeSlotCount; slot++) {
            for (int bucket = fromBucket; bucket < after.horizonBuckets; bucket++) {
                if (after.isEdgeLethal(slot, bucket) && !before.isEdgeLethal(slot, bucket)) {
                    edgeSlots.add(slot);
                    break;
                }
            }
        }

        Set<Integer> nodeIndices = new HashSet<>();
        for (int nodeIndex = 0; nodeIndex < after.nodeCount; nodeIndex++) {
            for (int bucket = fromBucket; bucket < after.horizonBuckets; bucket++) {
                if (after.isNodeLethal(nodeIndex, bucket) && !before.isNodeLethal(nodeIndex, bucket)) {
                    nodeIndices.add(nodeIndex);
                    break;
                }
            }
        }

        return new NewlyLethalCells(Set.copyOf(edgeSlots), Set.copyOf(nodeIndices));
    }

    /**
     * Clamps a requested bucket to the timeline's range: states persist past the horizon, so any
     * bucket at or beyond {@code horizonBuckets} reads the last bucket. Negative buckets are rejected.
     */
    private int clampBucket(int bucket) {
        if (bucket < 0) {
            throw new IllegalArgumentException("bucket must be >= 0, got: " + bucket);
        }
        return bucket >= horizonBuckets ? horizonBuckets - 1 : bucket;
    }
}