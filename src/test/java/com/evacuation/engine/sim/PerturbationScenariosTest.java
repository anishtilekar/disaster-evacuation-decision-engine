package com.evacuation.engine.sim;

import com.evacuation.engine.algorithm.MultiTargetShelterSearch;
import com.evacuation.engine.algorithm.spacetime.TimeExpandedDijkstra;
import com.evacuation.engine.algorithm.spacetime.TimedWalk;
import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.dispatch.ActivePlan;
import com.evacuation.engine.dispatch.DispatchResult;
import com.evacuation.engine.dispatch.DispatchService;
import com.evacuation.engine.dispatch.InstructionSet;
import com.evacuation.engine.dispatch.Party;
import com.evacuation.engine.dispatch.RepairService;
import com.evacuation.engine.graph.overlay.TraversalPolicy;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies all three {@link PerturbationScenarios} mechanics against real, hand-checkable
 * scenarios — the same rigor {@code RepairServiceTest} and {@code ImprovementLoopTest} already
 * apply to the primitives this class builds on.
 *
 * <p>Each test constructs its own minimal topology rather than sharing one fixture: the three
 * mechanics need genuinely different conditions (a timeline change, a second shelter, a tight
 * capacity that only conflicts once stretched), and forcing them into one shared graph would make
 * each test's premise harder to verify by hand, not easier.
 */
class PerturbationScenariosTest {

    private static final Logger log = LoggerFactory.getLogger(PerturbationScenariosTest.class);
    private static final long GRAPH_VERSION = 42L;
    private static final long SHELTER_ID = 1L;

    private record Harness(GraphSnapshot snapshot, GraphEngineProperties properties,
                           TimeModel timeModel, BlockedRoadRepository blockedRoadRepository,
                           HazardTimelineCache hazardTimelineCache, ActivePlan activePlan,
                           DispatchService dispatchService, PerturbationScenarios perturbations) {
    }

    private Harness buildHarness(GraphSnapshot snapshot) {
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
        DispatchService dispatchService = new DispatchService(graphCache, hazardTimelineCache,
                activePlan, search, new MultiTargetShelterSearch(), timeModel, properties);
        RepairService repairService = new RepairService(
                graphCache, hazardTimelineCache, activePlan, search, timeModel);
        PerturbationScenarios perturbations = new PerturbationScenarios(
                activePlan, hazardTimelineCache, repairService, timeModel, search);

        return new Harness(snapshot, properties, timeModel, blockedRoadRepository,
                hazardTimelineCache, activePlan, dispatchService, perturbations);
    }

    private boolean usesSlot(TimedWalk walk, int slot) {
        return walk.steps().stream().anyMatch(step -> !step.isWait() && step.edgeSlot() == slot);
    }

    // --- Hazard arrives early ---

    private static final int H_ORIGIN = 0;
    private static final int H_SHELTER = 1;
    private static final int H_DETOUR = 2;
    private static final int H_SLOT_DIRECT = 0;
    private static final int H_SLOT_TO_DETOUR = 1;
    private static final int H_SLOT_DETOUR_TO_SHELTER = 2;
    private static final long H_EDGE_DB_ID_DIRECT = 100L;

    private GraphSnapshot buildHazardSnapshot() {
        long[] dbNodeId = {10L, 20L, 30L};
        String[] nodeName = {"Origin", "Shelter", "Detour"};
        double[] nodeLat = {18.5000, 18.5100, 18.5050};
        double[] nodeLon = {73.8500, 73.8500, 73.8550};
        NodeType[] nodeType = {NodeType.INTERSECTION, NodeType.INTERSECTION, NodeType.INTERSECTION};
        boolean[] nodeActive = {true, true, true};
        double[] nodeCapacityPersonsPerHour = {10_000.0, 10_000.0, 10_000.0};
        Map<Long, Integer> nodeIdToIndex = Map.of(10L, 0, 20L, 1, 30L, 2);

        int[] edgeHead = {0, 2, 2, 3};
        int[] edgeTo = {H_SHELTER, H_DETOUR, H_SHELTER};
        long[] edgeDbId = {H_EDGE_DB_ID_DIRECT, 101L, 102L};
        double[] edgeDistanceKm = {0.5, 0.5, 0.5};
        double[] edgeTimeMin = {1.0, 1.0, 1.0};
        double[] edgeCapacityPersonsPerHour = {10_000.0, 10_000.0, 10_000.0};
        RoadStatus[] edgeBaseStatus = {RoadStatus.OPEN, RoadStatus.OPEN, RoadStatus.OPEN};

        GraphSnapshot.ShelterRef shelter = new GraphSnapshot.ShelterRef(
                SHELTER_ID, H_SHELTER, "Test Shelter", ShelterStatus.AVAILABLE,
                1000, false, 18.5100, 73.8500);

        return new GraphSnapshot(
                dbNodeId, nodeName, nodeLat, nodeLon, nodeType, nodeActive, nodeCapacityPersonsPerHour,
                nodeIdToIndex, edgeHead, edgeTo, edgeDbId, edgeDistanceKm, edgeTimeMin,
                edgeCapacityPersonsPerHour, edgeBaseStatus, List.of(shelter), GRAPH_VERSION,
                LocalDateTime.now());
    }

    @Test
    @DisplayName("applyHazardArrivesEarly repairs exactly the platoon on the newly-lethal edge")
    void applyHazardArrivesEarlyRepairsExactlyThePlatoonOnTheNewlyLethalEdge() {
        Harness harness = buildHarness(buildHazardSnapshot());
        LocalDateTime now = LocalDateTime.now();

        InstructionSet dispatched = harness.dispatchService().plan(
                List.of(new Party(1L, H_ORIGIN, 5, EvacuationPriority.MEDIUM, false, now)), now);
        assertTrue(dispatched.shortfalls().isEmpty());
        DispatchResult before = dispatched.committed().get(0);
        assertTrue(usesSlot(before.searchResult().walk(), H_SLOT_DIRECT),
                "the direct edge is cheapest, so the party must take it while nothing is wrong");

        // The hazard "arrives early": the direct edge becomes lethal from now. A BlockedRoad row is
        // used as the trigger rather than a geometrically-tuned HazardEvent — the method under test
        // only ever diffs the compiled timeline, so it cannot tell which repository produced the
        // change, and BlockedRoad reaches the same state without needing onset-bucket arithmetic.
        RoadEdge blockedEdge = RoadEdge.builder().edgeId(H_EDGE_DB_ID_DIRECT).build();
        BlockedRoad blockedRoad = BlockedRoad.builder().roadEdge(blockedEdge).active(true).build();
        when(harness.blockedRoadRepository().findActiveWithEdge()).thenReturn(List.of(blockedRoad));

        PerturbationScenarios.PerturbationResult result =
                harness.perturbations().applyHazardArrivesEarly(harness.snapshot(), now);

        assertEquals(1, result.repairResult().committed().size());
        assertTrue(result.repairResult().shortfalls().isEmpty(), "a detour exists");
        assertEquals(1, result.instructionChurn());
        assertTrue(result.replanLatencyNanos() > 0);

        DispatchResult after = result.repairResult().committed().get(0);
        assertFalse(usesSlot(after.searchResult().walk(), H_SLOT_DIRECT));
        assertTrue(usesSlot(after.searchResult().walk(), H_SLOT_TO_DETOUR));
        assertTrue(usesSlot(after.searchResult().walk(), H_SLOT_DETOUR_TO_SHELTER));

        log.info("Hazard-early repair: {} -> detour route, churn {}",
                before.platoonId(), result.instructionChurn());
    }

    // --- Shelter closes ---

    private static final int S_ORIGIN = 0;
    private static final int S_SHELTER_A = 1;
    private static final int S_SHELTER_B = 2;
    private static final long SHELTER_A_ID = 1L;
    private static final long SHELTER_B_ID = 2L;
    private static final int S_PARTY_SIZE = 10;

    private GraphSnapshot buildTwoShelterSnapshot() {
        long[] dbNodeId = {10L, 20L, 30L};
        String[] nodeName = {"Origin", "Shelter A", "Shelter B"};
        double[] nodeLat = {18.5000, 18.5100, 18.5200};
        double[] nodeLon = {73.8500, 73.8500, 73.8500};
        NodeType[] nodeType = {NodeType.INTERSECTION, NodeType.INTERSECTION, NodeType.INTERSECTION};
        boolean[] nodeActive = {true, true, true};
        double[] nodeCapacityPersonsPerHour = {10_000.0, 10_000.0, 10_000.0};
        Map<Long, Integer> nodeIdToIndex = Map.of(10L, 0, 20L, 1, 30L, 2);

        int[] edgeHead = {0, 2, 2, 2};
        int[] edgeTo = {S_SHELTER_A, S_SHELTER_B};
        long[] edgeDbId = {200L, 201L};
        // Shelter A is the cheaper choice, so an unconstrained party always prefers it first.
        double[] edgeDistanceKm = {0.3, 1.0};
        double[] edgeTimeMin = {0.5, 2.0};
        double[] edgeCapacityPersonsPerHour = {10_000.0, 10_000.0};
        RoadStatus[] edgeBaseStatus = {RoadStatus.OPEN, RoadStatus.OPEN};

        GraphSnapshot.ShelterRef shelterA = new GraphSnapshot.ShelterRef(
                SHELTER_A_ID, S_SHELTER_A, "Shelter A", ShelterStatus.AVAILABLE,
                S_PARTY_SIZE, false, 18.5100, 73.8500);
        GraphSnapshot.ShelterRef shelterB = new GraphSnapshot.ShelterRef(
                SHELTER_B_ID, S_SHELTER_B, "Shelter B", ShelterStatus.AVAILABLE,
                1000, false, 18.5200, 73.8500);

        return new GraphSnapshot(
                dbNodeId, nodeName, nodeLat, nodeLon, nodeType, nodeActive, nodeCapacityPersonsPerHour,
                nodeIdToIndex, edgeHead, edgeTo, edgeDbId, edgeDistanceKm, edgeTimeMin,
                edgeCapacityPersonsPerHour, edgeBaseStatus, List.of(shelterA, shelterB), GRAPH_VERSION,
                LocalDateTime.now());
    }

    @Test
    @DisplayName("applyShelterCloses repairs only platoons assigned to it, others stay byte-identical")
    void applyShelterClosesRepairsOnlyPlatoonsAssignedToIt() {
        Harness harness = buildHarness(buildTwoShelterSnapshot());
        LocalDateTime now = LocalDateTime.now();

        // Shelter A's capacity is exactly party X's size, so party Y is forced to Shelter B even
        // though A would otherwise be its cheaper choice too.
        InstructionSet dispatched = harness.dispatchService().plan(List.of(
                new Party(1L, S_ORIGIN, S_PARTY_SIZE, EvacuationPriority.MEDIUM, false, now),
                new Party(2L, S_ORIGIN, S_PARTY_SIZE, EvacuationPriority.MEDIUM, false, now)), now);
        assertTrue(dispatched.shortfalls().isEmpty());

        DispatchResult atA = dispatched.committed().stream()
                .filter(r -> r.searchResult().shelter().shelterId() == SHELTER_A_ID)
                .findFirst().orElseThrow();
        DispatchResult atB = dispatched.committed().stream()
                .filter(r -> r.searchResult().shelter().shelterId() == SHELTER_B_ID)
                .findFirst().orElseThrow();

        TraversalPolicy policy = new TraversalPolicy(
                harness.snapshot(), harness.hazardTimelineCache().get());
        PerturbationScenarios.PerturbationResult result = harness.perturbations()
                .applyShelterCloses(SHELTER_A_ID, harness.snapshot(), policy, now);

        assertEquals(1, result.repairResult().committed().size());
        assertTrue(result.repairResult().shortfalls().isEmpty(), "Shelter B has ample spare room");
        DispatchResult repaired = result.repairResult().committed().get(0);
        assertEquals(atA.platoonId(), repaired.platoonId());
        assertEquals(SHELTER_B_ID, repaired.searchResult().shelter().shelterId());
        assertEquals(1, result.instructionChurn());

        // The platoon already at B was never assigned to the closed shelter — untouched down to
        // object identity, not merely to an equal outcome.
        assertSame(atB, harness.activePlan().planBook().get(atB.platoonId()).orElseThrow());
    }

    // --- Slow platoon ---

    private static final int P_ORIGIN = 0;
    private static final int P_SHELTER = 1;
    private static final int P_DETOUR = 2;
    private static final int P_SLOT_DIRECT = 0;
    private static final int P_SLOT_TO_DETOUR = 1;
    private static final int P_SLOT_DETOUR_TO_SHELTER = 2;
    private static final long PARTY_ID = 1L;
    private static final int WAVE_SIZE = 5;
    private static final int CONVOY_STAGGER = 10;

    /** Direct edge capacity: 2400/hr -> 8.5/bucket after headroom. One wave (5) fits; two (10) do not. */
    private GraphSnapshot buildSlowPlatoonSnapshot() {
        long[] dbNodeId = {10L, 20L, 30L};
        String[] nodeName = {"Origin", "Shelter", "Detour"};
        double[] nodeLat = {18.5000, 18.5100, 18.5050};
        double[] nodeLon = {73.8500, 73.8500, 73.8550};
        NodeType[] nodeType = {NodeType.INTERSECTION, NodeType.INTERSECTION, NodeType.INTERSECTION};
        boolean[] nodeActive = {true, true, true};
        double[] nodeCapacityPersonsPerHour = {10_000.0, 10_000.0, 10_000.0};
        Map<Long, Integer> nodeIdToIndex = Map.of(10L, 0, 20L, 1, 30L, 2);

        int[] edgeHead = {0, 2, 2, 3};
        int[] edgeTo = {P_SHELTER, P_DETOUR, P_SHELTER};
        long[] edgeDbId = {300L, 301L, 302L};
        double[] edgeDistanceKm = {0.3, 0.5, 0.5};
        // 1.0 min -> 4 buckets on every edge.
        double[] edgeTimeMin = {1.0, 1.0, 1.0};
        double[] edgeCapacityPersonsPerHour = {2_400.0, 10_000.0, 10_000.0};
        RoadStatus[] edgeBaseStatus = {RoadStatus.OPEN, RoadStatus.OPEN, RoadStatus.OPEN};

        GraphSnapshot.ShelterRef shelter = new GraphSnapshot.ShelterRef(
                SHELTER_ID, P_SHELTER, "Test Shelter", ShelterStatus.AVAILABLE,
                1000, false, 18.5100, 73.8500);

        return new GraphSnapshot(
                dbNodeId, nodeName, nodeLat, nodeLon, nodeType, nodeActive, nodeCapacityPersonsPerHour,
                nodeIdToIndex, edgeHead, edgeTo, edgeDbId, edgeDistanceKm, edgeTimeMin,
                edgeCapacityPersonsPerHour, edgeBaseStatus, List.of(shelter), GRAPH_VERSION,
                LocalDateTime.now());
    }

    private GraphEngineProperties slowPlatoonProperties() {
        GraphEngineProperties properties = new GraphEngineProperties();
        properties.getDispatch().setMaxPlatoonSize(WAVE_SIZE);
        properties.getDispatch().setConvoyStaggerBuckets(CONVOY_STAGGER);
        return properties;
    }

    private Harness buildSlowPlatoonHarness() {
        GraphSnapshot snapshot = buildSlowPlatoonSnapshot();
        GraphEngineProperties properties = slowPlatoonProperties();
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
        DispatchService dispatchService = new DispatchService(graphCache, hazardTimelineCache,
                activePlan, search, new MultiTargetShelterSearch(), timeModel, properties);
        RepairService repairService = new RepairService(
                graphCache, hazardTimelineCache, activePlan, search, timeModel);
        PerturbationScenarios perturbations = new PerturbationScenarios(
                activePlan, hazardTimelineCache, repairService, timeModel, search);

        return new Harness(snapshot, properties, timeModel, blockedRoadRepository,
                hazardTimelineCache, activePlan, dispatchService, perturbations);
    }

    /** Dispatches one 10-person party as two staggered waves of 5, both on the tight direct edge. */
    private List<DispatchResult> dispatchTwoWaves(Harness harness, LocalDateTime now) {
        InstructionSet dispatched = harness.dispatchService().plan(
                List.of(new Party(PARTY_ID, P_ORIGIN, 2 * WAVE_SIZE, EvacuationPriority.MEDIUM,
                        false, now)),
                now);
        assertTrue(dispatched.shortfalls().isEmpty());
        assertEquals(2, dispatched.committed().size());
        for (DispatchResult result : dispatched.committed()) {
            assertTrue(usesSlot(result.searchResult().walk(), P_SLOT_DIRECT));
        }
        return dispatched.committed();
    }

    @Test
    @DisplayName("applySlowPlatoon attempts displacement, and rejects the slowdown cleanly when it "
            + "reconverges rather than corrupting the ledger")
    void applySlowPlatoonRejectsCleanlyWhenDisplacementReconverges() {
        Harness harness = buildSlowPlatoonHarness();
        LocalDateTime now = LocalDateTime.now();
        List<DispatchResult> waves = dispatchTwoWaves(harness, now);

        DispatchResult wave0 = waves.stream().filter(r -> r.waveIndex() == 0).findFirst().orElseThrow();
        DispatchResult wave1 = waves.stream().filter(r -> r.waveIndex() == 1).findFirst().orElseThrow();
        assertEquals(0, wave0.searchResult().walk().origin().bucket());
        assertEquals(CONVOY_STAGGER, wave1.searchResult().walk().origin().bucket());

        TraversalPolicy policy = new TraversalPolicy(
                harness.snapshot(), harness.hazardTimelineCache().get());

        // Stretch wave 0 by 3x: its 4-bucket crossing becomes 12 buckets, [0,12) — overlapping
        // wave 1's untouched [10,14) at buckets [10,12), where 5 (wave 1) + 5 (stretched wave 0)
        // = 10 exceeds the direct edge's 8.5-per-bucket capacity.
        //
        // This is a genuine, honest limit of the mechanic, not a bug: repair relocates wave 1
        // before wave 0's stretched walk is ever reserved, so from repair's own search the direct
        // edge looks completely free — the cheapest option — and it correctly (from what it can
        // see) sends wave 1 right back to the exact window that will conflict again. Wave 0's own
        // reservation attempt then fails a second time, and the code falls back to rollback rather
        // than forcing anything through. slowdownAbsorbed exists precisely to carry this outcome.
        PerturbationScenarios.SlowPlatoonResult result = harness.perturbations().applySlowPlatoon(
                wave0.platoonId(), 3.0, harness.snapshot(), policy, now);

        assertFalse(result.slowdownAbsorbed(),
                "the direct edge is wave 1's own cheapest option regardless of wave 0's pending "
                        + "need, so repair cannot durably clear the way here");

        // Repair still ran and "succeeded" on its own terms — it found wave 1 a feasible route.
        // That the route happens to be the same contested slot is exactly the point being proven.
        assertEquals(1, result.conflictRepair().repairResult().committed().size());
        DispatchResult repairedWave1 = result.conflictRepair().repairResult().committed().get(0);
        assertEquals(wave1.platoonId(), repairedWave1.platoonId());
        assertTrue(usesSlot(repairedWave1.searchResult().walk(), P_SLOT_DIRECT));

        // Wave 0 itself is restored exactly as it was — the same object, not a re-created equal one.
        assertSame(wave0, harness.activePlan().planBook().get(wave0.platoonId()).orElseThrow());

        log.info("Slow platoon: displacement reconverged onto the direct edge; slowdown rejected, "
                + "wave 0 restored to its original route");
    }

    @Test
    @DisplayName("applySlowPlatoon with no resulting conflict needs no repair call")
    void applySlowPlatoonWithNoConflictNeedsNoRepairCall() {
        Harness harness = buildSlowPlatoonHarness();
        LocalDateTime now = LocalDateTime.now();
        List<DispatchResult> waves = dispatchTwoWaves(harness, now);
        DispatchResult wave0 = waves.stream().filter(r -> r.waveIndex() == 0).findFirst().orElseThrow();

        TraversalPolicy policy = new TraversalPolicy(
                harness.snapshot(), harness.hazardTimelineCache().get());

        // A mild stretch (1.2x -> 4 buckets become 5, [0,5)) never reaches wave 1's [10,14) window.
        PerturbationScenarios.SlowPlatoonResult result = harness.perturbations().applySlowPlatoon(
                wave0.platoonId(), 1.2, harness.snapshot(), policy, now);

        assertTrue(result.slowdownAbsorbed());
        assertTrue(result.conflictRepair().repairResult().committed().isEmpty());
        assertTrue(result.conflictRepair().repairResult().shortfalls().isEmpty());
        assertEquals(0L, result.conflictRepair().replanLatencyNanos(),
                "no repair call means no latency to measure, not zero measured latency");
        assertEquals(1, result.conflictRepair().instructionChurn(),
                "wave 0 itself still changed route, even though nobody else was touched");
    }

    @Test
    @DisplayName("applySlowPlatoon that would overrun the horizon rolls back cleanly instead of throwing")
    void applySlowPlatoonThatOverrunsTheHorizonRollsBackCleanly() {
        Harness harness = buildSlowPlatoonHarness();
        LocalDateTime now = LocalDateTime.now();
        List<DispatchResult> waves = dispatchTwoWaves(harness, now);
        DispatchResult wave0 = waves.stream().filter(r -> r.waveIndex() == 0).findFirst().orElseThrow();

        TraversalPolicy policy = new TraversalPolicy(
                harness.snapshot(), harness.hazardTimelineCache().get());
        int horizon = harness.activePlan().ledger().horizonBuckets();

        // A large enough factor pushes the stretched arrival at or past the 160-bucket horizon.
        double extremeFactor = (double) (horizon + 10) / 4.0;

        PerturbationScenarios.SlowPlatoonResult result = harness.perturbations().applySlowPlatoon(
                wave0.platoonId(), extremeFactor, harness.snapshot(), policy, now);

        assertFalse(result.slowdownAbsorbed());
        assertTrue(result.conflictRepair().repairResult().committed().isEmpty());
        assertEquals(0L, result.conflictRepair().replanLatencyNanos());

        // Wave 0 is restored exactly as it was — same object, not a re-created equal one.
        assertSame(wave0, harness.activePlan().planBook().get(wave0.platoonId()).orElseThrow());
    }
}
