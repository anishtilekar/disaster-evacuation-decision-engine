package com.evacuation.engine.dispatch;

import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.TimeModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The design's space-time reservation table: who has already promised to be where, and when.
 *
 * <p>This is the piece that turns a set of independent shortest paths into actual flow. Without it,
 * twelve parties heading for the same bridge each compute the same route and are each told the same
 * travel time — individually correct, collectively fiction, because the bridge cannot hold them all
 * at once. With it, each party's search consults what earlier parties have already reserved, so the
 * cells that are full read as infeasible and the search routes or waits around them.
 *
 * <p>Two constraints are enforced, and they are kept deliberately separate rather than folded into
 * one indexing scheme — mirroring how {@link GraphSnapshot} itself keeps edge- and node-indexed
 * arrays distinct:
 * <ul>
 *   <li><strong>Arc capacity (C1):</strong> for every {@code (slot, bucket)}, the summed sizes of
 *       platoons occupying it must not exceed that arc's capacity. A platoon entering an arc at
 *       bucket {@code b} with duration {@code tau} occupies the whole window
 *       {@code [b, b + tau)}.</li>
 *   <li><strong>Node residency:</strong> the gate on the search's waiting arc — a junction can only
 *       hold so many people at once. A wait step occupies exactly <em>one</em>
 *       {@code (nodeIndex, bucket)} cell, never a window: waiting several buckets means several
 *       separate reservations, one per bucket, which is precisely what a caller looping over a
 *       walk's consecutive wait steps produces naturally.</li>
 * </ul>
 *
 * <p><strong>Capacities are fractional; occupancy is not.</strong> Each raw persons-per-hour figure
 * is converted once, at construction, into an effective per-bucket bound and left as a
 * {@code double}, headroom already applied. An integer occupancy count is then compared directly
 * against that fractional bound rather than flooring the bound first — flooring would silently
 * discard up to a person per cell of real capacity, which compounds across a long window.
 *
 * <p><strong>Concurrency.</strong> Every public method is {@code synchronized} on a single intrinsic
 * lock covering both structures together, so an edge reservation and a node reservation for the same
 * move can never interleave with another thread's. This engine is explicitly single-JVM (the same
 * assumption {@code GraphCache} and {@code HazardTimelineCache} are built on) and dispatch is
 * sequential by design — one party at a time, in priority order — so an exact, auditable ledger is
 * worth far more here than lock-free throughput.
 *
 * <p><strong>Lifecycle.</strong> Deliberately not a Spring bean. Spring builds its singletons before
 * the startup runner has imported the graph, so a bean sizing itself against a not-yet-built
 * snapshot would fail at context startup. Like {@link GraphSnapshot}, this is a plain object
 * constructed explicitly against a specific snapshot; the dispatch service owns the question of when
 * to build one and how long to keep it.
 *
 * <p><strong>Repair support.</strong> Two methods exist for the repair phase and are only ever
 * called by it. {@link #platoonsIntersecting(Set, Set, int)} answers "whom does this event
 * invalidate" by finding the platoons holding still-future reservations on the affected cells, and
 * {@link #release(long, int)} then gives back <em>only</em> the future portion of one such platoon's
 * holdings. Together they express the design's {@code partiesIntersecting(cells, from = now)} →
 * {@code release(i, futurePortionOnly)} pair: an event that happens now can change where a platoon
 * is going, but it cannot rewrite where the platoon has already been, so the elapsed part of every
 * plan stays exactly as recorded.
 */
public final class ReservationLedger {

    /** Seconds in an hour, for converting a persons-per-hour capacity into a per-bucket one. */
    private static final double SECONDS_PER_HOUR = 3600.0;

    /** One occupied arc window: {@code size} people on {@code slot} across {@code [fromBucket, fromBucket + tau)}. */
    public record EdgeHolding(int slot, int fromBucket, int tau, int size) {

        public EdgeHolding {
            if (fromBucket < 0) {
                throw new IllegalArgumentException("fromBucket must be >= 0, got " + fromBucket);
            }
            if (tau <= 0) {
                throw new IllegalArgumentException("tau must be > 0, got " + tau);
            }
            if (size <= 0) {
                throw new IllegalArgumentException("size must be > 0, got " + size);
            }
        }
    }

    /** One occupied junction cell: {@code size} people waiting at {@code nodeIndex} during {@code bucket}. */
    public record NodeHolding(int nodeIndex, int bucket, int size) {

        public NodeHolding {
            if (bucket < 0) {
                throw new IllegalArgumentException("bucket must be >= 0, got " + bucket);
            }
            if (size <= 0) {
                throw new IllegalArgumentException("size must be > 0, got " + size);
            }
        }
    }

    /**
     * Everything one platoon holds at a moment in time — the token handed out by
     * {@link #journal(long)} and handed back to {@link #rollback(long, Journal)} when a tentative
     * move is abandoned.
     */
    public record Journal(List<EdgeHolding> edgeHoldings, List<NodeHolding> nodeHoldings) {
    }

    private final int horizonBuckets;
    private final int edgeSlotCount;
    private final int nodeCount;

    /** Flat {@code slot * horizonBuckets + bucket} occupancy, in persons. */
    private final int[] edgeOccupancy;
    /** Flat {@code nodeIndex * horizonBuckets + bucket} occupancy, in persons. */
    private final int[] nodeOccupancy;

    /** Per-slot persons-per-bucket bound, headroom already applied. */
    private final double[] effectiveEdgeCapacityPerBucket;
    /** Per-node persons-per-bucket bound, headroom already applied. */
    private final double[] effectiveNodeCapacityPerBucket;

    private final Map<Long, List<EdgeHolding>> edgeHoldingsByPlatoon = new HashMap<>();
    private final Map<Long, List<NodeHolding>> nodeHoldingsByPlatoon = new HashMap<>();

    /**
     * Sizes a fresh, empty ledger against one snapshot and converts every capacity to a per-bucket
     * bound once, up front, so the hot path never repeats the arithmetic.
     *
     * @throws IllegalArgumentException if the configured capacity headroom is not in {@code (0, 1]}
     */
    public ReservationLedger(GraphSnapshot snapshot, TimeModel timeModel,
                             GraphEngineProperties properties) {
        double headroom = properties.getDispatch().getCapacityHeadroom();
        if (headroom <= 0.0 || headroom > 1.0) {
            throw new IllegalArgumentException(
                    "graph.dispatch.capacity-headroom must be in (0, 1], got " + headroom);
        }

        this.horizonBuckets = timeModel.horizonBuckets();
        this.edgeSlotCount = snapshot.edgeSlotCount();
        this.nodeCount = snapshot.nodeCount();

        this.edgeOccupancy = new int[edgeSlotCount * horizonBuckets];
        this.nodeOccupancy = new int[nodeCount * horizonBuckets];

        // A capacity is quoted per hour; the ledger reasons per bucket. Convert once, apply the
        // headroom here, and every later check is a plain comparison.
        double bucketsPerHour = SECONDS_PER_HOUR / timeModel.deltaSeconds();

        this.effectiveEdgeCapacityPerBucket = new double[edgeSlotCount];
        for (int slot = 0; slot < edgeSlotCount; slot++) {
            effectiveEdgeCapacityPerBucket[slot] =
                    snapshot.edgeCapacityPersonsPerHour(slot) / bucketsPerHour * headroom;
        }

        this.effectiveNodeCapacityPerBucket = new double[nodeCount];
        for (int nodeIndex = 0; nodeIndex < nodeCount; nodeIndex++) {
            effectiveNodeCapacityPerBucket[nodeIndex] =
                    snapshot.nodeCapacityPersonsPerHour(nodeIndex) / bucketsPerHour * headroom;
        }
    }

    // --- Arc capacity (C1) ---

    /**
     * Whether {@code size} people could traverse {@code slot} across {@code [fromBucket, fromBucket +
     * tau)} without exceeding its capacity in any bucket.
     *
     * <p>Read-only — this is what a search calls while exploring candidates, long before anything is
     * committed.
     *
     * @throws IllegalArgumentException if the window falls outside the compiled horizon
     */
    public synchronized boolean edgeFeasible(int slot, int fromBucket, int tau, int size) {
        checkEdgeWindow(slot, fromBucket, tau);
        return edgeWindowHasRoom(slot, fromBucket, tau, size);
    }

    /**
     * Atomically checks and reserves an arc window, returning {@code false} and mutating nothing if
     * any bucket in the window lacks room.
     *
     * <p>Deliberately a single call rather than {@code edgeFeasible(...)} followed by a separate
     * reserve: those are two lock acquisitions, and another platoon can take the last of the
     * capacity in between. Per-method synchronization does not close a check-then-act gap that spans
     * two methods.
     *
     * @throws IllegalArgumentException if the window falls outside the compiled horizon
     */
    public synchronized boolean tryReserveEdge(int slot, int fromBucket, int tau, int size,
                                               long platoonId) {
        checkEdgeWindow(slot, fromBucket, tau);
        if (!edgeWindowHasRoom(slot, fromBucket, tau, size)) {
            return false;
        }

        for (int bucket = fromBucket; bucket < fromBucket + tau; bucket++) {
            edgeOccupancy[edgeCellIndex(slot, bucket)] += size;
        }
        edgeHoldingsByPlatoon
                .computeIfAbsent(platoonId, id -> new ArrayList<>())
                .add(new EdgeHolding(slot, fromBucket, tau, size));
        return true;
    }

    // --- Node residency (the waiting-arc gate) ---

    /**
     * Whether {@code size} people could occupy {@code nodeIndex} during {@code bucket} without
     * exceeding the junction's holding capacity. Read-only.
     *
     * @throws IllegalArgumentException if the bucket falls outside the compiled horizon
     */
    public synchronized boolean nodeFeasible(int nodeIndex, int bucket, int size) {
        checkNodeCell(nodeIndex, bucket);
        return nodeCellHasRoom(nodeIndex, bucket, size);
    }

    /**
     * Atomically checks and reserves one junction cell, returning {@code false} and mutating nothing
     * if it lacks room. Same single-call discipline as {@link #tryReserveEdge}, over a single cell
     * rather than a window.
     *
     * @throws IllegalArgumentException if the bucket falls outside the compiled horizon
     */
    public synchronized boolean tryReserveNode(int nodeIndex, int bucket, int size, long platoonId) {
        checkNodeCell(nodeIndex, bucket);
        if (!nodeCellHasRoom(nodeIndex, bucket, size)) {
            return false;
        }

        nodeOccupancy[nodeCellIndex(nodeIndex, bucket)] += size;
        nodeHoldingsByPlatoon
                .computeIfAbsent(platoonId, id -> new ArrayList<>())
                .add(new NodeHolding(nodeIndex, bucket, size));
        return true;
    }

    // --- Per-platoon lifecycle ---

    /**
     * Gives back everything a platoon holds, on both arcs and junctions.
     *
     * <p>Walks that platoon's own holdings rather than scanning the arrays, so the cost is
     * proportional to its route length, not to the size of the graph. A platoon that holds nothing
     * is a no-op, not an error — releasing twice, or releasing something never reserved, is safe.
     */
    public synchronized void release(long platoonId) {
        List<EdgeHolding> edgeHoldings = edgeHoldingsByPlatoon.remove(platoonId);
        if (edgeHoldings != null) {
            for (EdgeHolding holding : edgeHoldings) {
                for (int bucket = holding.fromBucket();
                     bucket < holding.fromBucket() + holding.tau();
                     bucket++) {
                    edgeOccupancy[edgeCellIndex(holding.slot(), bucket)] -= holding.size();
                }
            }
        }

        List<NodeHolding> nodeHoldings = nodeHoldingsByPlatoon.remove(platoonId);
        if (nodeHoldings != null) {
            for (NodeHolding holding : nodeHoldings) {
                nodeOccupancy[nodeCellIndex(holding.nodeIndex(), holding.bucket())] -= holding.size();
            }
        }
    }

    /**
     * The design's {@code ledger.release(i, futurePortionOnly)} — gives back only what has not
     * happened yet, from {@code fromBucketInclusive} onward.
     *
     * <p>Where {@link #release(long)} erases a platoon's whole reservation as though it had never
     * been planned, this keeps its already-elapsed history intact — both as occupancy and as
     * holdings — and undoes only the part that lies at or beyond the bucket a repair is anchored to.
     * That distinction is the point: a platoon that has spent the last four minutes crossing a
     * bridge really did occupy those cells, and a flood reported now cannot change that. Erasing the
     * past instead would free capacity that was genuinely consumed, letting some other platoon be
     * routed through a bucket that has already gone by, and would destroy the audit trail of where
     * this platoon actually went before it was rerouted.
     *
     * <p>An arc holding that straddles the cutoff is truncated rather than dropped: its occupancy is
     * given back only for the buckets from the cutoff onward, and the holding is replaced by one
     * covering just the retained past portion, so the two views stay exactly consistent. Junction
     * holdings occupy a single cell each, so they are simply kept or dropped whole. A platoon that
     * holds nothing is a safe no-op, matching {@link #release(long)}'s tolerance.
     *
     * @param platoonId           the platoon whose future reservations to give back
     * @param fromBucketInclusive the first bucket considered "not yet happened"
     * @throws IllegalArgumentException if {@code fromBucketInclusive} is negative
     */
    public synchronized void release(long platoonId, int fromBucketInclusive) {
        if (fromBucketInclusive < 0) {
            throw new IllegalArgumentException(
                    "fromBucketInclusive must be >= 0, got " + fromBucketInclusive);
        }

        List<EdgeHolding> edgeHoldings = edgeHoldingsByPlatoon.get(platoonId);
        if (edgeHoldings != null) {
            List<EdgeHolding> retained = new ArrayList<>(edgeHoldings.size());
            for (EdgeHolding holding : edgeHoldings) {
                int windowEnd = holding.fromBucket() + holding.tau();

                if (windowEnd <= fromBucketInclusive) {
                    // Entirely in the past: untouched, occupancy and holding alike.
                    retained.add(holding);
                } else if (holding.fromBucket() >= fromBucketInclusive) {
                    // Entirely in the future: give the whole window back and drop the holding.
                    for (int bucket = holding.fromBucket(); bucket < windowEnd; bucket++) {
                        edgeOccupancy[edgeCellIndex(holding.slot(), bucket)] -= holding.size();
                    }
                } else {
                    // Straddles the cutoff: give back only the future buckets, keep the past as a
                    // shorter holding so occupancy and holdings continue to describe the same thing.
                    for (int bucket = fromBucketInclusive; bucket < windowEnd; bucket++) {
                        edgeOccupancy[edgeCellIndex(holding.slot(), bucket)] -= holding.size();
                    }
                    retained.add(new EdgeHolding(
                            holding.slot(),
                            holding.fromBucket(),
                            fromBucketInclusive - holding.fromBucket(),
                            holding.size()));
                }
            }

            if (retained.isEmpty()) {
                edgeHoldingsByPlatoon.remove(platoonId);
            } else {
                edgeHoldingsByPlatoon.put(platoonId, retained);
            }
        }

        List<NodeHolding> nodeHoldings = nodeHoldingsByPlatoon.get(platoonId);
        if (nodeHoldings != null) {
            List<NodeHolding> retained = new ArrayList<>(nodeHoldings.size());
            for (NodeHolding holding : nodeHoldings) {
                if (holding.bucket() < fromBucketInclusive) {
                    retained.add(holding);
                } else {
                    nodeOccupancy[nodeCellIndex(holding.nodeIndex(), holding.bucket())] -= holding.size();
                }
            }

            if (retained.isEmpty()) {
                nodeHoldingsByPlatoon.remove(platoonId);
            } else {
                nodeHoldingsByPlatoon.put(platoonId, retained);
            }
        }
    }

    /**
     * An immutable record of what a platoon currently holds, for the improvement loop's
     * {@code journal → release → try something → rollback if worse} cycle. Mutates nothing; the
     * caller releases separately, keeping the two steps explicit rather than hiding a release inside
     * a read.
     *
     * @return the platoon's holdings, with empty lists (never {@code null}) if it holds nothing
     */
    public synchronized Journal journal(long platoonId) {
        List<EdgeHolding> edgeHoldings = edgeHoldingsByPlatoon.get(platoonId);
        List<NodeHolding> nodeHoldings = nodeHoldingsByPlatoon.get(platoonId);

        return new Journal(
                edgeHoldings == null ? List.of() : List.copyOf(edgeHoldings),
                nodeHoldings == null ? List.of() : List.copyOf(nodeHoldings));
    }

    /**
     * Puts a platoon back exactly as a journal recorded it, discarding whatever it holds now.
     *
     * <p>The journaled cells are re-applied without re-checking feasibility, which is sound rather
     * than sloppy: this same platoon held these exact cells moments ago, and releasing them only
     * freed capacity. Re-validating could not fail, and treating a rollback as something that might
     * be refused would leave the caller with no correct recovery.
     */
    public synchronized void rollback(long platoonId, Journal journaled) {
        release(platoonId);

        if (!journaled.edgeHoldings().isEmpty()) {
            List<EdgeHolding> restored = new ArrayList<>(journaled.edgeHoldings());
            for (EdgeHolding holding : restored) {
                for (int bucket = holding.fromBucket();
                     bucket < holding.fromBucket() + holding.tau();
                     bucket++) {
                    edgeOccupancy[edgeCellIndex(holding.slot(), bucket)] += holding.size();
                }
            }
            edgeHoldingsByPlatoon.put(platoonId, restored);
        }

        if (!journaled.nodeHoldings().isEmpty()) {
            List<NodeHolding> restored = new ArrayList<>(journaled.nodeHoldings());
            for (NodeHolding holding : restored) {
                nodeOccupancy[nodeCellIndex(holding.nodeIndex(), holding.bucket())] += holding.size();
            }
            nodeHoldingsByPlatoon.put(platoonId, restored);
        }
    }

    // --- Repair support (what an event invalidates) ---

    /**
     * The design's {@code ledger.partiesIntersecting(e.cells, from = now)}: everyone whose remaining
     * plan runs through the cells an event has just made unsafe or unusable.
     *
     * <p>Despite the design's name, this is genuinely <em>platoon</em>-grained, and deliberately so.
     * Reservations are held per platoon, not per party, and a party split into several waves may
     * well have only one of them actually crossing the affected cells — invalidating the whole party
     * because one wave is caught would tear up plans that remain perfectly valid. The repair service
     * is the piece that maps these platoon ids back to their owning parties, via the plan book, when
     * it needs to reason at party level.
     *
     * <p>The {@code fromBucket} floor is what keeps history safe: only a holding that has not
     * entirely passed counts as intersecting. An arc holding qualifies while its window has not yet
     * ended ({@code fromBucket < holding.fromBucket() + tau}), so a platoon still partway across an
     * affected arc is caught; a junction holding qualifies from its own bucket onward. A hazard
     * reported now can invalidate a platoon's future, never the part of its route it has already
     * completed.
     *
     * <p>Implemented as a linear scan of the current holdings rather than a reverse cell-to-platoon
     * index. At this project's ward scale the committed holdings are few enough that the scan is far
     * cheaper than keeping a second index correct through every reserve, release, partial release
     * and rollback — the same by-scale reasoning this class applies elsewhere, and the same thing
     * that would be worth revisiting for a city-wide graph.
     *
     * @param affectedEdgeSlots   CSR slots the event has compromised; may be empty
     * @param affectedNodeIndices dense node indices the event has compromised; may be empty
     * @param fromBucket          the first bucket that still counts as "not yet happened"
     * @return the ids of every platoon holding a still-future reservation on an affected cell; empty
     *         if there are none, never {@code null}
     * @throws IllegalArgumentException if {@code fromBucket} is negative
     */
    public synchronized Set<Long> platoonsIntersecting(Set<Integer> affectedEdgeSlots,
                                                       Set<Integer> affectedNodeIndices,
                                                       int fromBucket) {
        if (fromBucket < 0) {
            throw new IllegalArgumentException("fromBucket must be >= 0, got " + fromBucket);
        }
        if (affectedEdgeSlots.isEmpty() && affectedNodeIndices.isEmpty()) {
            return new HashSet<>();
        }

        Set<Long> affected = new HashSet<>();

        if (!affectedEdgeSlots.isEmpty()) {
            for (Map.Entry<Long, List<EdgeHolding>> entry : edgeHoldingsByPlatoon.entrySet()) {
                for (EdgeHolding holding : entry.getValue()) {
                    if (affectedEdgeSlots.contains(holding.slot())
                            && fromBucket < holding.fromBucket() + holding.tau()) {
                        affected.add(entry.getKey());
                        break;
                    }
                }
            }
        }

        if (!affectedNodeIndices.isEmpty()) {
            for (Map.Entry<Long, List<NodeHolding>> entry : nodeHoldingsByPlatoon.entrySet()) {
                for (NodeHolding holding : entry.getValue()) {
                    if (affectedNodeIndices.contains(holding.nodeIndex())
                            && holding.bucket() >= fromBucket) {
                        affected.add(entry.getKey());
                        break;
                    }
                }
            }
        }

        return affected;
    }

    // --- Read accessors (invariant checking, diagnostics) ---

    public synchronized int edgeOccupancyAt(int slot, int bucket) {
        checkEdgeWindow(slot, bucket, 1);
        return edgeOccupancy[edgeCellIndex(slot, bucket)];
    }

    public double effectiveEdgeCapacityPerBucket(int slot) {
        return effectiveEdgeCapacityPerBucket[slot];
    }

    public synchronized int nodeOccupancyAt(int nodeIndex, int bucket) {
        checkNodeCell(nodeIndex, bucket);
        return nodeOccupancy[nodeCellIndex(nodeIndex, bucket)];
    }

    public double effectiveNodeCapacityPerBucket(int nodeIndex) {
        return effectiveNodeCapacityPerBucket[nodeIndex];
    }

    public int horizonBuckets() {
        return horizonBuckets;
    }

    public int edgeSlotCount() {
        return edgeSlotCount;
    }

    public int nodeCount() {
        return nodeCount;
    }

    // --- Internals ---

    /**
     * Arc cells and junction cells live in two separate index spaces that happen to share this
     * arithmetic shape. They must never be crossed: {@code edgeCellIndex} takes a CSR slot,
     * {@code nodeCellIndex} takes a dense node index, and the arrays they address have different
     * lengths and different meanings.
     */
    private int edgeCellIndex(int slot, int bucket) {
        return slot * horizonBuckets + bucket;
    }

    private int nodeCellIndex(int nodeIndex, int bucket) {
        return nodeIndex * horizonBuckets + bucket;
    }

    private boolean edgeWindowHasRoom(int slot, int fromBucket, int tau, int size) {
        double capacity = effectiveEdgeCapacityPerBucket[slot];
        for (int bucket = fromBucket; bucket < fromBucket + tau; bucket++) {
            if (edgeOccupancy[edgeCellIndex(slot, bucket)] + size > capacity) {
                return false;
            }
        }
        return true;
    }

    private boolean nodeCellHasRoom(int nodeIndex, int bucket, int size) {
        return nodeOccupancy[nodeCellIndex(nodeIndex, bucket)] + size
                <= effectiveNodeCapacityPerBucket[nodeIndex];
    }

    private void checkEdgeWindow(int slot, int fromBucket, int tau) {
        if (slot < 0 || slot >= edgeSlotCount) {
            throw new IllegalArgumentException(
                    "slot must be in [0, " + edgeSlotCount + "), got " + slot);
        }
        if (fromBucket < 0) {
            throw new IllegalArgumentException("fromBucket must be >= 0, got " + fromBucket);
        }
        if (fromBucket + tau > horizonBuckets) {
            throw new IllegalArgumentException("window [" + fromBucket + ", " + (fromBucket + tau)
                    + ") exceeds horizon " + horizonBuckets);
        }
    }

    private void checkNodeCell(int nodeIndex, int bucket) {
        if (nodeIndex < 0 || nodeIndex >= nodeCount) {
            throw new IllegalArgumentException(
                    "nodeIndex must be in [0, " + nodeCount + "), got " + nodeIndex);
        }
        if (bucket < 0 || bucket >= horizonBuckets) {
            throw new IllegalArgumentException(
                    "bucket must be in [0, " + horizonBuckets + "), got " + bucket);
        }
    }
}