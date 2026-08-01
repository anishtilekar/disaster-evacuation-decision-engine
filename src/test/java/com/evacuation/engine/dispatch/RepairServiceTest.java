package com.evacuation.engine.dispatch;

import com.evacuation.engine.algorithm.AStarShortestPath;
import com.evacuation.engine.algorithm.HaversineHeuristic;
import com.evacuation.engine.algorithm.MultiTargetShelterSearch;
import com.evacuation.engine.algorithm.spacetime.Destination;
import com.evacuation.engine.algorithm.spacetime.TimeExpandedDijkstra;
import com.evacuation.engine.algorithm.spacetime.TimedWalk;
import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimelineCache;
import com.evacuation.engine.graph.time.HazardTimelineCompiler;
import com.evacuation.engine.graph.time.TimeModel;
import com.evacuation.engine.loader.GraphCache;
import com.evacuation.engine.model.entity.BlockedRoad;
import com.evacuation.engine.model.entity.RoadEdge;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 4 exit test: mid-evacuation block.
 *
 * <p>The scenario is built so the boundary the whole phase rests on is checkable by hand rather
 * than merely plausible. Party A's ten people split into two waves five minutes apart on the same
 * crossing; by the time the road is blocked, wave 0 has already fully crossed it and wave 1 has
 * not yet started. Party B never goes near that crossing at all. Blocking the road should therefore
 * repair <em>exactly</em> wave 1 — not wave 0, whose history already happened and cannot be
 * unwritten by an event reported after the fact, and not Party B, who was never near it — and every
 * instruction it does not touch must come out of repair as the literal same object it went in as,
 * not merely an equal one.
 *
 * <p>At the default 15-second bucket, a 1-minute edge is 4 buckets and a 150-second convoy stagger
 * is 10 buckets, so wave 0 occupies {@code [0, 4)} and wave 1 {@code [10, 14)} on the direct
 * crossing. Reporting the block at bucket 6 — comfortably after wave 0 finished and comfortably
 * before wave 1 was due to start — is the exact boundary the design's {@code from = now} floor
 * exists to get right.
 */
class RepairServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RepairServiceTest.class);

    private static final long GRAPH_VERSION = 42L;
    private static final long SHELTER_ID = 1L;

    // Dense node indices, shared by the with-detour fixture.
    private static final int ORIGIN_A = 0;
    private static final int SHELTER_NODE = 1;
    private static final int ORIGIN_B = 2;
    private static final int DETOUR_A = 3;

    // CSR slots, with-detour fixture.
    private static final int SLOT_A_DIRECT = 0;
    private static final int SLOT_A_TO_DETOUR = 1;
    private static final int SLOT_B_DIRECT = 2;
    private static final int SLOT_DETOUR_TO_SHELTER = 3;

    private static final long EDGE_DB_ID_A_DIRECT = 100L;
    private static final long EDGE_DB_ID_A_TO_DETOUR = 101L;
    private static final long EDGE_DB_ID_B_DIRECT = 200L;
    private static final long EDGE_DB_ID_DETOUR_TO_SHELTER = 102L;

    private static final long PARTY_A_ID = 1L;
    private static final long PARTY_B_ID = 2L;

    private static final int PARTY_A_PEOPLE = 10;
    private static final int PARTY_B_PEOPLE = 5;
    private static final int MAX_PLATOON_SIZE = 5;
    private static final int CONVOY_STAGGER_BUCKETS = 10;

    /** Generous enough that every capacity check in this test is a non-factor. */
    private static final double GENEROUS_CAPACITY_PERSONS_PER_HOUR = 10_000.0;

    private GraphEngineProperties properties() {
        GraphEngineProperties properties = new GraphEngineProperties();
        properties.getDispatch().setMaxPlatoonSize(MAX_PLATOON_SIZE);
        properties.getDispatch().setConvoyStaggerBuckets(CONVOY_STAGGER_BUCKETS);
        return properties;
    }

    /**
     * Origin A reaches the shared shelter directly (the crossing that gets blocked) or via a
     * detour through node 3; origin B reaches it by a third, wholly unrelated edge.
     */
    private GraphSnapshot buildSnapshotWithDetour() {
        long[] dbNodeId = {10L, 20L, 30L, 40L};
        String[] nodeName = {"Origin A", "Shelter Node", "Origin B", "Detour A"};
        double[] nodeLat = {18.5000, 18.5100, 18.5200, 18.5050};
        double[] nodeLon = {73.8500, 73.8500, 73.8600, 73.8550};
        NodeType[] nodeType = {
                NodeType.INTERSECTION, NodeType.INTERSECTION,
                NodeType.INTERSECTION, NodeType.INTERSECTION
        };
        boolean[] nodeActive = {true, true, true, true};
        double[] nodeCapacityPersonsPerHour = {
                GENEROUS_CAPACITY_PERSONS_PER_HOUR, GENEROUS_CAPACITY_PERSONS_PER_HOUR,
                GENEROUS_CAPACITY_PERSONS_PER_HOUR, GENEROUS_CAPACITY_PERSONS_PER_HOUR
        };
        Map<Long, Integer> nodeIdToIndex = Map.of(10L, 0, 20L, 1, 30L, 2, 40L, 3);

        // Node 0's slots [0,2): direct + detour-entry. Node 1: none (sink). Node 2's slots [2,3):
        // its own unrelated edge. Node 3's slots [3,4): detour-exit.
        int[] edgeHead = {0, 2, 2, 3, 4};
        int[] edgeTo = {SHELTER_NODE, DETOUR_A, SHELTER_NODE, SHELTER_NODE};
        long[] edgeDbId = {
                EDGE_DB_ID_A_DIRECT, EDGE_DB_ID_A_TO_DETOUR,
                EDGE_DB_ID_B_DIRECT, EDGE_DB_ID_DETOUR_TO_SHELTER
        };
        double[] edgeDistanceKm = {0.5, 0.5, 0.5, 0.5};
        // 1.0 min -> ceil(60/15) = 4 buckets, on every edge.
        double[] edgeTimeMin = {1.0, 1.0, 1.0, 1.0};
        double[] edgeCapacityPersonsPerHour = {
                GENEROUS_CAPACITY_PERSONS_PER_HOUR, GENEROUS_CAPACITY_PERSONS_PER_HOUR,
                GENEROUS_CAPACITY_PERSONS_PER_HOUR, GENEROUS_CAPACITY_PERSONS_PER_HOUR
        };
        RoadStatus[] edgeBaseStatus = {
                RoadStatus.OPEN, RoadStatus.OPEN, RoadStatus.OPEN, RoadStatus.OPEN
        };

        GraphSnapshot.ShelterRef shelter = new GraphSnapshot.ShelterRef(
                SHELTER_ID, SHELTER_NODE, "Test Shelter", ShelterStatus.AVAILABLE,
                1000, false, 18.5100, 73.8500);

        return new GraphSnapshot(
                dbNodeId, nodeName, nodeLat, nodeLon, nodeType, nodeActive, nodeCapacityPersonsPerHour,
                nodeIdToIndex, edgeHead, edgeTo, edgeDbId, edgeDistanceKm, edgeTimeMin,
                edgeCapacityPersonsPerHour, edgeBaseStatus, List.of(shelter), GRAPH_VERSION,
                LocalDateTime.now());
    }

    /** Origin A's only way out — no detour, so a block here is unrepairable by construction. */
    private GraphSnapshot buildSnapshotWithoutDetour() {
        long[] dbNodeId = {10L, 20L};
        String[] nodeName = {"Origin A", "Shelter Node"};
        double[] nodeLat = {18.5000, 18.5100};
        double[] nodeLon = {73.8500, 73.8500};
        NodeType[] nodeType = {NodeType.INTERSECTION, NodeType.INTERSECTION};
        boolean[] nodeActive = {true, true};
        double[] nodeCapacityPersonsPerHour = {
                GENEROUS_CAPACITY_PERSONS_PER_HOUR, GENEROUS_CAPACITY_PERSONS_PER_HOUR
        };
        Map<Long, Integer> nodeIdToIndex = Map.of(10L, 0, 20L, 1);

        int[] edgeHead = {0, 1, 1};
        int[] edgeTo = {SHELTER_NODE};
        long[] edgeDbId = {EDGE_DB_ID_A_DIRECT};
        double[] edgeDistanceKm = {0.5};
        double[] edgeTimeMin = {1.0};
        double[] edgeCapacityPersonsPerHour = {GENEROUS_CAPACITY_PERSONS_PER_HOUR};
        RoadStatus[] edgeBaseStatus = {RoadStatus.OPEN};

        GraphSnapshot.ShelterRef shelter = new GraphSnapshot.ShelterRef(
                SHELTER_ID, SHELTER_NODE, "Test Shelter", ShelterStatus.AVAILABLE,
                1000, false, 18.5100, 73.8500);

        return new GraphSnapshot(
                dbNodeId, nodeName, nodeLat, nodeLon, nodeType, nodeActive, nodeCapacityPersonsPerHour,
                nodeIdToIndex, edgeHead, edgeTo, edgeDbId, edgeDistanceKm, edgeTimeMin,
                edgeCapacityPersonsPerHour, edgeBaseStatus, List.of(shelter), GRAPH_VERSION,
                LocalDateTime.now());
    }

    /** Everything one scenario needs, wired exactly as GraphAdminService wires it in production. */
    private record Harness(GraphSnapshot snapshot, GraphEngineProperties properties,
                           TimeModel timeModel, BlockedRoadRepository blockedRoadRepository,
                           HazardTimelineCache hazardTimelineCache, ActivePlan activePlan,
                           DispatchService dispatchService, RepairService repairService) {
    }

    private Harness buildHarness(GraphSnapshot snapshot) {
        GraphEngineProperties properties = properties();
        TimeModel timeModel = new TimeModel(properties);

        BlockedRoadRepository blockedRoadRepository = mock(BlockedRoadRepository.class);
        when(blockedRoadRepository.findActiveWithEdge()).thenReturn(List.of());
        HazardEventRepository hazardEventRepository = mock(HazardEventRepository.class);
        when(hazardEventRepository.findByActive(true)).thenReturn(List.of());

        HazardTimelineCompiler compiler =
                new HazardTimelineCompiler(blockedRoadRepository, hazardEventRepository, timeModel);
        HazardTimelineCache hazardTimelineCache = new HazardTimelineCache(compiler);

        GraphCache graphCache = mock(GraphCache.class);
        when(graphCache.get()).thenReturn(snapshot);

        ActivePlan activePlan = new ActivePlan();

        DispatchService dispatchService = new DispatchService(
                graphCache, hazardTimelineCache, activePlan,
                new TimeExpandedDijkstra(timeModel, properties), new MultiTargetShelterSearch(),
                new AStarShortestPath(new HaversineHeuristic(properties)), timeModel, properties);
        RepairService repairService = new RepairService(
                graphCache, hazardTimelineCache, activePlan,
                new TimeExpandedDijkstra(timeModel, properties), timeModel);

        return new Harness(snapshot, properties, timeModel, blockedRoadRepository,
                hazardTimelineCache, activePlan, dispatchService, repairService);
    }

    private DispatchResult findByPartyAndWave(List<DispatchResult> results, long partyId, int waveIndex) {
        return results.stream()
                .filter(r -> r.partyId() == partyId && r.waveIndex() == waveIndex)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No committed result for party " + partyId + " wave " + waveIndex));
    }

    private boolean usesSlot(TimedWalk walk, int slot) {
        return walk.steps().stream().anyMatch(step -> !step.isWait() && step.edgeSlot() == slot);
    }

    @Test
    @DisplayName("Repair is a no-op when no dispatch session has ever started")
    void repairIsANoOpWhenNoSessionIsActive() {
        Harness harness = buildHarness(buildSnapshotWithDetour());

        assertFalse(harness.activePlan().isActive());

        InstructionSet result = harness.repairService()
                .onEvent(Set.of(SLOT_A_DIRECT), Set.of(), LocalDateTime.now());

        assertTrue(result.committed().isEmpty());
        assertTrue(result.shortfalls().isEmpty());
        assertFalse(harness.activePlan().isActive(), "repair must not itself start a session");
    }

    @Test
    @DisplayName("Blocking an edge nobody reserved repairs nothing, and every instruction stays the exact same object")
    void blockingAnUnusedEdgeRepairsNothing() {
        Harness harness = buildHarness(buildSnapshotWithDetour());
        LocalDateTime epoch = LocalDateTime.now();

        InstructionSet dispatched = harness.dispatchService().plan(
                List.of(new Party(PARTY_A_ID, ORIGIN_A, PARTY_A_PEOPLE, EvacuationPriority.MEDIUM, false, epoch),
                        new Party(PARTY_B_ID, ORIGIN_B, PARTY_B_PEOPLE, EvacuationPriority.MEDIUM, false, epoch)),
                epoch);
        assertTrue(dispatched.shortfalls().isEmpty());

        // Nobody was ever routed through the detour-entry edge — it exists but is never the
        // cheaper choice while the direct crossing is open.
        InstructionSet repair = harness.repairService()
                .onEvent(Set.of(SLOT_A_TO_DETOUR), Set.of(), epoch.plusSeconds(30));

        assertTrue(repair.committed().isEmpty());
        assertTrue(repair.shortfalls().isEmpty());

        for (DispatchResult before : dispatched.committed()) {
            DispatchResult after = harness.activePlan().planBook().get(before.platoonId()).orElseThrow();
            assertSame(before, after, "an unaffected platoon's instruction must be untouched, not merely equal");
        }
    }

    @Test
    @DisplayName("The repair set is exactly the platoon with a still-future reservation on the blocked edge")
    void repairSetIsExactlyThePlatoonWithAFutureReservation() {
        Harness harness = buildHarness(buildSnapshotWithDetour());
        LocalDateTime epoch = LocalDateTime.now();

        InstructionSet dispatched = harness.dispatchService().plan(
                List.of(new Party(PARTY_A_ID, ORIGIN_A, PARTY_A_PEOPLE, EvacuationPriority.MEDIUM, false, epoch),
                        new Party(PARTY_B_ID, ORIGIN_B, PARTY_B_PEOPLE, EvacuationPriority.MEDIUM, false, epoch)),
                epoch);
        assertTrue(dispatched.shortfalls().isEmpty());
        assertEquals(3, dispatched.committed().size(), "party A's two waves plus party B's one");

        DispatchResult wave0Before = findByPartyAndWave(dispatched.committed(), PARTY_A_ID, 0);
        DispatchResult wave1Before = findByPartyAndWave(dispatched.committed(), PARTY_A_ID, 1);
        DispatchResult partyBBefore = findByPartyAndWave(dispatched.committed(), PARTY_B_ID, 0);

        // Sanity-check the scenario's own premise before trusting what repair does with it: both
        // waves must actually be on the direct crossing, at the disjoint windows the stagger implies.
        assertTrue(usesSlot(wave0Before.searchResult().walk(), SLOT_A_DIRECT));
        assertEquals(0, wave0Before.searchResult().walk().origin().bucket());
        assertTrue(usesSlot(wave1Before.searchResult().walk(), SLOT_A_DIRECT));
        assertEquals(CONVOY_STAGGER_BUCKETS, wave1Before.searchResult().walk().origin().bucket());

        // Report the block at bucket 6: after wave 0's [0,4) window closed, before wave 1's [10,14)
        // window opens. Recompiling with the block active mirrors exactly what GraphAdminService
        // does — same session epoch, so bucket 0 does not move.
        RoadEdge blockedEdge = RoadEdge.builder().edgeId(EDGE_DB_ID_A_DIRECT).build();
        BlockedRoad blockedRoad = BlockedRoad.builder().roadEdge(blockedEdge).active(true).build();
        when(harness.blockedRoadRepository().findActiveWithEdge()).thenReturn(List.of(blockedRoad));
        harness.hazardTimelineCache().reload(harness.snapshot(), harness.activePlan().sessionEpoch());

        LocalDateTime repairNow = epoch.plusSeconds(90); // bucket 6
        InstructionSet repair = harness.repairService()
                .onEvent(Set.of(SLOT_A_DIRECT), Set.of(), repairNow);

        // Exactly wave 1 — not wave 0 (already fully crossed), not party B (unrelated edge).
        assertEquals(1, repair.committed().size());
        assertTrue(repair.shortfalls().isEmpty(), "a detour exists, so nobody should be stranded");
        DispatchResult wave1After = repair.committed().get(0);
        assertEquals(PARTY_A_ID, wave1After.partyId());
        assertEquals(1, wave1After.waveIndex());
        assertEquals(PARTY_A_PEOPLE - MAX_PLATOON_SIZE, wave1After.size());

        // The new route avoids the blocked edge and goes the long way round.
        TimedWalk repairedWalk = wave1After.searchResult().walk();
        assertFalse(usesSlot(repairedWalk, SLOT_A_DIRECT));
        assertTrue(usesSlot(repairedWalk, SLOT_A_TO_DETOUR));
        assertTrue(usesSlot(repairedWalk, SLOT_DETOUR_TO_SHELTER));

        // Wave 0 and party B are untouched — the literal same objects, not recomputed equals.
        assertSame(wave0Before,
                harness.activePlan().planBook().get(wave0Before.platoonId()).orElseThrow());
        assertSame(partyBBefore,
                harness.activePlan().planBook().get(partyBBefore.platoonId()).orElseThrow());

        // Wave 0's already-elapsed reservation is untouched history...
        assertEquals(MAX_PLATOON_SIZE,
                harness.activePlan().ledger().edgeOccupancyAt(SLOT_A_DIRECT, 2));
        // ...while wave 1's old, now-abandoned future window on the direct crossing is empty.
        assertEquals(0,
                harness.activePlan().ledger().edgeOccupancyAt(SLOT_A_DIRECT, 11));

        log.info("Repair set: {} platoon(s) replanned, {} stranded — wave 0 at bucket [0,4) preserved, "
                        + "wave 1 rerouted via the detour",
                repair.committed().size(), repair.shortfalls().size());
    }

    @Test
    @DisplayName("A platoon with no feasible alternative is reported as a shortfall, not silently dropped")
    void strandedPlatoonIsReportedAsAShortfall() {
        Harness harness = buildHarness(buildSnapshotWithoutDetour());
        LocalDateTime epoch = LocalDateTime.now();

        InstructionSet dispatched = harness.dispatchService().plan(
                List.of(new Party(PARTY_A_ID, ORIGIN_A, MAX_PLATOON_SIZE, EvacuationPriority.MEDIUM,
                        false, epoch)),
                epoch);
        assertTrue(dispatched.shortfalls().isEmpty());
        assertEquals(1, dispatched.committed().size());
        DispatchResult before = dispatched.committed().get(0);
        assertTrue(usesSlot(before.searchResult().walk(), SLOT_A_DIRECT));

        RoadEdge blockedEdge = RoadEdge.builder().edgeId(EDGE_DB_ID_A_DIRECT).build();
        BlockedRoad blockedRoad = BlockedRoad.builder().roadEdge(blockedEdge).active(true).build();
        when(harness.blockedRoadRepository().findActiveWithEdge()).thenReturn(List.of(blockedRoad));
        harness.hazardTimelineCache().reload(harness.snapshot(), harness.activePlan().sessionEpoch());

        InstructionSet repair = harness.repairService().onEvent(Set.of(SLOT_A_DIRECT), Set.of(), epoch);

        assertTrue(repair.committed().isEmpty(), "there is no alternative route to commit");
        assertEquals(1, repair.shortfalls().size());
        InstructionSet.Shortfall shortfall = repair.shortfalls().get(0);
        assertEquals(PARTY_A_ID, shortfall.partyId());
        assertEquals(MAX_PLATOON_SIZE, shortfall.unroutedSize());
        assertEquals(before.waveIndex(), shortfall.failedAtWaveIndex());

        // A stranded platoon holds no plan: repair removed it and never re-committed anything.
        assertTrue(harness.activePlan().planBook().get(before.platoonId()).isEmpty());
        // Its future reservation was genuinely given back, not merely orphaned in the plan book.
        assertEquals(0, harness.activePlan().ledger().edgeOccupancyAt(SLOT_A_DIRECT, 1));
    }

    /**
     * The exit test for storing {@code Destination} on {@link DispatchResult}: a platoon routed to a
     * destination the requester chose for themselves — not a shelter — must be repaired to the exact
     * same destination, never silently falling back to "nearest shelter" once its original route is
     * torn up.
     */
    @Test
    @DisplayName("A repaired FixedNode platoon is re-routed to the exact same chosen destination")
    void repairedFixedNodePlatoonKeepsTheSameDestination() {
        Harness harness = buildHarness(buildSnapshotWithDetour());
        LocalDateTime epoch = LocalDateTime.now();

        // Routed to the shelter's own node directly, by chosen destination rather than by shelter
        // eligibility — the same node a shelter search would land on, reached a different way.
        Party chosenDestinationParty = new Party(PARTY_A_ID, ORIGIN_A, MAX_PLATOON_SIZE,
                EvacuationPriority.MEDIUM, false, epoch, SHELTER_NODE);

        InstructionSet dispatched =
                harness.dispatchService().plan(List.of(chosenDestinationParty), epoch);
        assertTrue(dispatched.shortfalls().isEmpty());
        assertEquals(1, dispatched.committed().size());

        DispatchResult before = dispatched.committed().get(0);
        assertEquals(new Destination.FixedNode(SHELTER_NODE), before.destination());
        assertNull(before.searchResult().shelter(), "a FixedNode route has no shelter to report");
        assertTrue(usesSlot(before.searchResult().walk(), SLOT_A_DIRECT));

        RoadEdge blockedEdge = RoadEdge.builder().edgeId(EDGE_DB_ID_A_DIRECT).build();
        BlockedRoad blockedRoad = BlockedRoad.builder().roadEdge(blockedEdge).active(true).build();
        when(harness.blockedRoadRepository().findActiveWithEdge()).thenReturn(List.of(blockedRoad));
        harness.hazardTimelineCache().reload(harness.snapshot(), harness.activePlan().sessionEpoch());

        InstructionSet repair = harness.repairService()
                .onEvent(Set.of(SLOT_A_DIRECT), Set.of(), epoch.plusSeconds(30));

        assertEquals(1, repair.committed().size());
        assertTrue(repair.shortfalls().isEmpty(), "a detour exists");
        DispatchResult after = repair.committed().get(0);

        // The point of the test: destination identity survives the repair, replayed verbatim rather
        // than re-derived — and the walk actually ends where that destination says it should.
        assertEquals(before.destination(), after.destination());
        assertNull(after.searchResult().shelter());
        assertEquals(SHELTER_NODE, after.searchResult().walk().arrival().nodeIndex());

        // The new route avoids the blocked edge and goes the long way round to reach that same node.
        TimedWalk repairedWalk = after.searchResult().walk();
        assertFalse(usesSlot(repairedWalk, SLOT_A_DIRECT));
        assertTrue(usesSlot(repairedWalk, SLOT_A_TO_DETOUR));
        assertTrue(usesSlot(repairedWalk, SLOT_DETOUR_TO_SHELTER));
    }
}
