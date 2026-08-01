package com.evacuation.engine.algorithm.spacetime;

import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.dispatch.ReservationLedger;
import com.evacuation.engine.graph.overlay.TraversalPolicy;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimeline;
import com.evacuation.engine.graph.time.HazardTimelineCompiler;
import com.evacuation.engine.graph.time.TimeModel;
import com.evacuation.engine.model.entity.BlockedRoad;
import com.evacuation.engine.model.entity.HazardEvent;
import com.evacuation.engine.model.entity.RoadEdge;
import com.evacuation.engine.model.enums.DisasterType;
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
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2 exit test: proves the time-expanded search is exactly optimal against the current world
 * model, and that a <em>predicted</em> hazard changes the route it returns.
 *
 * <p>The fixture is a diamond: two routes from one origin to one shelter node. The riverside route B
 * is unambiguously cheaper free-flow (4 buckets, 0.8 km) than the inland route A (12 buckets, 2.0 km),
 * so B is what the search must pick when nothing is wrong — and every later test that sees A chosen
 * has therefore proved the hazard actually drove the decision, rather than that some feasible route
 * happened to be found.
 *
 * <p>The same detour is then reached twice, by two different paths through the system: once from a
 * legacy {@code BlockedRoad} row (the compatibility shim proven in {@code HazardTimelineCompilerTest},
 * now driving a live search), and once from a real {@code HazardEvent} compiled by the real compiler —
 * the design's "outrun the flood" scenario, end to end.
 *
 * <p>Every edge is OPEN, so no cell is ever RISKY and the exposure term is exactly zero throughout.
 * Each expected cost below is therefore a plain sum of {@code secondsForBuckets(tau)} and is checkable
 * by hand.
 */
class TimeExpandedDijkstraTest {

    private static final Logger log = LoggerFactory.getLogger(TimeExpandedDijkstraTest.class);

    private static final long GRAPH_VERSION = 42L;
    private static final long SHELTER_ID = 1L;

    // Dense node indices.
    private static final int NODE_ORIGIN = 0;
    private static final int NODE_A_INLAND = 1;
    private static final int NODE_B_RIVERSIDE = 2;
    private static final int NODE_SHELTER = 3;

    // CSR slots, in the order the fixture lays them out.
    private static final int SLOT_ORIGIN_TO_A = 0;
    private static final int SLOT_ORIGIN_TO_B = 1;
    private static final int SLOT_A_TO_SHELTER = 2;
    private static final int SLOT_B_TO_SHELTER = 3;

    // Database edge ids per slot; the riverside first leg is the one Test 2 blocks.
    private static final long EDGE_DB_ID_ORIGIN_TO_A = 100L;
    private static final long EDGE_DB_ID_ORIGIN_TO_B = 200L;
    private static final long EDGE_DB_ID_A_TO_SHELTER = 101L;
    private static final long EDGE_DB_ID_B_TO_SHELTER = 201L;

    /** Route B free-flow: 2 + 2 buckets at 15 s. */
    private static final double ROUTE_B_COST_SECONDS = 60.0;
    private static final double ROUTE_B_DISTANCE_KM = 0.8;

    /** Route A free-flow: 6 + 6 buckets at 15 s. */
    private static final double ROUTE_A_COST_SECONDS = 180.0;
    private static final double ROUTE_A_DISTANCE_KM = 2.0;

    private static final double EPSILON = 1e-9;

    /** Any AVAILABLE shelter may terminate a search; selection policy stays the caller's business. */
    private static final Predicate<GraphSnapshot.ShelterRef> AVAILABLE_SHELTERS =
            shelter -> shelter.status() == ShelterStatus.AVAILABLE;

    /**
     * The diamond: origin (0) reaches the shelter node (3) either inland via A (1) or riverside via
     * B (2). Directed edges only — no bidirectional expansion is needed to make the point.
     *
     * <p>Coordinates are chosen so the flood scenario separates cleanly under the real haversine
     * distance, with every margin generous enough that no assertion turns on rounding:
     * <ul>
     *   <li>B's two edge midpoints sit ~264 m from the hazard origin, well inside its 350 m initial
     *       radius, so both are LETHAL from bucket 0.</li>
     *   <li>The origin and shelter nodes sit ~528 m out, so they stay usable at bucket 0 and through
     *       route A's arrival-plus-margin window (their onset lands near bucket 36, comfortably past
     *       the bucket 20 that window ends at).</li>
     *   <li>A's edge midpoints sit 1.6 km+ out, while the front reaches only ~1145 m by the last
     *       bucket of the horizon — so A is never lethal anywhere in the compiled plan.</li>
     * </ul>
     *
     * <p>Edge distances and travel times are set independently of the coordinates, as the cost
     * assertions require; only the hazard compiler reads the geometry.
     */
    private GraphSnapshot buildDiamondSnapshot() {
        long[] dbNodeId = {10L, 20L, 30L, 40L};
        String[] nodeName = {"Origin", "A (inland)", "B (riverside)", "Shelter Node"};
        double[] nodeLat = {18.5000, 18.5300, 18.5000, 18.5000};
        double[] nodeLon = {73.8500, 73.8500, 73.8550, 73.8600};
        NodeType[] nodeType = {
                NodeType.INTERSECTION, NodeType.INTERSECTION,
                NodeType.INTERSECTION, NodeType.INTERSECTION
        };
        boolean[] nodeActive = {true, true, true, true};
        // Capacity plays no part in these tests either; a uniform, generously large rate keeps it
        // a non-factor, same as the edge array below.
        double[] nodeCapacityPersonsPerHour = {1000.0, 1000.0, 1000.0, 1000.0};
        Map<Long, Integer> nodeIdToIndex = Map.of(10L, 0, 20L, 1, 30L, 2, 40L, 3);

        // CSR: node 0's slots = [0,2), node 1's = [2,3), node 2's = [3,4), node 3's = [4,4).
        int[] edgeHead = {0, 2, 3, 4, 4};
        int[] edgeTo = {NODE_A_INLAND, NODE_B_RIVERSIDE, NODE_SHELTER, NODE_SHELTER};
        long[] edgeDbId = {
                EDGE_DB_ID_ORIGIN_TO_A, EDGE_DB_ID_ORIGIN_TO_B,
                EDGE_DB_ID_A_TO_SHELTER, EDGE_DB_ID_B_TO_SHELTER
        };
        double[] edgeDistanceKm = {1.0, 0.4, 1.0, 0.4};
        // 1.5 min -> ceil(90/15) = 6 buckets; 0.5 min -> ceil(30/15) = 2 buckets.
        double[] edgeTimeMin = {1.5, 0.5, 1.5, 0.5};
        // Capacity plays no part in these tests (Phase 2 predates the reservation ledger); a
        // uniform, generously large rate keeps it a non-factor.
        double[] edgeCapacityPersonsPerHour = {1000.0, 1000.0, 1000.0, 1000.0};
        RoadStatus[] edgeBaseStatus = {
                RoadStatus.OPEN, RoadStatus.OPEN, RoadStatus.OPEN, RoadStatus.OPEN
        };

        GraphSnapshot.ShelterRef shelter = new GraphSnapshot.ShelterRef(
                SHELTER_ID, NODE_SHELTER, "Test Shelter", ShelterStatus.AVAILABLE,
                100, false, 18.5000, 73.8600);

        return new GraphSnapshot(
                dbNodeId, nodeName, nodeLat, nodeLon, nodeType, nodeActive, nodeCapacityPersonsPerHour,
                nodeIdToIndex, edgeHead, edgeTo, edgeDbId, edgeDistanceKm, edgeTimeMin,
                edgeCapacityPersonsPerHour, edgeBaseStatus, List.of(shelter), GRAPH_VERSION,
                LocalDateTime.now());
    }

    private TimeModel defaultTimeModel() {
        // Default GraphEngineProperties: delta = 15 s, horizon = 160 buckets, margin = 8 buckets.
        return new TimeModel(new GraphEngineProperties());
    }

    /** Compiles a timeline from the given overlay rows, mirroring HazardTimelineCompilerTest. */
    private HazardTimeline compile(GraphSnapshot snapshot,
                                   List<BlockedRoad> activeBlocks,
                                   List<HazardEvent> activeHazards) {
        BlockedRoadRepository blockedRoadRepository = mock(BlockedRoadRepository.class);
        when(blockedRoadRepository.findActiveWithEdge()).thenReturn(activeBlocks);

        HazardEventRepository hazardEventRepository = mock(HazardEventRepository.class);
        when(hazardEventRepository.findByActive(true)).thenReturn(activeHazards);

        return new HazardTimelineCompiler(blockedRoadRepository, hazardEventRepository, defaultTimeModel())
                .compile(snapshot);
    }

    /** The nothing-is-wrong baseline timeline. */
    private HazardTimeline compileHazardFree(GraphSnapshot snapshot) {
        return compile(snapshot, List.of(), List.of());
    }

    /**
     * The one search call every test runs: depart the origin at bucket 0, no medical preference,
     * a single-person platoon against a fresh (empty) ledger sized to this test's own snapshot —
     * capacity is a non-factor here, so an empty ledger is a true no-op rather than a stand-in.
     */
    private SearchResult searchFromOrigin(TraversalPolicy policy,
                                          Predicate<GraphSnapshot.ShelterRef> eligibility) {
        GraphEngineProperties properties = new GraphEngineProperties();
        TimeModel timeModel = defaultTimeModel();
        ReservationLedger ledger = new ReservationLedger(policy.snapshot(), timeModel, properties);
        return new TimeExpandedDijkstra(timeModel, properties)
                .searchSpaceTime(policy, NODE_ORIGIN, 0, Destination.anyShelter(), eligibility, false,
                        ledger, 1);
    }

    private boolean passesThrough(TimedWalk walk, int nodeIndex) {
        return walk.steps().stream().anyMatch(step -> step.to().nodeIndex() == nodeIndex);
    }

    /**
     * A {@link Destination.FixedNode} search from the origin. The eligibility predicate always
     * refuses, so a test failing here because a shelter sink fired anyway would mean the search is
     * not actually ignoring it for a fixed target — the guard is deliberate, not an oversight.
     */
    private SearchResult searchToNodeFromOrigin(TraversalPolicy policy, int targetNodeIndex) {
        GraphEngineProperties properties = new GraphEngineProperties();
        TimeModel timeModel = defaultTimeModel();
        ReservationLedger ledger = new ReservationLedger(policy.snapshot(), timeModel, properties);
        return new TimeExpandedDijkstra(timeModel, properties).searchSpaceTime(
                policy, NODE_ORIGIN, 0, new Destination.FixedNode(targetNodeIndex),
                shelter -> false, false, ledger, 1);
    }

    @Test
    @DisplayName("With no hazards at all, the search takes the cheaper riverside route B at its exact free-flow cost")
    void cheaperRouteChosenWhenHazardFree() {
        GraphSnapshot snapshot = buildDiamondSnapshot();
        TraversalPolicy policy = new TraversalPolicy(snapshot, compileHazardFree(snapshot));

        SearchResult result = searchFromOrigin(policy, AVAILABLE_SHELTERS);

        assertTrue(result.feasible());

        TimedWalk walk = result.walk();
        assertEquals(ROUTE_B_COST_SECONDS, walk.totalCost(), EPSILON);
        assertEquals(ROUTE_B_DISTANCE_KM, walk.totalDistanceKm(), EPSILON);

        // It reached the shelter node, and it did so via B rather than merely finding some route.
        assertEquals(new SpaceTimeState(NODE_SHELTER, 4), walk.arrival());
        assertTrue(passesThrough(walk, NODE_B_RIVERSIDE));
        assertFalse(passesThrough(walk, NODE_A_INLAND));
    }

    @Test
    @DisplayName("An active BlockedRoad on the riverside leg forces the inland detour at exactly route A's free-flow cost")
    void hardBlockForcesDetourToMoreExpensiveRoute() {
        GraphSnapshot snapshot = buildDiamondSnapshot();

        RoadEdge blockedEdge = RoadEdge.builder()
                .edgeId(EDGE_DB_ID_ORIGIN_TO_B)
                .build();
        BlockedRoad blockedRoad = BlockedRoad.builder()
                .roadEdge(blockedEdge)
                .active(true)
                .build();

        TraversalPolicy policy = new TraversalPolicy(
                snapshot, compile(snapshot, List.of(blockedRoad), List.of()));

        SearchResult result = searchFromOrigin(policy, AVAILABLE_SHELTERS);

        assertTrue(result.feasible());

        TimedWalk walk = result.walk();
        assertTrue(passesThrough(walk, NODE_A_INLAND));
        assertFalse(passesThrough(walk, NODE_B_RIVERSIDE));

        // The detour costs exactly what free-flow A costs — no partial credit through the blocked leg.
        assertEquals(ROUTE_A_COST_SECONDS, walk.totalCost(), EPSILON);
        assertEquals(ROUTE_A_DISTANCE_KM, walk.totalDistanceKm(), EPSILON);
        assertEquals(new SpaceTimeState(NODE_SHELTER, 12), walk.arrival());
    }

    @Test
    @DisplayName("With no shelter eligible, the search returns INFEASIBLE_WITHIN_HORIZON carrying the origin state")
    void infeasibleWhenNoShelterIsEligible() {
        GraphSnapshot snapshot = buildDiamondSnapshot();
        TraversalPolicy policy = new TraversalPolicy(snapshot, compileHazardFree(snapshot));

        SearchResult result = searchFromOrigin(policy, shelter -> false);

        assertFalse(result.feasible());

        TimedWalk walk = result.walk();
        assertNotNull(walk.steps());
        assertTrue(walk.steps().isEmpty());
        assertEquals(new SpaceTimeState(NODE_ORIGIN, 0), walk.origin());
        assertEquals(new SpaceTimeState(NODE_ORIGIN, 0), walk.arrival());
    }

    @Test
    @DisplayName("A predicted flood front compiled from a real HazardEvent makes the riverside route lethal, and the search outruns it inland")
    void outrunsAdvancingFloodByTakingInlandRoute() {
        GraphSnapshot snapshot = buildDiamondSnapshot();

        // A front opening at node B: 350 m already covered, advancing 20 m/min from now.
        // Defaults live in @PrePersist, which never runs for an unpersisted instance, so the
        // buffer and risk factor are set explicitly rather than left null.
        HazardEvent flood = HazardEvent.builder()
                .hazardType(DisasterType.FLOOD)
                .originLatitude(18.5000)
                .originLongitude(73.8550)
                .initialRadiusMeters(350.0)
                .growthRateMetersPerMinute(20.0)
                .leadingRiskBufferMeters(0.0)
                .riskFactor(0.0)
                .eventStartTime(LocalDateTime.now())
                .active(true)
                .build();

        HazardTimeline timeline = compile(snapshot, List.of(), List.of(flood));
        TraversalPolicy policy = new TraversalPolicy(snapshot, timeline);

        int lastBucket = timeline.horizonBuckets() - 1;

        // First: the compiled timeline itself, so this test proves the growth-rate math ran rather
        // than only that the routing outcome came out the expected way.
        assertTrue(timeline.isEdgeLethal(SLOT_ORIGIN_TO_B, 0));
        assertTrue(timeline.isEdgeLethal(SLOT_B_TO_SHELTER, 0));

        // And the effect is spatially confined — the inland route is untouched for the whole horizon.
        assertFalse(timeline.isEdgeLethal(SLOT_ORIGIN_TO_A, 0));
        assertFalse(timeline.isEdgeLethal(SLOT_ORIGIN_TO_A, lastBucket));
        assertFalse(timeline.isEdgeLethal(SLOT_A_TO_SHELTER, 0));
        assertFalse(timeline.isEdgeLethal(SLOT_A_TO_SHELTER, lastBucket));

        // Then: the search reaches the same detour as the hard block, through the full
        // HazardEvent -> compiler -> timeline pipeline instead of a manually flagged row.
        SearchResult result = searchFromOrigin(policy, AVAILABLE_SHELTERS);

        assertTrue(result.feasible());
        assertTrue(passesThrough(result.walk(), NODE_A_INLAND));
        assertFalse(passesThrough(result.walk(), NODE_B_RIVERSIDE));
        assertEquals(ROUTE_A_COST_SECONDS, result.walk().totalCost(), EPSILON);
    }

    @Test
    @DisplayName("A single search completes well inside a generous bound, with its latency logged")
    void searchCompletesQuicklyAndLatencyIsLogged() {
        GraphSnapshot snapshot = buildDiamondSnapshot();
        TraversalPolicy policy = new TraversalPolicy(snapshot, compileHazardFree(snapshot));

        long startNanos = System.nanoTime();
        SearchResult result = searchFromOrigin(policy, AVAILABLE_SHELTERS);
        long elapsedNanos = System.nanoTime() - startNanos;

        double elapsedMillis = elapsedNanos / 1_000_000.0;
        log.info("Time-expanded search latency: {} ms ({} nodes, horizon {} buckets)",
                elapsedMillis, snapshot.nodeCount(), defaultTimeModel().horizonBuckets());

        assertTrue(result.feasible());
        // A liveness bound, not a benchmark — deliberately loose so a slow CI runner cannot flake it.
        assertTrue(elapsedMillis < 2000, "Search took " + elapsedMillis + " ms");
    }

    // --- Destination.FixedNode: routing to a chosen node rather than any eligible shelter ---

    @Test
    @DisplayName("A FixedNode search reaches exactly the target node, stopping short of the shelter beyond it")
    void fixedNodeSearchReachesExactlyTheTargetNode() {
        GraphSnapshot snapshot = buildDiamondSnapshot();
        TraversalPolicy policy = new TraversalPolicy(snapshot, compileHazardFree(snapshot));

        SearchResult result = searchToNodeFromOrigin(policy, NODE_B_RIVERSIDE);

        assertTrue(result.feasible());
        assertNull(result.shelter(), "a FixedNode search has no shelter to report");
        TimedWalk walk = result.walk();
        assertEquals(new SpaceTimeState(NODE_B_RIVERSIDE, 2), walk.arrival());
        // Only the first leg of route B: the search stops at B itself rather than continuing to
        // the shelter node one hop further on.
        assertEquals(30.0, walk.totalCost(), EPSILON);
    }

    @Test
    @DisplayName("A FixedNode search whose target is the origin returns a trivial feasible walk at zero cost")
    void fixedNodeSearchAtOriginIsTrivial() {
        GraphSnapshot snapshot = buildDiamondSnapshot();
        TraversalPolicy policy = new TraversalPolicy(snapshot, compileHazardFree(snapshot));

        SearchResult result = searchToNodeFromOrigin(policy, NODE_ORIGIN);

        assertTrue(result.feasible());
        assertNull(result.shelter());
        TimedWalk walk = result.walk();
        assertTrue(walk.steps().isEmpty());
        assertEquals(new SpaceTimeState(NODE_ORIGIN, 0), walk.arrival());
        assertEquals(0.0, walk.totalCost(), EPSILON);
    }

    @Test
    @DisplayName("A FixedNode search to an unreachable node returns INFEASIBLE_WITHIN_HORIZON, not an exception")
    void fixedNodeSearchToUnreachableNodeIsInfeasible() {
        GraphSnapshot snapshot = buildDiamondSnapshot();
        TraversalPolicy policy = new TraversalPolicy(snapshot, compileHazardFree(snapshot));
        GraphEngineProperties properties = new GraphEngineProperties();
        TimeModel timeModel = defaultTimeModel();
        ReservationLedger ledger = new ReservationLedger(policy.snapshot(), timeModel, properties);

        // The shelter node is a dead end — no outgoing edges — so nothing is reachable from it.
        SearchResult result = new TimeExpandedDijkstra(timeModel, properties).searchSpaceTime(
                policy, NODE_SHELTER, 0, new Destination.FixedNode(NODE_ORIGIN),
                shelter -> false, false, ledger, 1);

        assertFalse(result.feasible());
        assertNull(result.shelter());
        assertEquals(new SpaceTimeState(NODE_SHELTER, 0), result.walk().origin());
        assertTrue(result.walk().steps().isEmpty());
    }

    @Test
    @DisplayName("A FixedNode search to the shelter node still outruns the advancing flood via the inland detour")
    void fixedNodeSearchStillRespectsHazardFeasibility() {
        GraphSnapshot snapshot = buildDiamondSnapshot();

        HazardEvent flood = HazardEvent.builder()
                .hazardType(DisasterType.FLOOD)
                .originLatitude(18.5000)
                .originLongitude(73.8550)
                .initialRadiusMeters(350.0)
                .growthRateMetersPerMinute(20.0)
                .leadingRiskBufferMeters(0.0)
                .riskFactor(0.0)
                .eventStartTime(LocalDateTime.now())
                .active(true)
                .build();

        HazardTimeline timeline = compile(snapshot, List.of(), List.of(flood));
        TraversalPolicy policy = new TraversalPolicy(snapshot, timeline);

        // Same "outrun the flood" scenario as the shelter-search test above, but the destination is
        // now the shelter's node itself, named directly rather than found via an eligibility predicate.
        SearchResult result = searchToNodeFromOrigin(policy, NODE_SHELTER);

        assertTrue(result.feasible());
        assertNull(result.shelter());
        assertTrue(passesThrough(result.walk(), NODE_A_INLAND));
        assertFalse(passesThrough(result.walk(), NODE_B_RIVERSIDE));
        assertEquals(ROUTE_A_COST_SECONDS, result.walk().totalCost(), EPSILON);
    }
}