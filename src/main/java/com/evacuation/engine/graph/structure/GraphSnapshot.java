package com.evacuation.engine.graph.structure;

import com.evacuation.engine.model.enums.NodeType;
import com.evacuation.engine.model.enums.RoadStatus;
import com.evacuation.engine.model.enums.ShelterStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Immutable, dense-id, CSR (Compressed Sparse Row) snapshot of the road graph — the runtime
 * structure every routing algorithm reads from.
 *
 * <p>Nodes are addressed by a dense integer index {@code 0..nodeCount-1} rather than by database id
 * or hash map, so Dijkstra/A*'s hot path is plain O(1) array access with no hashing or boxing. Each
 * bidirectional {@link com.evacuation.engine.model.entity.RoadEdge} is pre-expanded into two
 * directed CSR "slots" at build time, so algorithms iterate outgoing slots uniformly and never
 * special-case direction.
 *
 * <p>Built once by {@code GraphBuilder} and swapped atomically by {@code GraphCache}. This class
 * does no graph-building or validation itself: it holds the arrays {@code GraphBuilder} already
 * built and validated, storing them by reference for speed and exposing them only through read-only
 * accessors. The raw arrays are never handed out, so callers cannot mutate a snapshot; the arrays,
 * the {@code nodeIdToIndex} map, and the {@code shelters} list must therefore never be mutated after
 * being passed in.
 */
public final class GraphSnapshot {

    // --- Node-indexed arrays (index = dense int 0..nodeCount-1) ---

    /** Maps each dense index back to its {@code RoadNode.nodeId}. */
    private final long[] dbNodeId;
    private final String[] nodeName;
    private final double[] nodeLat;
    private final double[] nodeLon;
    private final NodeType[] nodeType;
    /** Base-graph {@code RoadNode.active}; the overlay derives block-state from this but does not own it. */
    private final boolean[] nodeActive;
    /** {@code dbNodeId -> dense index}, unmodifiable; exposed only via {@link #indexOfDbNodeId(long)}. */
    private final Map<Long, Integer> nodeIdToIndex;

    // --- CSR adjacency (one directed traversal per slot) ---

    /** CSR offsets, size {@code nodeCount + 1}: node i's outgoing slots are {@code [edgeHead[i], edgeHead[i+1])}. */
    private final int[] edgeHead;
    /** Destination node dense index for each slot. */
    private final int[] edgeTo;
    /** Source {@code RoadEdge.edgeId} for each slot; both directions of a bidirectional edge share it. */
    private final long[] edgeDbId;
    private final double[] edgeDistanceKm;
    /** From {@code RoadEdge.estimatedTravelTimeMinutes}. */
    private final double[] edgeTimeMin;
    /** Base {@code RoadEdge.roadStatus}; the overlay layers live status on top of this. */
    private final RoadStatus[] edgeBaseStatus;

    // --- Shelters (already snapped to their nearest node by the builder) ---

    private final List<ShelterRef> shelters;

    // --- Metadata ---

    private final long graphVersion;
    private final LocalDateTime builtAt;
    private final int nodeCount;
    private final int edgeSlotCount;

    /**
     * A shelter snapped onto the graph: its owning dense {@code nodeIndex} plus the display and
     * allocation fields routing and response-building need, so callers never re-read the entity.
     */
    public record ShelterRef(long shelterId, int nodeIndex, String shelterName,
                             ShelterStatus status, int availableCapacity, boolean medicalFacility,
                             double lat, double lon) {
    }

    /**
     * Holds the pre-built, pre-validated graph arrays and collections by reference (no copies — the
     * builder is trusted to have made the map and list unmodifiable). {@code nodeCount} and
     * {@code edgeSlotCount} are derived from the array lengths so they cannot disagree with them.
     */
    public GraphSnapshot(long[] dbNodeId,
                         String[] nodeName,
                         double[] nodeLat,
                         double[] nodeLon,
                         NodeType[] nodeType,
                         boolean[] nodeActive,
                         Map<Long, Integer> nodeIdToIndex,
                         int[] edgeHead,
                         int[] edgeTo,
                         long[] edgeDbId,
                         double[] edgeDistanceKm,
                         double[] edgeTimeMin,
                         RoadStatus[] edgeBaseStatus,
                         List<ShelterRef> shelters,
                         long graphVersion,
                         LocalDateTime builtAt) {
        this.dbNodeId = dbNodeId;
        this.nodeName = nodeName;
        this.nodeLat = nodeLat;
        this.nodeLon = nodeLon;
        this.nodeType = nodeType;
        this.nodeActive = nodeActive;
        this.nodeIdToIndex = nodeIdToIndex;
        this.edgeHead = edgeHead;
        this.edgeTo = edgeTo;
        this.edgeDbId = edgeDbId;
        this.edgeDistanceKm = edgeDistanceKm;
        this.edgeTimeMin = edgeTimeMin;
        this.edgeBaseStatus = edgeBaseStatus;
        this.shelters = shelters;
        this.graphVersion = graphVersion;
        this.builtAt = builtAt;
        this.nodeCount = dbNodeId.length;
        this.edgeSlotCount = edgeTo.length;
    }

    // --- Structure / CSR iteration ---

    public int nodeCount() {
        return nodeCount;
    }

    public int edgeSlotCount() {
        return edgeSlotCount;
    }

    /** Inclusive start of {@code nodeIndex}'s outgoing slot range, iterated as {@code [start, end)}. */
    public int slotsStart(int nodeIndex) {
        return edgeHead[nodeIndex];
    }

    /** Exclusive end of {@code nodeIndex}'s outgoing slot range, iterated as {@code [start, end)}. */
    public int slotsEnd(int nodeIndex) {
        return edgeHead[nodeIndex + 1];
    }

    // --- Per-slot accessors (slot in [0, edgeSlotCount)) ---

    public int edgeTo(int slot) {
        return edgeTo[slot];
    }

    public long edgeDbId(int slot) {
        return edgeDbId[slot];
    }

    public double edgeDistanceKm(int slot) {
        return edgeDistanceKm[slot];
    }

    public double edgeTimeMin(int slot) {
        return edgeTimeMin[slot];
    }

    public RoadStatus edgeBaseStatus(int slot) {
        return edgeBaseStatus[slot];
    }

    // --- Per-node accessors (nodeIndex in [0, nodeCount)) ---

    public long dbNodeId(int nodeIndex) {
        return dbNodeId[nodeIndex];
    }

    public String nodeName(int nodeIndex) {
        return nodeName[nodeIndex];
    }

    public double nodeLat(int nodeIndex) {
        return nodeLat[nodeIndex];
    }

    public double nodeLon(int nodeIndex) {
        return nodeLon[nodeIndex];
    }

    public NodeType nodeType(int nodeIndex) {
        return nodeType[nodeIndex];
    }

    public boolean nodeActive(int nodeIndex) {
        return nodeActive[nodeIndex];
    }

    /** Dense index for a database node id, or {@code null} if it is not in this snapshot. */
    public Integer indexOfDbNodeId(long dbNodeId) {
        return nodeIdToIndex.get(dbNodeId);
    }

    // --- Shelters & metadata ---

    /** Shelters snapped to graph nodes; already unmodifiable, returned directly. */
    public List<ShelterRef> shelters() {
        return shelters;
    }

    /** Monotonically increasing build id (timestamp millis) identifying this snapshot. */
    public long graphVersion() {
        return graphVersion;
    }

    public LocalDateTime builtAt() {
        return builtAt;
    }
}