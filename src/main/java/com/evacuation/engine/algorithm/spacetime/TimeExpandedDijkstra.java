package com.evacuation.engine.algorithm.spacetime;

import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.graph.overlay.TraversalPolicy;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimeline;
import com.evacuation.engine.graph.time.TimeModel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * The design's §3.2 core search: exactly-optimal single-platoon routing over a <em>virtual</em>
 * time-expanded graph, run directly against the CSR snapshot.
 *
 * <p>States are {@code (v, b)} — node {@code v} at bucket {@code b} — and the time-expanded graph is
 * never materialised. A settled state's outgoing arcs are generated on the fly: shelter sink arcs from
 * the shelter list, a waiting arc from the clock, and movement arcs straight from {@code v}'s CSR row.
 * Three properties make plain Dijkstra exactly optimal on this virtual structure, and together they
 * are the entire correctness argument. Every generated arc carries a fixed, non-negative scalar cost.
 * Time is baked into the state itself, so no time-dependent-cost subtleties arise — the cost of
 * traversing an arc departing at bucket {@code b} is a constant of the {@code (arc, b)} pair, fixed at
 * generation. And both real arc families strictly advance {@code b}, so the state graph is a DAG.
 * Dijkstra's optimality proof applies verbatim; the exposure term prices RISKY cells without ever
 * bending that proof, because it only ever adds cost.
 *
 * <p><strong>Medical preference is a penalty, not the doc's literal bonus.</strong> The pseudocode
 * writes {@code - medBonus} on entry to the virtual sink. Implemented literally, any cost-lowering
 * bonus at the sink breaks the settle-order invariant this search's termination rests on: sink entries
 * are harvested in the order states settle, so a nearer-but-unpreferred shelter's entry is committed
 * to before a slightly-farther preferred shelter has even been reached, and a discount applied on
 * arrival cannot retroactively reorder that. Note there is not even a non-zero base here for a factor
 * to shrink — a matching shelter's sink arc costs nothing — so a multiplicative discount could only
 * bite by driving the arrival cost below zero. The identical preference is therefore expressed as a
 * non-negative charge on the <em>mismatched</em> option: a medical-preferred party pays
 * {@code graph.dispatch.medical-mismatch-penalty-seconds} on a shelter without a medical facility and
 * nothing on one with. Every sink arc stays {@code >= 0}, the settle-order argument survives
 * untouched, and the relative ordering between the two shelters is exactly what the bonus intended.
 *
 * <p><strong>Phase 2 scope — capacity is deliberately ignored, not stubbed.</strong> The doc's full
 * cost model has four further ledger-dependent pieces: the BPR-shaped congestion term, the slack
 * penalty, the convoy-coherence epsilon, and the {@code nodeResidual} gate on the waiting arc. All
 * four need the ReservationLedger the capacity phase introduces, and none appears here in any form.
 * What is implemented: shelter sink arcs, wait arcs, movement arcs, the C3 hazard-feasibility check
 * including the beta margin, and exposure pricing of RISKY cells.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TimeExpandedDijkstra {

    /** Sentinel for "no predecessor state" / "arrived by waiting, no CSR slot" in the parent arrays. */
    private static final int UNSET = -1;

    /** One priority-queue entry: a flattened {@code (v, b)} state and the cost it was pushed with. */
    private record Entry(int flatIndex, double cost) {
    }

    private final TimeModel timeModel;
    private final GraphEngineProperties properties;

    /**
     * Searches for the cheapest hazard-feasible space-time path from an origin state to any eligible
     * shelter within the compiled horizon.
     *
     * <p>Shelter eligibility (open status, capacity, and so on) is entirely the caller's business via
     * the predicate — the same delegation convention as the spatial multi-target search. This class
     * knows reachability and cost, not selection policy; the one selection concern it does price,
     * medical fit, arrives as a boolean and a configured penalty rather than as logic.
     *
     * @param originNodeIndex  dense index of the departure node
     * @param departureBucket  the bucket the party departs at, relative to the timeline's epoch
     * @param eligibility      the caller's shelter filter; only shelters it accepts can terminate the search
     * @param medicalPreferred whether this party pays the mismatch penalty at non-medical shelters
     * @return the cheapest feasible walk and the shelter it reaches, or the infeasible result; never
     *         {@code null}
     * @throws IllegalArgumentException if {@code departureBucket} is negative
     */
    public SearchResult searchSpaceTime(TraversalPolicy policy, int originNodeIndex,
                                        int departureBucket,
                                        Predicate<GraphSnapshot.ShelterRef> eligibility,
                                        boolean medicalPreferred) {

        // Buckets are always relative to the timeline's epoch, so a negative departure is a caller
        // bug rather than a planning outcome — fail loudly here instead of obscurely downstream.
        if (departureBucket < 0) {
            throw new IllegalArgumentException("departureBucket must be >= 0, got " + departureBucket);
        }

        GraphSnapshot snapshot = policy.snapshot();
        HazardTimeline timeline = policy.timeline();
        int horizon = timeModel.horizonBuckets();

        // Guard clauses: a departure past the horizon, or from a node unusable at departure time,
        // cannot begin a feasible walk.
        if (departureBucket >= horizon
                || !policy.isNodeUsable(originNodeIndex, departureBucket)) {
            return SearchResult.infeasible(new SpaceTimeState(originNodeIndex, departureBucket));
        }

        // Eligible shelters, indexed by hosting node so a settled state checks its sinks in O(1).
        // A node may host several (a medical and an ordinary shelter can share one), and they are
        // kept apart deliberately: under medicalPreferred their sink costs differ.
        Map<Integer, List<GraphSnapshot.ShelterRef>> eligibleByNode = new HashMap<>();
        for (GraphSnapshot.ShelterRef shelter : snapshot.shelters()) {
            if (eligibility.test(shelter)) {
                eligibleByNode.computeIfAbsent(shelter.nodeIndex(), key -> new ArrayList<>()).add(shelter);
            }
        }
        if (eligibleByNode.isEmpty()) {
            log.debug("Space-time search from node {} bucket {}: no eligible shelters at all",
                    originNodeIndex, departureBucket);
            return SearchResult.infeasible(new SpaceTimeState(originNodeIndex, departureBucket));
        }

        // Per-call scratch over the N*T virtual state space, addressed by SpaceTimeState.flatIndex.
        // Allocated per call for now; the improvement-loop phase introduces per-thread pooling.
        int nodeCount = snapshot.nodeCount();
        int stateCount = nodeCount * horizon;
        double[] dist = new double[stateCount];
        int[] parentFlat = new int[stateCount];
        int[] parentSlot = new int[stateCount];
        boolean[] settled = new boolean[stateCount];

        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(parentFlat, UNSET);
        Arrays.fill(parentSlot, UNSET);

        // Operator levers and time constants, hoisted off the hot path.
        int beta = timeModel.marginBuckets();
        double deltaSeconds = timeModel.deltaSeconds();
        double waitArcCost = properties.getDispatch().getWaitCostWeight() * deltaSeconds;
        double exposureWeight = properties.getDispatch().getExposureWeight();
        double medicalMismatchPenalty = properties.getDispatch().getMedicalMismatchPenaltySeconds();

        int originFlat = SpaceTimeState.flatIndex(originNodeIndex, departureBucket, horizon);
        dist[originFlat] = 0.0;

        PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));
        queue.add(new Entry(originFlat, 0.0));

        // The virtual sink is tracked as a running best rather than modelled as a real state.
        double bestSinkCost = Double.POSITIVE_INFINITY;
        int bestSinkFlat = UNSET;
        GraphSnapshot.ShelterRef bestShelter = null;

        while (!queue.isEmpty()) {
            Entry entry = queue.poll();
            int flat = entry.flatIndex();
            double entryCost = entry.cost();

            // Termination: all arc costs are >= 0, so the heap surfaces entries in non-decreasing
            // cost order. Once the head costs >= bestSinkCost, every state still to settle has
            // dist >= bestSinkCost, and any sink arc it could offer costs dist + (penalty >= 0)
            // >= bestSinkCost — no remaining state can strictly improve on the best sink. Ties
            // cannot improve either (improvement requires strict <), so >= is the right comparison.
            // This holds even if this particular entry is stale: an unsettled state with a smaller
            // dist would have a smaller, still-queued entry the heap would have surfaced first.
            if (entryCost >= bestSinkCost) {
                break;
            }

            // Lazy deletion: already settled, or superseded by a cheaper push of the same state.
            if (settled[flat] || entryCost != dist[flat]) {
                continue;
            }
            settled[flat] = true;

            int v = flat / horizon;
            int b = flat % horizon;

            // 1. Shelter sink arcs — the multi-target virtual-super-sink, capacity-blind this phase.
            List<GraphSnapshot.ShelterRef> sheltersHere = eligibleByNode.get(v);
            if (sheltersHere != null) {
                for (GraphSnapshot.ShelterRef shelter : sheltersHere) {
                    double sinkCost = entryCost
                            + (medicalPreferred && !shelter.medicalFacility()
                                    ? medicalMismatchPenalty : 0.0);
                    if (sinkCost < bestSinkCost) {
                        bestSinkCost = sinkCost;
                        bestSinkFlat = flat;
                        bestShelter = shelter;
                    }
                }
            }

            // 2. Waiting arc — stay put one bucket while the node remains non-lethal. Waiting can be
            //    strictly optimal: it is how a party lets a hazard front pass an arc ahead of it.
            //    (The capacity phase additionally gates this on node residual capacity.)
            if (b + 1 < horizon && !timeline.isNodeLethal(v, b + 1)) {
                int toFlat = SpaceTimeState.flatIndex(v, b + 1, horizon);
                double candidate = entryCost + waitArcCost;
                if (candidate < dist[toFlat]) {
                    dist[toFlat] = candidate;
                    parentFlat[toFlat] = flat;
                    parentSlot[toFlat] = UNSET;
                    queue.add(new Entry(toFlat, candidate));
                }
            }

            // 3. Movement arcs, generated straight from v's CSR row.
            int slotsEnd = snapshot.slotsEnd(v);
            for (int slot = snapshot.slotsStart(v); slot < slotsEnd; slot++) {
                int tau = timeModel.edgeBuckets(snapshot.edgeTimeMin(slot));
                int u = snapshot.edgeTo(slot);

                if (b + tau + beta >= horizon) {
                    continue; // the doc's horizon guard: the margin window must fit inside T
                }
                if (!hazardFeasible(policy, slot, u, b, tau, beta)) {
                    continue; // C3, including the beta margin
                }

                // Base cost is the traversal's real duration; exposure prices each occupied bucket's
                // risk factor (0 on SAFE cells), so RISKY arcs cost more without being forbidden.
                double moveSeconds = timeModel.secondsForBuckets(tau);
                double exposure = 0.0;
                for (int bb = b; bb < b + tau; bb++) {
                    exposure += policy.riskAt(slot, bb) * deltaSeconds;
                }
                double candidate = entryCost + moveSeconds + exposureWeight * exposure;

                int toFlat = SpaceTimeState.flatIndex(u, b + tau, horizon);
                if (candidate < dist[toFlat]) {
                    dist[toFlat] = candidate;
                    parentFlat[toFlat] = flat;
                    parentSlot[toFlat] = slot;
                    queue.add(new Entry(toFlat, candidate));
                }
            }
        }

        // The doc's INFEASIBLE_WITHIN_HORIZON branch: no eligible shelter reachable inside T.
        if (bestSinkFlat == UNSET) {
            log.debug("Space-time search from node {} bucket {}: infeasible within horizon {}",
                    originNodeIndex, departureBucket, horizon);
            return SearchResult.infeasible(new SpaceTimeState(originNodeIndex, departureBucket));
        }

        // Reconstruct the winning walk: parents back from the sink's hosting state to the origin,
        // reversed into departure order. Kilometres accumulate over movement steps only — a wait
        // covers no distance — and stay a separate quantity from the cost.
        List<TimedWalk.Step> steps = new ArrayList<>();
        double totalDistanceKm = 0.0;

        for (int flat = bestSinkFlat; parentFlat[flat] != UNSET; flat = parentFlat[flat]) {
            int fromFlat = parentFlat[flat];
            int slot = parentSlot[flat];
            boolean isWait = slot == UNSET;

            steps.add(new TimedWalk.Step(
                    SpaceTimeState.fromFlatIndex(fromFlat, horizon),
                    SpaceTimeState.fromFlatIndex(flat, horizon),
                    isWait,
                    slot));
            if (!isWait) {
                totalDistanceKm += snapshot.edgeDistanceKm(slot);
            }
        }
        Collections.reverse(steps);

        SpaceTimeState origin = new SpaceTimeState(originNodeIndex, departureBucket);
        TimedWalk walk = new TimedWalk(origin, List.copyOf(steps), bestSinkCost, totalDistanceKm);

        log.debug("Space-time search from node {} bucket {}: shelter '{}' (id {}) at cost {} over {} steps",
                originNodeIndex, departureBucket, bestShelter.shelterName(), bestShelter.shelterId(),
                bestSinkCost, steps.size());

        return SearchResult.of(walk, bestShelter);
    }

    /**
     * The design's constraint C3, margin included: no platoon may occupy any {@code (x, b)} cell with
     * {@code h = LETHAL}, and an arc must remain non-lethal for {@code beta} buckets <em>after</em> the
     * platoon exits it.
     *
     * <p>The platoon occupies the arc across {@code [b, b + tau)}; the margin extends the arc's
     * requirement through {@code [b + tau, b + tau + beta)}, and the destination node must be
     * non-lethal on arrival and through that same margin window. The policy's own traversability rule
     * at the departure bucket is required as well, so any non-hazard gating the policy layer grows
     * later is respected here without edits. Callers guarantee {@code b + tau + beta < horizon}, so
     * every bucket read below is inside the compiled range.
     */
    private boolean hazardFeasible(TraversalPolicy policy, int slot, int destNodeIndex,
                                   int b, int tau, int beta) {
        if (!policy.isTraversable(slot, b)) {
            return false;
        }
        HazardTimeline timeline = policy.timeline();
        for (int bb = b; bb < b + tau + beta; bb++) {
            if (timeline.isEdgeLethal(slot, bb)) {
                return false;
            }
        }
        for (int bb = b + tau; bb < b + tau + beta; bb++) {
            if (timeline.isNodeLethal(destNodeIndex, bb)) {
                return false;
            }
        }
        return true;
    }
}
