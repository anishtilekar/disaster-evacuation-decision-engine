package com.evacuation.engine.dispatch;

import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.TimeModel;
import com.evacuation.engine.model.enums.NodeType;
import com.evacuation.engine.model.enums.RoadStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the reservation ledger's core promises: capacity is actually enforced, release is the
 * exact inverse of reserve, arcs and junctions are tracked independently, and a rollback restores
 * precisely what a journal recorded.
 *
 * <p>The fixture is deliberately arithmetic-friendly. At the default 15-second bucket there are 240
 * buckets in an hour, so a 2400 persons/hour arc allows 10 persons/bucket before headroom and
 * {@code 10 * 0.85 = 8.5} after it. That fractional bound is the interesting case: 8 people fit, 9
 * do not, and nothing is silently rounded away.
 */
class ReservationLedgerTest {

    private static final long GRAPH_VERSION = 42L;

    private static final int NODE_A = 0;
    private static final int NODE_B = 1;

    private static final int SLOT_A_TO_B = 0;
    private static final int SLOT_B_TO_A = 1;

    private static final long PLATOON = 7L;
    private static final long OTHER_PLATOON = 8L;

    /** 2400 persons/hour / 240 buckets-per-hour * 0.85 headroom. */
    private static final double EXPECTED_CAPACITY_PER_BUCKET = 8.5;

    private static final double EPSILON = 1e-9;

    /** Two nodes, one bidirectional edge as two directed slots — the smallest useful graph. */
    private GraphSnapshot buildSnapshot() {
        long[] dbNodeId = {10L, 20L};
        String[] nodeName = {"A", "B"};
        double[] nodeLat = {18.5100, 18.5200};
        double[] nodeLon = {73.8500, 73.8600};
        NodeType[] nodeType = {NodeType.INTERSECTION, NodeType.INTERSECTION};
        boolean[] nodeActive = {true, true};
        double[] nodeCapacityPersonsPerHour = {2400.0, 2400.0};
        Map<Long, Integer> nodeIdToIndex = Map.of(10L, 0, 20L, 1);

        int[] edgeHead = {0, 1, 2};
        int[] edgeTo = {NODE_B, NODE_A};
        long[] edgeDbId = {100L, 100L};
        double[] edgeDistanceKm = {1.0, 1.0};
        double[] edgeTimeMin = {2.0, 2.0};
        double[] edgeCapacityPersonsPerHour = {2400.0, 2400.0};
        RoadStatus[] edgeBaseStatus = {RoadStatus.OPEN, RoadStatus.OPEN};

        return new GraphSnapshot(
                dbNodeId, nodeName, nodeLat, nodeLon, nodeType, nodeActive, nodeCapacityPersonsPerHour,
                nodeIdToIndex, edgeHead, edgeTo, edgeDbId, edgeDistanceKm, edgeTimeMin,
                edgeCapacityPersonsPerHour, edgeBaseStatus, List.of(), GRAPH_VERSION,
                LocalDateTime.now());
    }

    private ReservationLedger newLedger() {
        GraphEngineProperties properties = new GraphEngineProperties();
        return new ReservationLedger(buildSnapshot(), new TimeModel(properties), properties);
    }

    /** True only if every arc and junction cell in the whole ledger is empty. */
    private boolean isCompletelyEmpty(ReservationLedger ledger) {
        for (int slot = 0; slot < ledger.edgeSlotCount(); slot++) {
            for (int bucket = 0; bucket < ledger.horizonBuckets(); bucket++) {
                if (ledger.edgeOccupancyAt(slot, bucket) != 0) {
                    return false;
                }
            }
        }
        for (int nodeIndex = 0; nodeIndex < ledger.nodeCount(); nodeIndex++) {
            for (int bucket = 0; bucket < ledger.horizonBuckets(); bucket++) {
                if (ledger.nodeOccupancyAt(nodeIndex, bucket) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    @DisplayName("Hourly capacities convert to the expected fractional per-bucket bound, headroom applied")
    void capacityConvertsToPerBucketBoundWithHeadroom() {
        ReservationLedger ledger = newLedger();

        assertEquals(EXPECTED_CAPACITY_PER_BUCKET,
                ledger.effectiveEdgeCapacityPerBucket(SLOT_A_TO_B), EPSILON);
        assertEquals(EXPECTED_CAPACITY_PER_BUCKET,
                ledger.effectiveNodeCapacityPerBucket(NODE_A), EPSILON);
    }

    @Test
    @DisplayName("A reservation within capacity occupies every bucket of its window")
    void reservingAnArcOccupiesTheWholeWindow() {
        ReservationLedger ledger = newLedger();

        assertTrue(ledger.tryReserveEdge(SLOT_A_TO_B, 5, 3, 4, PLATOON));

        // The window [5, 8) is occupied; the buckets on either side are untouched.
        assertEquals(0, ledger.edgeOccupancyAt(SLOT_A_TO_B, 4));
        assertEquals(4, ledger.edgeOccupancyAt(SLOT_A_TO_B, 5));
        assertEquals(4, ledger.edgeOccupancyAt(SLOT_A_TO_B, 6));
        assertEquals(4, ledger.edgeOccupancyAt(SLOT_A_TO_B, 7));
        assertEquals(0, ledger.edgeOccupancyAt(SLOT_A_TO_B, 8));
    }

    @Test
    @DisplayName("Capacity is enforced against the fractional bound: 8 fit, 9 do not")
    void capacityIsEnforcedAgainstTheFractionalBound() {
        ReservationLedger ledger = newLedger();

        // 8 <= 8.5, so this fits exactly.
        assertTrue(ledger.tryReserveEdge(SLOT_A_TO_B, 0, 1, 8, PLATOON));
        assertEquals(8, ledger.edgeOccupancyAt(SLOT_A_TO_B, 0));

        // One more would make 9 > 8.5.
        assertFalse(ledger.edgeFeasible(SLOT_A_TO_B, 0, 1, 1));
        assertFalse(ledger.tryReserveEdge(SLOT_A_TO_B, 0, 1, 1, OTHER_PLATOON));

        // And the refusal changed nothing.
        assertEquals(8, ledger.edgeOccupancyAt(SLOT_A_TO_B, 0));
    }

    @Test
    @DisplayName("A reservation that fails partway through its window leaves no partial occupancy")
    void failedReservationIsAllOrNothing() {
        ReservationLedger ledger = newLedger();

        // Fill only the last bucket of the window a second reservation will want.
        assertTrue(ledger.tryReserveEdge(SLOT_A_TO_B, 4, 1, 8, PLATOON));

        // [2, 5) overlaps that full bucket at 4, so it must be refused outright — not applied to
        // buckets 2 and 3 and then abandoned.
        assertFalse(ledger.tryReserveEdge(SLOT_A_TO_B, 2, 3, 5, OTHER_PLATOON));

        assertEquals(0, ledger.edgeOccupancyAt(SLOT_A_TO_B, 2));
        assertEquals(0, ledger.edgeOccupancyAt(SLOT_A_TO_B, 3));
        assertEquals(8, ledger.edgeOccupancyAt(SLOT_A_TO_B, 4));
    }

    @Test
    @DisplayName("Release is the exact inverse of reserve, across both arcs and junctions")
    void releaseIsTheExactInverseOfReserve() {
        ReservationLedger ledger = newLedger();

        assertTrue(ledger.tryReserveEdge(SLOT_A_TO_B, 3, 4, 5, PLATOON));
        assertTrue(ledger.tryReserveEdge(SLOT_B_TO_A, 10, 2, 3, PLATOON));
        assertTrue(ledger.tryReserveNode(NODE_A, 3, 5, PLATOON));
        assertTrue(ledger.tryReserveNode(NODE_B, 4, 2, PLATOON));

        assertFalse(isCompletelyEmpty(ledger));

        ledger.release(PLATOON);

        assertTrue(isCompletelyEmpty(ledger), "every cell should return to zero after release");
    }

    @Test
    @DisplayName("Releasing a platoon that holds nothing is a harmless no-op")
    void releasingNothingIsSafe() {
        ReservationLedger ledger = newLedger();

        ledger.release(PLATOON);
        ledger.release(PLATOON);

        assertTrue(isCompletelyEmpty(ledger));
    }

    @Test
    @DisplayName("Releasing one platoon leaves another platoon's holdings intact")
    void releaseOnlyAffectsItsOwnPlatoon() {
        ReservationLedger ledger = newLedger();

        assertTrue(ledger.tryReserveEdge(SLOT_A_TO_B, 0, 2, 3, PLATOON));
        assertTrue(ledger.tryReserveEdge(SLOT_A_TO_B, 0, 2, 4, OTHER_PLATOON));
        assertEquals(7, ledger.edgeOccupancyAt(SLOT_A_TO_B, 0));

        ledger.release(PLATOON);

        assertEquals(4, ledger.edgeOccupancyAt(SLOT_A_TO_B, 0));
    }

    @Test
    @DisplayName("Arc and junction occupancy are tracked in separate index spaces")
    void arcAndNodeOccupancyAreIndependent() {
        ReservationLedger ledger = newLedger();

        // Slot 0 and node 0 would collide if the two flat index spaces were shared.
        assertTrue(ledger.tryReserveEdge(SLOT_A_TO_B, 6, 1, 5, PLATOON));

        assertEquals(5, ledger.edgeOccupancyAt(SLOT_A_TO_B, 6));
        assertEquals(0, ledger.nodeOccupancyAt(NODE_A, 6), "reserving an arc must not occupy a junction");

        assertTrue(ledger.tryReserveNode(NODE_A, 6, 2, PLATOON));

        assertEquals(5, ledger.edgeOccupancyAt(SLOT_A_TO_B, 6), "reserving a junction must not occupy an arc");
        assertEquals(2, ledger.nodeOccupancyAt(NODE_A, 6));
    }

    @Test
    @DisplayName("Waiting several buckets is several single-cell reservations, each independently gated")
    void nodeReservationsAreSingleCell() {
        ReservationLedger ledger = newLedger();

        assertTrue(ledger.tryReserveNode(NODE_A, 2, 3, PLATOON));
        assertTrue(ledger.tryReserveNode(NODE_A, 3, 3, PLATOON));

        assertEquals(0, ledger.nodeOccupancyAt(NODE_A, 1));
        assertEquals(3, ledger.nodeOccupancyAt(NODE_A, 2));
        assertEquals(3, ledger.nodeOccupancyAt(NODE_A, 3));
        assertEquals(0, ledger.nodeOccupancyAt(NODE_A, 4));
    }

    @Test
    @DisplayName("Rollback restores exactly what the journal recorded, discarding the tentative move")
    void rollbackRestoresTheJournaledState() {
        ReservationLedger ledger = newLedger();

        // The committed plan.
        assertTrue(ledger.tryReserveEdge(SLOT_A_TO_B, 1, 2, 4, PLATOON));
        assertTrue(ledger.tryReserveNode(NODE_B, 3, 2, PLATOON));

        ReservationLedger.Journal journal = ledger.journal(PLATOON);
        assertEquals(1, journal.edgeHoldings().size());
        assertEquals(1, journal.nodeHoldings().size());

        // Try something else instead.
        ledger.release(PLATOON);
        assertTrue(ledger.tryReserveEdge(SLOT_B_TO_A, 20, 3, 6, PLATOON));
        assertEquals(6, ledger.edgeOccupancyAt(SLOT_B_TO_A, 20));

        // It was worse — put the original back.
        ledger.rollback(PLATOON, journal);

        assertEquals(4, ledger.edgeOccupancyAt(SLOT_A_TO_B, 1));
        assertEquals(4, ledger.edgeOccupancyAt(SLOT_A_TO_B, 2));
        assertEquals(2, ledger.nodeOccupancyAt(NODE_B, 3));
        assertEquals(0, ledger.edgeOccupancyAt(SLOT_B_TO_A, 20), "the abandoned move must leave nothing behind");

        // And the restored holdings are releasable, so the ledger is genuinely back in a clean state.
        ledger.release(PLATOON);
        assertTrue(isCompletelyEmpty(ledger));
    }

    @Test
    @DisplayName("A journal of a platoon holding nothing is empty rather than null")
    void journalOfNothingIsEmpty() {
        ReservationLedger ledger = newLedger();

        ReservationLedger.Journal journal = ledger.journal(PLATOON);

        assertTrue(journal.edgeHoldings().isEmpty());
        assertTrue(journal.nodeHoldings().isEmpty());
    }

    @Test
    @DisplayName("Windows and cells outside the compiled horizon are caller bugs, not silent failures")
    void outOfHorizonAccessThrows() {
        ReservationLedger ledger = newLedger();
        int horizon = ledger.horizonBuckets();

        assertThrows(IllegalArgumentException.class,
                () -> ledger.edgeFeasible(SLOT_A_TO_B, horizon - 1, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.tryReserveEdge(SLOT_A_TO_B, horizon, 1, 1, PLATOON));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.nodeFeasible(NODE_A, horizon, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.edgeFeasible(ledger.edgeSlotCount(), 0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.nodeFeasible(ledger.nodeCount(), 0, 1));
    }

    @Test
    @DisplayName("A capacity headroom outside (0, 1] is rejected at construction")
    void invalidHeadroomIsRejected() {
        GraphEngineProperties properties = new GraphEngineProperties();
        properties.getDispatch().setCapacityHeadroom(1.5);
        TimeModel timeModel = new TimeModel(properties);
        GraphSnapshot snapshot = buildSnapshot();

        assertThrows(IllegalArgumentException.class,
                () -> new ReservationLedger(snapshot, timeModel, properties));
    }
}
