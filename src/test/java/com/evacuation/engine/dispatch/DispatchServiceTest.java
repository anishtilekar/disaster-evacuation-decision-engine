package com.evacuation.engine.dispatch;

import com.evacuation.engine.algorithm.MultiTargetShelterSearch;
import com.evacuation.engine.algorithm.spacetime.TimeExpandedDijkstra;
import com.evacuation.engine.algorithm.spacetime.TimedWalk;
import com.evacuation.engine.config.GraphEngineProperties;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3 exit test: the bridge funnel.
 *
 * <p>A crowd north of the river, shelters south, and two ways across — a fast narrow bridge and a
 * slow wide detour. Routed independently, all twelve parties compute the same shortest path over
 * the same bridge and are each promised the same crossing time, an answer that is correct for any
 * one of them and false for the group: the bridge cannot hold three hundred people at once, so the
 * tail of that crowd arrives far later than promised, compressed into a queue nobody planned.
 *
 * <p>What is asserted here is not that dispatch produces some particular route, but that the plan
 * it produces is <em>physically deliverable</em>: no arc is oversubscribed in any bucket, no
 * shelter takes more people than it has room for, every person is accounted for, and the whole
 * crowd clears sooner than the narrow bridge alone could ever have carried them. The occupancy
 * check deliberately reconstructs demand from the returned instructions rather than reading the
 * ledger's own bookkeeping — that way it verifies the plan as an operator would receive it, not
 * merely that the ledger agreed with itself.
 *
 * <p>The fixture's numbers are chosen to be checkable by hand. At a 15-second bucket there are 240
 * buckets in an hour and the configured headroom is 0.85, so a 7,200 persons/hour bridge allows
 * {@code 7200 / 240 * 0.85 = 25.5} people per bucket, and the 28,800 persons/hour detour allows
 * 102. Junctions are deliberately given far more room than either road, so this test is about arc
 * capacity and nothing else.
 */
class DispatchServiceTest {

    private static final Logger log = LoggerFactory.getLogger(DispatchServiceTest.class);

    private static final long GRAPH_VERSION = 42L;
    private static final long SHELTER_ID = 1L;

    private static final int NODE_ORIGIN = 0;
    private static final int NODE_SHELTER = 1;

    /** Fast and narrow: 2 buckets to cross, 25.5 people per bucket after headroom. */
    private static final int SLOT_NARROW_BRIDGE = 0;
    private static final int NARROW_TAU_BUCKETS = 2;
    private static final double NARROW_CAPACITY_PER_BUCKET = 25.5;

    /** Slow and wide: 8 buckets to cross, 102 people per bucket after headroom. */
    private static final int SLOT_WIDE_DETOUR = 1;

    private static final int PARTY_COUNT = 12;
    private static final int PEOPLE_PER_PARTY = 25;
    private static final int TOTAL_PEOPLE = PARTY_COUNT * PEOPLE_PER_PARTY;

    private static final int MAX_PLATOON_SIZE = 10;

    /** Room for exactly the whole crowd, so nobody is turned away for lack of shelter space. */
    private static final int SHELTER_CAPACITY = TOTAL_PEOPLE;

    private static final double EPSILON = 1e-9;

    /**
     * Two nodes, two parallel crossings between them. Everything the scenario needs and nothing it
     * does not: the interesting behaviour is entirely in how demand distributes across the two arcs.
     */
    private GraphSnapshot buildRiverCrossingSnapshot(int shelterCapacity) {
        long[] dbNodeId = {10L, 20L};
        String[] nodeName = {"North Bank", "South Bank Shelter"};
        double[] nodeLat = {18.5300, 18.5200};
        double[] nodeLon = {73.8500, 73.8500};
        NodeType[] nodeType = {NodeType.INTERSECTION, NodeType.INTERSECTION};
        boolean[] nodeActive = {true, true};
        // Junctions are not the bottleneck under test; give them room for the entire crowd at once.
        double[] nodeCapacityPersonsPerHour = {100_000.0, 100_000.0};
        Map<Long, Integer> nodeIdToIndex = Map.of(10L, 0, 20L, 1);

        // Both crossings leave node 0 for node 1: slots [0, 2) belong to node 0, node 1 has none.
        int[] edgeHead = {0, 2, 2};
        int[] edgeTo = {NODE_SHELTER, NODE_SHELTER};
        long[] edgeDbId = {100L, 200L};
        double[] edgeDistanceKm = {0.3, 1.6};
        // 0.5 min -> ceil(30/15) = 2 buckets; 2.0 min -> ceil(120/15) = 8 buckets.
        double[] edgeTimeMin = {0.5, 2.0};
        double[] edgeCapacityPersonsPerHour = {7_200.0, 28_800.0};
        RoadStatus[] edgeBaseStatus = {RoadStatus.OPEN, RoadStatus.OPEN};

        GraphSnapshot.ShelterRef shelter = new GraphSnapshot.ShelterRef(
                SHELTER_ID, NODE_SHELTER, "South Bank Shelter", ShelterStatus.AVAILABLE,
                shelterCapacity, false, 18.5200, 73.8500);

        return new GraphSnapshot(
                dbNodeId, nodeName, nodeLat, nodeLon, nodeType, nodeActive, nodeCapacityPersonsPerHour,
                nodeIdToIndex, edgeHead, edgeTo, edgeDbId, edgeDistanceKm, edgeTimeMin,
                edgeCapacityPersonsPerHour, edgeBaseStatus, List.of(shelter), GRAPH_VERSION,
                LocalDateTime.now());
    }

    private GraphEngineProperties properties() {
        GraphEngineProperties properties = new GraphEngineProperties();
        properties.getDispatch().setMaxPlatoonSize(MAX_PLATOON_SIZE);
        return properties;
    }

    /** A hazard-free timeline, compiled by the real compiler over empty overlay rows. */
    private HazardTimeline compileHazardFree(GraphSnapshot snapshot, TimeModel timeModel) {
        BlockedRoadRepository blockedRoadRepository = mock(BlockedRoadRepository.class);
        when(blockedRoadRepository.findActiveWithEdge()).thenReturn(List.of());

        HazardEventRepository hazardEventRepository = mock(HazardEventRepository.class);
        when(hazardEventRepository.findByActive(true)).thenReturn(List.of());

        return new HazardTimelineCompiler(blockedRoadRepository, hazardEventRepository, timeModel)
                .compile(snapshot);
    }

    private DispatchService buildDispatchService(GraphSnapshot snapshot,
                                                 GraphEngineProperties properties) {
        TimeModel timeModel = new TimeModel(properties);

        // Compiled up front: the compiler stubs mocks of its own, and Mockito cannot cope with that
        // happening inside a when(...) argument.
        HazardTimeline timeline = compileHazardFree(snapshot, timeModel);

        GraphCache graphCache = mock(GraphCache.class);
        when(graphCache.get()).thenReturn(snapshot);

        HazardTimelineCache hazardTimelineCache = mock(HazardTimelineCache.class);
        when(hazardTimelineCache.isLoaded()).thenReturn(false);
        // DispatchService anchors the very first compile of a session to whatever "now" that
        // session's first plan(...) call used, so the epoch argument varies test to test.
        when(hazardTimelineCache.reload(any(GraphSnapshot.class), any(LocalDateTime.class)))
                .thenReturn(timeline);

        return new DispatchService(
                graphCache,
                hazardTimelineCache,
                new ActivePlan(),
                new TimeExpandedDijkstra(timeModel, properties),
                new MultiTargetShelterSearch(),
                timeModel,
                properties);
    }

    /** Twelve equal parties, all waiting at the north bank, all wanting out now. */
    private List<Party> buildCrowd(LocalDateTime now) {
        List<Party> parties = new ArrayList<>(PARTY_COUNT);
        for (int i = 0; i < PARTY_COUNT; i++) {
            parties.add(new Party(
                    i + 1L, NODE_ORIGIN, PEOPLE_PER_PARTY,
                    EvacuationPriority.MEDIUM, false, now));
        }
        return parties;
    }

    /**
     * Rebuilds per-{@code (slot, bucket)} demand from the committed instructions themselves, so the
     * capacity check tests the plan an operator would act on rather than the ledger's own accounting.
     */
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

    private Set<Integer> crossingsUsed(InstructionSet instructions) {
        Set<Integer> slots = new HashSet<>();
        for (DispatchResult result : instructions.committed()) {
            for (TimedWalk.Step step : result.searchResult().walk().steps()) {
                if (!step.isWait()) {
                    slots.add(step.edgeSlot());
                }
            }
        }
        return slots;
    }

    private int makespanBuckets(InstructionSet instructions) {
        return instructions.committed().stream()
                .mapToInt(result -> result.searchResult().walk().arrival().bucket())
                .max()
                .orElse(0);
    }

    @Test
    @DisplayName("The whole crowd is placed, nobody is lost in the split, and every person is accounted for")
    void everyPersonIsPlacedAndAccountedFor() {
        GraphSnapshot snapshot = buildRiverCrossingSnapshot(SHELTER_CAPACITY);
        DispatchService dispatchService = buildDispatchService(snapshot, properties());

        InstructionSet instructions = dispatchService.plan(buildCrowd(LocalDateTime.now()), LocalDateTime.now());

        int placed = instructions.committed().stream().mapToInt(DispatchResult::size).sum();
        int short_ = instructions.shortfalls().stream()
                .mapToInt(InstructionSet.Shortfall::unroutedSize).sum();

        assertEquals(TOTAL_PEOPLE, placed + short_, "conservation: nobody invented or dropped");
        assertTrue(instructions.shortfalls().isEmpty(),
                "the network has room for everyone, so nobody should come up short");
        assertEquals(TOTAL_PEOPLE, placed);
    }

    @Test
    @DisplayName("No arc is oversubscribed in any bucket — the plan is physically deliverable")
    void noArcIsOversubscribedInAnyBucket() {
        GraphSnapshot snapshot = buildRiverCrossingSnapshot(SHELTER_CAPACITY);
        DispatchService dispatchService = buildDispatchService(snapshot, properties());

        InstructionSet instructions = dispatchService.plan(buildCrowd(LocalDateTime.now()), LocalDateTime.now());

        Map<Long, Integer> occupancy = occupancyFromInstructions(instructions);
        assertTrue(occupancy.size() > 0, "the plan should actually use some arcs");

        double bucketsPerHour = 3600.0 / new TimeModel(properties()).deltaSeconds();
        double headroom = properties().getDispatch().getCapacityHeadroom();

        for (Map.Entry<Long, Integer> cell : occupancy.entrySet()) {
            int slot = slotOf(cell.getKey());
            double capacity =
                    snapshot.edgeCapacityPersonsPerHour(slot) / bucketsPerHour * headroom;

            assertTrue(cell.getValue() <= capacity + EPSILON,
                    "slot " + slot + " carries " + cell.getValue()
                            + " people in one bucket, over its " + capacity + " capacity");
        }
    }

    @Test
    @DisplayName("Demand spreads across both crossings rather than funnelling onto the fast one")
    void demandSpreadsAcrossBothCrossings() {
        GraphSnapshot snapshot = buildRiverCrossingSnapshot(SHELTER_CAPACITY);
        DispatchService dispatchService = buildDispatchService(snapshot, properties());

        InstructionSet instructions = dispatchService.plan(buildCrowd(LocalDateTime.now()), LocalDateTime.now());

        Set<Integer> used = crossingsUsed(instructions);
        log.info("Crossings used by the committed plan: {}", used);

        assertTrue(used.contains(SLOT_NARROW_BRIDGE), "the fast bridge should carry its share");
        assertTrue(used.contains(SLOT_WIDE_DETOUR),
                "once the fast bridge fills, the detour must take the overflow — this is the whole "
                        + "point of routing against reservations rather than independently");
    }

    @Test
    @DisplayName("The crowd clears sooner than the narrow bridge alone could ever have carried it")
    void makespanBeatsTheSingleCorridorBound() {
        GraphSnapshot snapshot = buildRiverCrossingSnapshot(SHELTER_CAPACITY);
        DispatchService dispatchService = buildDispatchService(snapshot, properties());

        InstructionSet instructions = dispatchService.plan(buildCrowd(LocalDateTime.now()), LocalDateTime.now());

        // What the naive answer actually costs once capacity is taken seriously. Every person
        // occupies the bridge for its full crossing time, so the crowd needs
        // (people * tau) person-buckets of it, delivered at capacity per bucket — and the last
        // group still has to finish crossing after that.
        int singleCorridorBound =
                (int) Math.ceil(TOTAL_PEOPLE * NARROW_TAU_BUCKETS / NARROW_CAPACITY_PER_BUCKET)
                        + NARROW_TAU_BUCKETS;

        int makespan = makespanBuckets(instructions);
        log.info("Makespan: {} buckets, versus a single-corridor bound of {} buckets",
                makespan, singleCorridorBound);

        assertTrue(makespan < singleCorridorBound,
                "spreading across both crossings should clear the crowd faster than the narrow "
                        + "bridge alone (" + makespan + " vs " + singleCorridorBound + " buckets)");
    }

    @Test
    @DisplayName("A shelter never takes more people than it has room for, and the surplus is reported")
    void shelterCapacityIsChargedAtAssignment() {
        // Room for less than half the crowd: the rest must be reported, not quietly over-assigned.
        int limitedCapacity = 100;
        GraphSnapshot snapshot = buildRiverCrossingSnapshot(limitedCapacity);
        DispatchService dispatchService = buildDispatchService(snapshot, properties());

        InstructionSet instructions = dispatchService.plan(buildCrowd(LocalDateTime.now()), LocalDateTime.now());

        Map<Long, Integer> assignedByShelter = new HashMap<>();
        for (DispatchResult result : instructions.committed()) {
            assignedByShelter.merge(
                    result.searchResult().shelter().shelterId(), result.size(), Integer::sum);
        }

        int assigned = assignedByShelter.getOrDefault(SHELTER_ID, 0);
        assertTrue(assigned <= limitedCapacity,
                "shelter took " + assigned + " people into " + limitedCapacity + " places");

        int short_ = instructions.shortfalls().stream()
                .mapToInt(InstructionSet.Shortfall::unroutedSize).sum();
        assertEquals(TOTAL_PEOPLE, assigned + short_,
                "everyone is either placed or explicitly reported as short");
        assertTrue(short_ > 0, "with room for only " + limitedCapacity
                + ", some of the crowd must be reported as unplaced");
    }
}
