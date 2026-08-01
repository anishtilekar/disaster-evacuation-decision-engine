package com.evacuation.engine.dispatch;

import com.evacuation.engine.algorithm.AStarShortestPath;
import com.evacuation.engine.algorithm.HaversineHeuristic;
import com.evacuation.engine.algorithm.MultiTargetShelterSearch;
import com.evacuation.engine.algorithm.spacetime.TimeExpandedDijkstra;
import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimelineCache;
import com.evacuation.engine.graph.time.HazardTimelineCompiler;
import com.evacuation.engine.graph.time.TimeModel;
import com.evacuation.engine.loader.GraphCache;
import com.evacuation.engine.model.enums.EvacuationPriority;
import com.evacuation.engine.model.enums.NodeType;
import com.evacuation.engine.model.enums.RoadStatus;
import com.evacuation.engine.model.enums.ShelterStatus;
import com.evacuation.engine.repository.graph.BlockedRoadRepository;
import com.evacuation.engine.repository.graph.HazardEventRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 5 exit test: LNS monotonicity.
 *
 * <p>Two parallel edges from one origin to one shelter, cheap (1 bucket) and expensive (8 buckets),
 * both generously capacitated. A synthetic ledger reservation — not a real party, just a raw
 * {@code tryReserveEdge} call — occupies the cheap edge for 100 buckets, so a real dispatch is
 * forced onto the expensive edge (waiting out 100 buckets costs far more than an 8-bucket detour).
 * Releasing that synthetic reservation afterward, with nothing else touched, is exactly the
 * precondition an improving LNS iteration needs: the platoon's own re-search now sees a cheaper
 * option that genuinely was not there when it first committed.
 */
class ImprovementLoopTest {

    private static final Logger log = LoggerFactory.getLogger(ImprovementLoopTest.class);

    private static final long GRAPH_VERSION = 42L;
    private static final long SHELTER_ID = 1L;
    private static final int ORIGIN = 0;
    private static final int SHELTER_NODE = 1;
    private static final int SLOT_CHEAP = 0;
    private static final int SLOT_EXPENSIVE = 1;
    private static final long EDGE_DB_ID_CHEAP = 100L;
    private static final long EDGE_DB_ID_EXPENSIVE = 101L;

    private static final long TARGET_PARTY_ID = 1L;
    private static final int TARGET_PEOPLE = 5;

    /** Never a real DispatchService-issued id (those start at 1); safe from collision. */
    private static final long SYNTHETIC_BLOCKER_ID = 999L;
    /** 100 buckets >> the 8-bucket detour, so waiting it out is never competitive. */
    private static final int BLOCKER_TAU = 100;
    private static final int BLOCKER_SIZE = 4;

    private GraphSnapshot buildSnapshot() {
        long[] dbNodeId = {10L, 20L};
        String[] nodeName = {"Origin", "Shelter Node"};
        double[] nodeLat = {18.5000, 18.5100};
        double[] nodeLon = {73.8500, 73.8500};
        NodeType[] nodeType = {NodeType.INTERSECTION, NodeType.INTERSECTION};
        boolean[] nodeActive = {true, true};
        double[] nodeCapacityPersonsPerHour = {10_000.0, 10_000.0};
        Map<Long, Integer> nodeIdToIndex = Map.of(10L, 0, 20L, 1);

        int[] edgeHead = {0, 2, 2};
        int[] edgeTo = {SHELTER_NODE, SHELTER_NODE};
        long[] edgeDbId = {EDGE_DB_ID_CHEAP, EDGE_DB_ID_EXPENSIVE};
        double[] edgeDistanceKm = {0.1, 2.0};
        // 0.25 min -> 1 bucket; 2.0 min -> 8 buckets.
        double[] edgeTimeMin = {0.25, 2.0};
        // Cheap: 2400/hr -> 8.5/bucket after headroom. Target (5) fits alone; target + blocker
        // (5 + 4 = 9) does not, so the blocker forces the detour without also blocking target outright.
        double[] edgeCapacityPersonsPerHour = {2_400.0, 10_000.0};
        RoadStatus[] edgeBaseStatus = {RoadStatus.OPEN, RoadStatus.OPEN};

        GraphSnapshot.ShelterRef shelter = new GraphSnapshot.ShelterRef(
                SHELTER_ID, SHELTER_NODE, "Test Shelter", ShelterStatus.AVAILABLE,
                1000, false, 18.5100, 73.8500);

        return new GraphSnapshot(
                dbNodeId, nodeName, nodeLat, nodeLon, nodeType, nodeActive, nodeCapacityPersonsPerHour,
                nodeIdToIndex, edgeHead, edgeTo, edgeDbId, edgeDistanceKm, edgeTimeMin,
                edgeCapacityPersonsPerHour, edgeBaseStatus, List.of(shelter), GRAPH_VERSION,
                LocalDateTime.now());
    }

    private record Harness(GraphSnapshot snapshot, ActivePlan activePlan,
                           DispatchService dispatchService, ImprovementLoop improvementLoop) {
    }

    private Harness buildHarness() {
        GraphSnapshot snapshot = buildSnapshot();
        GraphEngineProperties properties = new GraphEngineProperties();
        TimeModel timeModel = new TimeModel(properties);

        BlockedRoadRepository blockedRoadRepository = mock(BlockedRoadRepository.class);
        when(blockedRoadRepository.findActiveWithEdge()).thenReturn(List.of());
        HazardEventRepository hazardEventRepository = mock(HazardEventRepository.class);
        when(hazardEventRepository.findByActive(true)).thenReturn(List.of());
        HazardTimelineCache hazardTimelineCache = new HazardTimelineCache(
                new HazardTimelineCompiler(blockedRoadRepository, hazardEventRepository, timeModel));

        GraphCache graphCache = mock(GraphCache.class);
        when(graphCache.get()).thenReturn(snapshot);

        ActivePlan activePlan = new ActivePlan();
        TimeExpandedDijkstra search = new TimeExpandedDijkstra(timeModel, properties);
        AStarShortestPath aStar = new AStarShortestPath(new HaversineHeuristic(properties));
        DispatchService dispatchService = new DispatchService(graphCache, hazardTimelineCache,
                activePlan, search, new MultiTargetShelterSearch(), aStar, timeModel, properties);
        ImprovementLoop improvementLoop = new ImprovementLoop(graphCache, hazardTimelineCache,
                activePlan, search, new MultiTargetShelterSearch(), aStar, timeModel);

        return new Harness(snapshot, activePlan, dispatchService, improvementLoop);
    }

    /** Dispatches the target party while the cheap edge is synthetically occupied, forcing the detour. */
    private DispatchResult dispatchTargetOntoTheDetour(Harness harness) {
        LocalDateTime epoch = LocalDateTime.now();
        // An empty batch just to start the session, so the ledger exists to reserve into below.
        harness.dispatchService().plan(List.of(), epoch);
        harness.activePlan().ledger().tryReserveEdge(SLOT_CHEAP, 0, BLOCKER_TAU, BLOCKER_SIZE,
                SYNTHETIC_BLOCKER_ID);

        InstructionSet dispatched = harness.dispatchService().plan(
                List.of(new Party(TARGET_PARTY_ID, ORIGIN, TARGET_PEOPLE, EvacuationPriority.MEDIUM,
                        false, epoch)),
                epoch);
        assertEquals(1, dispatched.committed().size());
        assertTrue(dispatched.shortfalls().isEmpty());

        DispatchResult target = dispatched.committed().get(0);
        assertTrue(target.searchResult().walk().steps().stream()
                        .anyMatch(step -> !step.isWait() && step.edgeSlot() == SLOT_EXPENSIVE),
                "target must be forced onto the expensive edge while the cheap one is blocked");
        return target;
    }

    @Test
    @DisplayName("LNS finds and accepts a genuine improvement once capacity frees up")
    void lnsFindsAGenuineImprovement() {
        Harness harness = buildHarness();
        DispatchResult before = dispatchTargetOntoTheDetour(harness);
        LexicographicObjective.Score scoreBefore = LexicographicObjective.score(
                List.of(before), new TimeModel(new GraphEngineProperties()).horizonBuckets());

        // The blocker is never in the plan book, so releasing it directly is the whole cleanup.
        harness.activePlan().ledger().release(SYNTHETIC_BLOCKER_ID);

        ImprovementLoop.Result result = harness.improvementLoop().improve(1);

        assertEquals(1, result.iterationsRun());
        assertEquals(1, result.accepted());
        assertTrue(LexicographicObjective.isBetter(result.finalScore(), scoreBefore),
                "cheap-edge route must score strictly better than the forced detour");

        DispatchResult after = harness.activePlan().planBook().get(before.platoonId()).orElseThrow();
        assertTrue(after.searchResult().walk().steps().stream()
                        .anyMatch(step -> !step.isWait() && step.edgeSlot() == SLOT_CHEAP),
                "the repaired route should now take the cheap edge");
        assertFalse(after.searchResult().walk().steps().stream()
                .anyMatch(step -> !step.isWait() && step.edgeSlot() == SLOT_EXPENSIVE));

        log.info("Score before: {} -> after: {}", scoreBefore, result.finalScore());
    }

    @Test
    @DisplayName("Once optimal, repeated LNS passes neither regress nor thrash")
    void repeatedPassesStabilizeWithoutRegressing() {
        Harness harness = buildHarness();
        dispatchTargetOntoTheDetour(harness);
        harness.activePlan().ledger().release(SYNTHETIC_BLOCKER_ID);

        LexicographicObjective.Score afterFirst = harness.improvementLoop().improve(1).finalScore();
        LexicographicObjective.Score afterSecond = harness.improvementLoop().improve(1).finalScore();
        LexicographicObjective.Score afterThird = harness.improvementLoop().improve(1).finalScore();

        assertEquals(afterFirst, afterSecond, "already optimal — a second pass must not change the score");
        assertEquals(afterSecond, afterThird, "nor a third");
    }

    @Test
    @DisplayName("An already-optimal plan is left byte-identical — nothing to accept means nothing changes")
    void alreadyOptimalPlanIsUntouched() {
        Harness harness = buildHarness();
        LocalDateTime epoch = LocalDateTime.now();
        InstructionSet dispatched = harness.dispatchService().plan(
                List.of(new Party(TARGET_PARTY_ID, ORIGIN, TARGET_PEOPLE, EvacuationPriority.MEDIUM,
                        false, epoch)),
                epoch);
        DispatchResult before = dispatched.committed().get(0);
        assertTrue(before.searchResult().walk().steps().stream()
                .anyMatch(step -> !step.isWait() && step.edgeSlot() == SLOT_CHEAP));

        int cheapOccupancyBefore = harness.activePlan().ledger().edgeOccupancyAt(SLOT_CHEAP, 0);

        ImprovementLoop.Result result = harness.improvementLoop().improve(3);

        assertEquals(0, result.accepted());
        assertSame(before, harness.activePlan().planBook().get(before.platoonId()).orElseThrow());
        assertEquals(cheapOccupancyBefore,
                harness.activePlan().ledger().edgeOccupancyAt(SLOT_CHEAP, 0));
    }

    @Test
    @DisplayName("LNS is a no-op when no dispatch session has ever started")
    void noOpWhenNoSessionIsActive() {
        Harness harness = buildHarness();
        assertFalse(harness.activePlan().isActive());

        ImprovementLoop.Result result = harness.improvementLoop().improve(2);

        assertEquals(2, result.iterationsRun());
        assertEquals(0, result.accepted());
        assertFalse(harness.activePlan().isActive());
    }
}
