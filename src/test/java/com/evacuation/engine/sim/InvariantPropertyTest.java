package com.evacuation.engine.sim;

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
import com.evacuation.engine.dispatch.ReservationLedger;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimeline;
import com.evacuation.engine.graph.time.HazardTimelineCache;
import com.evacuation.engine.graph.time.HazardTimelineCompiler;
import com.evacuation.engine.graph.time.TimeModel;
import com.evacuation.engine.loader.GraphCache;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The design's property/invariant suite: the safety and conservation guarantees the engine claims
 * "by construction", asserted across many randomized topologies and demand patterns rather than the
 * hand-picked scenarios the per-component tests already cover.
 *
 * <p>The distinction matters. Every existing test proves its invariant on a graph chosen precisely
 * to exercise it; these prove the same invariants hold on graphs nobody designed, including ones
 * where demand is unroutable, shelters are too small, or the topology is disconnected. A generator
 * that guaranteed routable, well-formed scenarios would be testing an easier world than the real
 * one, so {@link RandomGraphGenerator} deliberately guarantees no connectivity and these tests
 * treat stranding as a legitimate outcome to hold invariants under, never a failure.
 *
 * <p>Plain JUnit loops over seeded generators rather than a property-testing framework — no new
 * dependency, and a failing seed is printed and directly reproducible, which is the only shrinking
 * behaviour these invariants actually need.
 */
class InvariantPropertyTest {

    private static final Logger log = LoggerFactory.getLogger(InvariantPropertyTest.class);

    private static final int TRIAL_COUNT = 40;
    private static final int MIN_NODES = 3;
    private static final int MAX_NODES = 12;
    private static final int PARTY_COUNT = 6;
    private static final double MEAN_GROUP_SIZE = 8.0;
    private static final double EPSILON = 1e-9;

    private record Harness(GraphSnapshot snapshot, GraphEngineProperties properties,
                           TimeModel timeModel, ActivePlan activePlan,
                           DispatchService dispatchService, ImprovementLoop improvementLoop,
                           HazardTimeline timeline) {
    }

    /** A full engine wired against one random snapshot, with a hazard-free compiled timeline. */
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
        MultiTargetShelterSearch shelterSearch = new MultiTargetShelterSearch();
        DispatchService dispatchService = new DispatchService(graphCache, hazardTimelineCache,
                activePlan, search, shelterSearch, timeModel, properties);
        ImprovementLoop improvementLoop = new ImprovementLoop(graphCache, hazardTimelineCache,
                activePlan, search, shelterSearch, timeModel);

        HazardTimeline timeline = hazardTimelineCache.reload(snapshot, LocalDateTime.now());

        return new Harness(snapshot, properties, timeModel, activePlan, dispatchService,
                improvementLoop, timeline);
    }

    private List<Party> demandFor(GraphSnapshot snapshot, long seed, LocalDateTime now) {
        return new DemandGenerator(seed).generate(snapshot, PARTY_COUNT, MEAN_GROUP_SIZE, now);
    }

    private GraphSnapshot randomSnapshot(long seed) {
        RandomGraphGenerator generator = new RandomGraphGenerator(seed);
        int nodeCount = MIN_NODES + (int) (seed % (MAX_NODES - MIN_NODES + 1));
        return generator.generate(nodeCount, 0.35);
    }

    // --- Generator self-check: everything below depends on this being right ---

    @Test
    @DisplayName("RandomGraphGenerator emits structurally valid CSR across many seeds")
    void generatorEmitsStructurallyValidCsr() {
        for (long seed = 1; seed <= TRIAL_COUNT; seed++) {
            GraphSnapshot snapshot = randomSnapshot(seed);
            String context = "seed=" + seed;

            assertTrue(snapshot.nodeCount() >= MIN_NODES, context);
            assertEquals(0, snapshot.slotsStart(0), context + " CSR must start at 0");
            assertEquals(snapshot.edgeSlotCount(), snapshot.slotsEnd(snapshot.nodeCount() - 1),
                    context + " last node's slot range must end at edgeSlotCount");

            int previousEnd = 0;
            for (int node = 0; node < snapshot.nodeCount(); node++) {
                int start = snapshot.slotsStart(node);
                int end = snapshot.slotsEnd(node);
                assertEquals(previousEnd, start,
                        context + " node " + node + " must start where the previous node ended");
                assertTrue(end >= start, context + " node " + node + " has an inverted slot range");

                for (int slot = start; slot < end; slot++) {
                    int destination = snapshot.edgeTo(slot);
                    assertTrue(destination >= 0 && destination < snapshot.nodeCount(),
                            context + " slot " + slot + " points outside the node set");
                    assertTrue(snapshot.edgeCapacityPersonsPerHour(slot) > 0, context);
                    assertTrue(snapshot.edgeTimeMin(slot) > 0, context);
                }
                previousEnd = end;
            }

            for (GraphSnapshot.ShelterRef shelter : snapshot.shelters()) {
                assertTrue(shelter.nodeIndex() >= 0 && shelter.nodeIndex() < snapshot.nodeCount(),
                        context + " shelter is snapped outside the node set");
            }
        }
    }

    @Test
    @DisplayName("The same seed reproduces an identical graph and identical demand")
    void generationIsDeterministicUnderSeed() {
        for (long seed = 1; seed <= 10; seed++) {
            GraphSnapshot first = randomSnapshot(seed);
            GraphSnapshot second = randomSnapshot(seed);
            String context = "seed=" + seed;

            assertEquals(first.nodeCount(), second.nodeCount(), context);
            assertEquals(first.edgeSlotCount(), second.edgeSlotCount(), context);
            for (int slot = 0; slot < first.edgeSlotCount(); slot++) {
                assertEquals(first.edgeTo(slot), second.edgeTo(slot), context + " slot " + slot);
                assertEquals(first.edgeTimeMin(slot), second.edgeTimeMin(slot), EPSILON, context);
                assertEquals(first.edgeCapacityPersonsPerHour(slot),
                        second.edgeCapacityPersonsPerHour(slot), EPSILON, context);
            }

            LocalDateTime now = LocalDateTime.now();
            List<Party> demandA = demandFor(first, seed, now);
            List<Party> demandB = demandFor(second, seed, now);
            assertEquals(demandA, demandB, context + " identical seeds must reproduce demand exactly");
        }
    }

    // --- Dispatch invariants, across random topologies ---

    @Test
    @DisplayName("Across random graphs: conservation, capacity, and shelter limits always hold")
    void dispatchAlwaysRespectsItsInvariants() {
        int trialsWithCommittedRoutes = 0;

        for (long seed = 1; seed <= TRIAL_COUNT; seed++) {
            GraphSnapshot snapshot = randomSnapshot(seed);
            Harness harness = buildHarness(snapshot);
            LocalDateTime now = LocalDateTime.now();
            List<Party> demand = demandFor(snapshot, seed, now);
            String context = "seed=" + seed;

            InstructionSet instructions = harness.dispatchService().plan(demand, now);

            // Conservation: everyone is either committed or explicitly reported short.
            int demanded = demand.stream().mapToInt(Party::numberOfPeople).sum();
            int placed = instructions.committed().stream().mapToInt(DispatchResult::size).sum();
            int stranded = instructions.shortfalls().stream()
                    .mapToInt(InstructionSet.Shortfall::unroutedSize).sum();
            assertEquals(demanded, placed + stranded,
                    context + " conservation violated: " + demanded + " demanded, "
                            + placed + " placed, " + stranded + " stranded");

            if (!instructions.committed().isEmpty()) {
                trialsWithCommittedRoutes++;
            }

            assertNoArcOversubscribed(instructions, snapshot, harness, context);
            assertNoShelterOversubscribed(instructions, snapshot, context);
            assertNoLethalCellOccupied(instructions, harness.timeline(), context);
        }

        // Guards against the suite silently passing because nothing was ever actually routed —
        // every invariant above is vacuously true on an empty plan.
        assertTrue(trialsWithCommittedRoutes > TRIAL_COUNT / 2,
                "only " + trialsWithCommittedRoutes + " of " + TRIAL_COUNT
                        + " trials routed anything; the generator is producing too few usable graphs "
                        + "for these invariants to be meaningfully exercised");
        log.info("Dispatch invariants held across {} random graphs ({} routed at least one platoon)",
                TRIAL_COUNT, trialsWithCommittedRoutes);
    }

    @Test
    @DisplayName("Across random graphs: LNS preserves every invariant it inherited")
    void improvementLoopPreservesInvariants() {
        for (long seed = 1; seed <= TRIAL_COUNT; seed++) {
            GraphSnapshot snapshot = randomSnapshot(seed);
            Harness harness = buildHarness(snapshot);
            LocalDateTime now = LocalDateTime.now();
            List<Party> demand = demandFor(snapshot, seed, now);
            String context = "seed=" + seed + " (post-LNS)";

            InstructionSet greedy = harness.dispatchService().plan(demand, now);
            int placedBefore = greedy.committed().stream().mapToInt(DispatchResult::size).sum();

            harness.improvementLoop().improve(5);

            List<DispatchResult> afterLns = harness.activePlan().planBook().all();
            InstructionSet improved = new InstructionSet(afterLns, greedy.shortfalls());

            // LNS re-routes committed platoons; it never drops or adds one.
            assertEquals(placedBefore, afterLns.stream().mapToInt(DispatchResult::size).sum(),
                    context + " LNS changed how many people are placed");

            assertNoArcOversubscribed(improved, snapshot, harness, context);
            assertNoShelterOversubscribed(improved, snapshot, context);
            assertNoLethalCellOccupied(improved, harness.timeline(), context);
        }
        log.info("LNS-preserved invariants held across {} random graphs", TRIAL_COUNT);
    }

    // --- Ledger invariant, independent of any graph ---

    @Test
    @DisplayName("Across random reservations: release is the exact inverse of reserve")
    void releaseIsAlwaysTheExactInverseOfReserve() {
        for (long seed = 1; seed <= TRIAL_COUNT; seed++) {
            GraphSnapshot snapshot = randomSnapshot(seed);
            GraphEngineProperties properties = new GraphEngineProperties();
            TimeModel timeModel = new TimeModel(properties);
            ReservationLedger ledger = new ReservationLedger(snapshot, timeModel, properties);
            java.util.Random random = new java.util.Random(seed);
            String context = "seed=" + seed;

            List<Long> reservedPlatoons = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                long platoonId = i + 1L;
                int slot = random.nextInt(ledger.edgeSlotCount());
                int tau = 1 + random.nextInt(4);
                int fromBucket = random.nextInt(ledger.horizonBuckets() - tau);
                int size = 1 + random.nextInt(3);
                if (ledger.tryReserveEdge(slot, fromBucket, tau, size, platoonId)) {
                    reservedPlatoons.add(platoonId);
                }
                int nodeIndex = random.nextInt(ledger.nodeCount());
                int bucket = random.nextInt(ledger.horizonBuckets());
                if (ledger.tryReserveNode(nodeIndex, bucket, size, platoonId)
                        && !reservedPlatoons.contains(platoonId)) {
                    reservedPlatoons.add(platoonId);
                }
            }

            for (long platoonId : reservedPlatoons) {
                ledger.release(platoonId);
            }

            for (int slot = 0; slot < ledger.edgeSlotCount(); slot++) {
                for (int bucket = 0; bucket < ledger.horizonBuckets(); bucket++) {
                    assertEquals(0, ledger.edgeOccupancyAt(slot, bucket),
                            context + " arc occupancy left behind at slot " + slot
                                    + " bucket " + bucket);
                }
            }
            for (int node = 0; node < ledger.nodeCount(); node++) {
                for (int bucket = 0; bucket < ledger.horizonBuckets(); bucket++) {
                    assertEquals(0, ledger.nodeOccupancyAt(node, bucket),
                            context + " junction occupancy left behind at node " + node
                                    + " bucket " + bucket);
                }
            }
        }
    }

    // --- Shared invariant checks ---

    private void assertNoArcOversubscribed(InstructionSet instructions, GraphSnapshot snapshot,
                                           Harness harness, String context) {
        double bucketsPerHour = 3600.0 / harness.timeModel().deltaSeconds();
        double headroom = harness.properties().getDispatch().getCapacityHeadroom();

        Map<Long, Integer> occupancy = new HashMap<>();
        for (DispatchResult result : instructions.committed()) {
            for (TimedWalk.Step step : result.searchResult().walk().steps()) {
                if (step.isWait()) {
                    continue;
                }
                for (int bucket = step.from().bucket(); bucket < step.to().bucket(); bucket++) {
                    occupancy.merge(((long) step.edgeSlot() << 32) | bucket, result.size(),
                            Integer::sum);
                }
            }
        }

        for (Map.Entry<Long, Integer> cell : occupancy.entrySet()) {
            int slot = (int) (cell.getKey() >>> 32);
            double capacity = snapshot.edgeCapacityPersonsPerHour(slot) / bucketsPerHour * headroom;
            assertTrue(cell.getValue() <= capacity + EPSILON,
                    context + " slot " + slot + " carries " + cell.getValue()
                            + " in one bucket, over its " + capacity + " capacity");
        }
    }

    private void assertNoShelterOversubscribed(InstructionSet instructions, GraphSnapshot snapshot,
                                               String context) {
        Map<Long, Integer> assigned = new HashMap<>();
        for (DispatchResult result : instructions.committed()) {
            assigned.merge(result.searchResult().shelter().shelterId(), result.size(), Integer::sum);
        }
        for (GraphSnapshot.ShelterRef shelter : snapshot.shelters()) {
            int taken = assigned.getOrDefault(shelter.shelterId(), 0);
            assertTrue(taken <= shelter.availableCapacity(),
                    context + " shelter " + shelter.shelterId() + " took " + taken
                            + " into " + shelter.availableCapacity() + " places");
        }
    }

    private void assertNoLethalCellOccupied(InstructionSet instructions, HazardTimeline timeline,
                                            String context) {
        for (DispatchResult result : instructions.committed()) {
            for (TimedWalk.Step step : result.searchResult().walk().steps()) {
                if (step.isWait()) {
                    assertFalse(timeline.isNodeLethal(step.to().nodeIndex(), step.to().bucket()),
                            context + " a platoon waits in a LETHAL junction cell");
                    continue;
                }
                for (int bucket = step.from().bucket(); bucket < step.to().bucket(); bucket++) {
                    assertFalse(timeline.isEdgeLethal(step.edgeSlot(), bucket),
                            context + " a platoon occupies LETHAL arc cell (slot "
                                    + step.edgeSlot() + ", bucket " + bucket + ")");
                }
            }
        }
    }
}
