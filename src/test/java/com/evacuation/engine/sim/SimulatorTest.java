package com.evacuation.engine.sim;

import com.evacuation.engine.algorithm.AStarShortestPath;
import com.evacuation.engine.algorithm.HaversineHeuristic;
import com.evacuation.engine.algorithm.MultiTargetShelterSearch;
import com.evacuation.engine.algorithm.spacetime.TimeExpandedDijkstra;
import com.evacuation.engine.algorithm.spacetime.TimedWalk;
import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.dispatch.ActivePlan;
import com.evacuation.engine.dispatch.DispatchResult;
import com.evacuation.engine.dispatch.DispatchService;
import com.evacuation.engine.dispatch.ImprovementLoop;
import com.evacuation.engine.dispatch.InstructionSet;
import com.evacuation.engine.dispatch.Party;
import com.evacuation.engine.graph.overlay.TraversalPolicy;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimeline;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 5 exit test: the A/B comparison actually compares something real.
 *
 * <p>Reuses the bridge-funnel topology {@code DispatchServiceTest} already established (a narrow,
 * 25.5-person-per-bucket crossing and a generous 102-person-per-bucket detour) — five parties of
 * ten people each, fifty people total, wanting the narrow crossing at the same bucket.
 *
 * <p><strong>Why the comparison is about oversubscription, not raw cost.</strong>
 * {@link SimulationMetrics.CoreMetrics} alone cannot show independent-Dijkstra losing: it is
 * capacity-blind by construction, so every party gets told the cheapest individual route with none
 * of the contention that would actually exist priced in — its reported cost looks <em>better</em>
 * than STRIDE's, not worse, precisely because it is a fantasy. The claim worth proving is that its
 * plan cannot physically be delivered, while STRIDE's can: fifty people asking for a 25.5-capacity
 * bucket independently all get told yes, and reconstructing occupancy from its own instructions
 * proves it. STRIDE, sharing one ledger, spreads the same demand across both crossings and never
 * exceeds either one.
 */
class SimulatorTest {

    private static final Logger log = LoggerFactory.getLogger(SimulatorTest.class);

    private static final long GRAPH_VERSION = 42L;
    private static final long SHELTER_ID = 1L;
    private static final int NODE_ORIGIN = 0;
    private static final int NODE_SHELTER = 1;
    private static final int SLOT_NARROW = 0;
    private static final int SLOT_WIDE = 1;

    /** 7,200 persons/hour / 240 buckets-per-hour * 0.85 headroom — same figure DispatchServiceTest uses. */
    private static final double NARROW_CAPACITY_PER_BUCKET = 25.5;

    private static final int PARTY_COUNT = 5;
    private static final int PEOPLE_PER_PARTY = 10;
    private static final int TOTAL_PEOPLE = PARTY_COUNT * PEOPLE_PER_PARTY;

    private GraphSnapshot buildSnapshot() {
        long[] dbNodeId = {10L, 20L};
        String[] nodeName = {"North Bank", "South Bank Shelter"};
        double[] nodeLat = {18.5300, 18.5200};
        double[] nodeLon = {73.8500, 73.8500};
        NodeType[] nodeType = {NodeType.INTERSECTION, NodeType.INTERSECTION};
        boolean[] nodeActive = {true, true};
        double[] nodeCapacityPersonsPerHour = {100_000.0, 100_000.0};
        Map<Long, Integer> nodeIdToIndex = Map.of(10L, 0, 20L, 1);

        int[] edgeHead = {0, 2, 2};
        int[] edgeTo = {NODE_SHELTER, NODE_SHELTER};
        long[] edgeDbId = {100L, 200L};
        double[] edgeDistanceKm = {0.3, 1.6};
        // 0.5 min -> 2 buckets (narrow); 2.0 min -> 8 buckets (wide).
        double[] edgeTimeMin = {0.5, 2.0};
        double[] edgeCapacityPersonsPerHour = {7_200.0, 28_800.0};
        RoadStatus[] edgeBaseStatus = {RoadStatus.OPEN, RoadStatus.OPEN};

        GraphSnapshot.ShelterRef shelter = new GraphSnapshot.ShelterRef(
                SHELTER_ID, NODE_SHELTER, "South Bank Shelter", ShelterStatus.AVAILABLE,
                TOTAL_PEOPLE, false, 18.5200, 73.8500);

        return new GraphSnapshot(
                dbNodeId, nodeName, nodeLat, nodeLon, nodeType, nodeActive, nodeCapacityPersonsPerHour,
                nodeIdToIndex, edgeHead, edgeTo, edgeDbId, edgeDistanceKm, edgeTimeMin,
                edgeCapacityPersonsPerHour, edgeBaseStatus, List.of(shelter), GRAPH_VERSION,
                LocalDateTime.now());
    }

    private record Harness(GraphSnapshot snapshot, GraphEngineProperties properties,
                           TimeModel timeModel, HazardTimelineCache hazardTimelineCache,
                           GraphCache graphCache, ActivePlan activePlan,
                           IndependentDijkstraStrategy independentDijkstraStrategy,
                           DispatchService dispatchService, ImprovementLoop improvementLoop,
                           Simulator simulator) {
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
        MultiTargetShelterSearch shelterSearch = new MultiTargetShelterSearch();

        AStarShortestPath aStar = new AStarShortestPath(new HaversineHeuristic(properties));
        IndependentDijkstraStrategy independentDijkstraStrategy =
                new IndependentDijkstraStrategy(search, properties, timeModel);
        DispatchService dispatchService = new DispatchService(graphCache, hazardTimelineCache,
                activePlan, search, shelterSearch, aStar, timeModel, properties);
        ImprovementLoop improvementLoop = new ImprovementLoop(graphCache, hazardTimelineCache,
                activePlan, search, shelterSearch, aStar, timeModel);
        DemandGenerator demandGenerator = new DemandGenerator(42L);

        Simulator simulator = new Simulator(graphCache, hazardTimelineCache, activePlan,
                demandGenerator, independentDijkstraStrategy, dispatchService, improvementLoop,
                timeModel, properties);

        return new Harness(snapshot, properties, timeModel, hazardTimelineCache, graphCache,
                activePlan, independentDijkstraStrategy, dispatchService, improvementLoop, simulator);
    }

    /** Five equal parties, all wanting the narrow crossing at the same instant. */
    private List<Party> buildDemand(LocalDateTime now) {
        List<Party> parties = new ArrayList<>(PARTY_COUNT);
        for (int i = 0; i < PARTY_COUNT; i++) {
            parties.add(new Party(i + 1L, NODE_ORIGIN, PEOPLE_PER_PARTY,
                    EvacuationPriority.MEDIUM, false, now));
        }
        return parties;
    }

    /** Same reconstruction technique as DispatchServiceTest: demand as an operator would receive it. */
    private Map<Long, Integer> occupancyFromInstructions(InstructionSet instructions) {
        Map<Long, Integer> occupancy = new HashMap<>();
        for (DispatchResult result : instructions.committed()) {
            for (TimedWalk.Step step : result.searchResult().walk().steps()) {
                if (step.isWait()) {
                    continue;
                }
                for (int bucket = step.from().bucket(); bucket < step.to().bucket(); bucket++) {
                    long key = ((long) step.edgeSlot() << 32) | bucket;
                    occupancy.merge(key, result.size(), Integer::sum);
                }
            }
        }
        return occupancy;
    }

    private int slotOf(long occupancyKey) {
        return (int) (occupancyKey >>> 32);
    }

    @Test
    @DisplayName("Independent-Dijkstra oversubscribes the narrow crossing that STRIDE-greedy respects")
    void independentDijkstraOversubscribesWhatStrideRespects() {
        Harness harness = buildHarness();
        LocalDateTime now = LocalDateTime.now();
        List<Party> demand = buildDemand(now);

        harness.hazardTimelineCache().reload(harness.snapshot(), now);
        TraversalPolicy policy = new TraversalPolicy(harness.snapshot(), harness.hazardTimelineCache().get());

        InstructionSet independentResult =
                harness.independentDijkstraStrategy().plan(demand, harness.snapshot(), policy);
        InstructionSet greedyResult = harness.dispatchService().plan(demand, now);

        assertEquals(TOTAL_PEOPLE, independentResult.committed().stream()
                .mapToInt(DispatchResult::size).sum(), "every party individually finds a route");
        assertEquals(TOTAL_PEOPLE, greedyResult.committed().stream()
                .mapToInt(DispatchResult::size).sum() + greedyResult.shortfalls().stream()
                .mapToInt(InstructionSet.Shortfall::unroutedSize).sum());

        Map<Long, Integer> independentOccupancy = occupancyFromInstructions(independentResult);
        Map<Long, Integer> greedyOccupancy = occupancyFromInstructions(greedyResult);

        boolean independentOversubscribesNarrow = independentOccupancy.entrySet().stream()
                .anyMatch(cell -> slotOf(cell.getKey()) == SLOT_NARROW
                        && cell.getValue() > NARROW_CAPACITY_PER_BUCKET);
        assertTrue(independentOversubscribesNarrow,
                "independent-Dijkstra must pile all five parties onto the narrow crossing — each "
                        + "party's own private ledger never sees the other four");

        double bucketsPerHour = 3600.0 / harness.timeModel().deltaSeconds();
        double headroom = harness.properties().getDispatch().getCapacityHeadroom();
        for (Map.Entry<Long, Integer> cell : greedyOccupancy.entrySet()) {
            int slot = slotOf(cell.getKey());
            double capacity = harness.snapshot().edgeCapacityPersonsPerHour(slot) / bucketsPerHour * headroom;
            assertTrue(cell.getValue() <= capacity + 1e-9,
                    "STRIDE-greedy slot " + slot + " carries " + cell.getValue()
                            + " people in one bucket, over its " + capacity + " capacity");
        }

        log.info("Independent-Dijkstra narrow-crossing occupancy: {}; STRIDE-greedy: {}",
                independentOccupancy.entrySet().stream()
                        .filter(e -> slotOf(e.getKey()) == SLOT_NARROW).toList(),
                greedyOccupancy.entrySet().stream()
                        .filter(e -> slotOf(e.getKey()) == SLOT_NARROW).toList());
    }

    @Test
    @DisplayName("runComparison shares identical demand across all three strategies and LNS never regresses")
    void runComparisonSharesDemandAndLnsNeverRegresses() {
        Harness harness = buildHarness();
        LocalDateTime now = LocalDateTime.now();

        Simulator.ComparisonResult result =
                harness.simulator().runComparison(PARTY_COUNT, PEOPLE_PER_PARTY, 5, now);

        // Capacity-blind routing never itself reports a shortfall — every party gets told yes,
        // which is exactly the fiction this whole comparison exists to expose.
        assertEquals(0, result.independentDijkstra().strandedCount());
        assertTrue(result.lnsResult().iterationsRun() > 0, "the requested budget should actually run");

        // The core monotonicity claim for this orchestration path specifically: whatever LNS did,
        // it cannot have made the mean cost worse than the greedy pass it started from.
        assertTrue(result.strideWithLns().meanWeightedPersonMinutes()
                        <= result.strideGreedy().meanWeightedPersonMinutes() + 1e-9,
                "LNS must never leave the plan more expensive than STRIDE-greedy alone: "
                        + result.strideWithLns().meanWeightedPersonMinutes() + " vs "
                        + result.strideGreedy().meanWeightedPersonMinutes());
        assertTrue(result.strideWithLns().strandedCount() <= result.strideGreedy().strandedCount(),
                "LNS never retries a stranded platoon, so it can only ever match, not worsen, "
                        + "the greedy pass's own shortfall count");

        log.info("independent={}, greedy={}, greedy+LNS={}, LNS ran {} iterations, accepted {}",
                result.independentDijkstra(), result.strideGreedy(), result.strideWithLns(),
                result.lnsResult().iterationsRun(), result.lnsResult().accepted());
    }
}
