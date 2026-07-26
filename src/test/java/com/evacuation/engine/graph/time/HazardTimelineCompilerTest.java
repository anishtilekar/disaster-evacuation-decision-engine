package com.evacuation.engine.graph.time;

import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.graph.overlay.TraversalPolicy;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.model.entity.BlockedRoad;
import com.evacuation.engine.model.entity.RoadEdge;
import com.evacuation.engine.model.enums.NodeType;
import com.evacuation.engine.model.enums.RoadStatus;
import com.evacuation.engine.repository.graph.BlockedRoadRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1 exit test: proves the time-bucketed hazard model reproduces exactly the traversal decision
 * the old boolean-blocking overlay would have made.
 *
 * <p>An active {@code BlockedRoad} row must compile into a {@link HazardTimeline} that reports LETHAL
 * for the affected edge's CSR slot(s) across the whole horizon (not just bucket 0), and
 * {@link TraversalPolicy} must therefore refuse to traverse it. A second scenario with no active
 * blocks proves an unaffected edge stays open, so the compiler doesn't over-block.
 */
class HazardTimelineCompilerTest {

    private static final long GRAPH_VERSION = 42L;
    private static final long BLOCKED_EDGE_DB_ID = 100L;

    private static final int SLOT_FORWARD = 0;  // node 0 -> node 1
    private static final int SLOT_REVERSE = 1;  // node 1 -> node 0

    /**
     * Builds a tiny real snapshot: 2 nodes and one bidirectional edge expanded into two directed CSR
     * slots (slot 0 = 0->1, slot 1 = 1->0) sharing one edgeDbId, exactly as GraphBuilder would.
     */
    private GraphSnapshot buildTwoNodeBidirectionalSnapshot() {
        long[] dbNodeId = {10L, 20L};
        String[] nodeName = {"Node-10", "Node-20"};
        double[] nodeLat = {18.5100, 18.5200};
        double[] nodeLon = {73.8500, 73.8600};
        NodeType[] nodeType = {NodeType.INTERSECTION, NodeType.INTERSECTION};
        boolean[] nodeActive = {true, true};
        Map<Long, Integer> nodeIdToIndex = Map.of(10L, 0, 20L, 1);

        // CSR: node 0's slots = [0,1), node 1's slots = [1,2); both slots share BLOCKED_EDGE_DB_ID.
        int[] edgeHead = {0, 1, 2};
        int[] edgeTo = {1, 0};
        long[] edgeDbId = {BLOCKED_EDGE_DB_ID, BLOCKED_EDGE_DB_ID};
        double[] edgeDistanceKm = {1.0, 1.0};
        double[] edgeTimeMin = {2.0, 2.0};
        RoadStatus[] edgeBaseStatus = {RoadStatus.OPEN, RoadStatus.OPEN};

        return new GraphSnapshot(
                dbNodeId, nodeName, nodeLat, nodeLon, nodeType, nodeActive, nodeIdToIndex,
                edgeHead, edgeTo, edgeDbId, edgeDistanceKm, edgeTimeMin, edgeBaseStatus,
                List.of(), GRAPH_VERSION, LocalDateTime.now());
    }

    private TimeModel defaultTimeModel() {
        // Default GraphEngineProperties values are fine (horizon = 160 buckets).
        return new TimeModel(new GraphEngineProperties());
    }

    @Test
    @DisplayName("An active BlockedRoad compiles to LETHAL across the whole horizon for both directed slots, and is not traversable")
    void blockedEdgeCompilesToLethalAndIsNotTraversable() {
        GraphSnapshot snapshot = buildTwoNodeBidirectionalSnapshot();

        RoadEdge blockedEdge = RoadEdge.builder()
                .edgeId(BLOCKED_EDGE_DB_ID)
                .build();
        BlockedRoad blockedRoad = BlockedRoad.builder()
                .roadEdge(blockedEdge)
                .active(true)
                .build();

        BlockedRoadRepository blockedRoadRepository = mock(BlockedRoadRepository.class);
        when(blockedRoadRepository.findActiveWithEdge()).thenReturn(List.of(blockedRoad));

        HazardTimeline timeline =
                new HazardTimelineCompiler(blockedRoadRepository, defaultTimeModel()).compile(snapshot);
        TraversalPolicy policy = new TraversalPolicy(snapshot, timeline);

        int lastBucket = timeline.horizonBuckets() - 1;

        // 1. LETHAL at the epoch and near the end of the horizon — spans the whole compiled horizon.
        assertTrue(timeline.isEdgeLethal(SLOT_FORWARD, 0));
        assertTrue(timeline.isEdgeLethal(SLOT_FORWARD, lastBucket));

        // 2. Both directed slots sharing the edgeDbId are LETHAL (bidirectional block).
        assertTrue(timeline.isEdgeLethal(SLOT_REVERSE, 0));
        assertTrue(timeline.isEdgeLethal(SLOT_REVERSE, lastBucket));

        // 3-4. TraversalPolicy refuses the edge via both the legacy and the bucket-aware overload.
        assertFalse(policy.isTraversable(SLOT_FORWARD));
        assertFalse(policy.isTraversable(SLOT_FORWARD, 0));
        assertFalse(policy.isTraversable(SLOT_REVERSE));
        assertFalse(policy.isTraversable(SLOT_REVERSE, 0));

        // 6. Version propagates, so TraversalPolicy's consistency check passes.
        assertEquals(snapshot.graphVersion(), timeline.graphVersion());
        assertEquals(GRAPH_VERSION, timeline.graphVersion());
    }

    @Test
    @DisplayName("With no active BlockedRoad rows, an OPEN edge stays traversable in both directions")
    void unaffectedEdgeStaysTraversable() {
        GraphSnapshot snapshot = buildTwoNodeBidirectionalSnapshot();

        BlockedRoadRepository blockedRoadRepository = mock(BlockedRoadRepository.class);
        when(blockedRoadRepository.findActiveWithEdge()).thenReturn(List.of());

        HazardTimeline timeline =
                new HazardTimelineCompiler(blockedRoadRepository, defaultTimeModel()).compile(snapshot);
        TraversalPolicy policy = new TraversalPolicy(snapshot, timeline);

        int lastBucket = timeline.horizonBuckets() - 1;

        // 5. Nothing is blocked, so neither slot is lethal and both remain traversable.
        assertFalse(timeline.isEdgeLethal(SLOT_FORWARD, 0));
        assertFalse(timeline.isEdgeLethal(SLOT_REVERSE, 0));

        assertTrue(policy.isTraversable(SLOT_FORWARD));
        assertTrue(policy.isTraversable(SLOT_REVERSE));
        assertTrue(policy.isTraversable(SLOT_FORWARD, 0));
        assertTrue(policy.isTraversable(SLOT_REVERSE, lastBucket));
    }
}