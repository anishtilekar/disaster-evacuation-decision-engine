package com.evacuation.engine.dispatch;

import com.evacuation.engine.algorithm.MultiTargetShelterSearch;
import com.evacuation.engine.algorithm.spacetime.SearchResult;
import com.evacuation.engine.algorithm.spacetime.SpaceTimeState;
import com.evacuation.engine.algorithm.spacetime.TimeExpandedDijkstra;
import com.evacuation.engine.algorithm.spacetime.TimedWalk;
import com.evacuation.engine.graph.overlay.TraversalPolicy;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimelineCache;
import com.evacuation.engine.graph.time.TimeModel;
import com.evacuation.engine.loader.GraphCache;
import com.evacuation.engine.model.enums.ShelterStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The design's §3.5 anytime LNS pass: repeatedly pick the platoon with the most regret, release it,
 * re-plan it against everyone else's intact reservations, and keep the new route only if the whole
 * plan scores lexicographically better — otherwise roll the ledger back and re-commit the original.
 *
 * <p>Monotone by construction (a rejected move is undone before the iteration returns) and
 * interruptible between iterations, so a caller can hand it any budget and stop it at any point
 * without leaving the session in a half-modified state.
 *
 * <p>Greedy dispatch commits each party against the board as it stood at that party's turn; a party
 * routed early may be holding a corridor that later arrivals needed more, and no single-party search
 * can see that. Re-planning one already-committed platoon against the finished board is the cheapest
 * move that can find it.
 *
 * <p>Scope: this re-optimizes a commitment's <em>route</em> from its original origin and departure
 * bucket. It never re-anchors to "now" — elapsed time and in-flight platoons are
 * {@code RepairService}'s concern.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ImprovementLoop {

    private final GraphCache graphCache;
    private final HazardTimelineCache hazardTimelineCache;
    private final ActivePlan activePlan;
    private final TimeExpandedDijkstra timeExpandedDijkstra;
    private final MultiTargetShelterSearch multiTargetShelterSearch;
    private final TimeModel timeModel;

    /**
     * @param iterationsRun how many iterations actually ran (fewer than the budget if interrupted)
     * @param accepted      how many of those improved the plan
     * @param finalScore    the plan's score after the pass
     */
    public record Result(int iterationsRun, int accepted, LexicographicObjective.Score finalScore) {
    }

    /**
     * Runs up to {@code maxIterations} improvement attempts against the current session.
     *
     * @param maxIterations the iteration budget
     * @return what the pass ran and what it achieved
     * @throws IllegalArgumentException if {@code maxIterations <= 0}
     */
    public Result improve(int maxIterations) {
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be > 0, got " + maxIterations);
        }

        int iterationsRun = 0;
        int accepted = 0;
        for (int i = 0; i < maxIterations; i++) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            iterationsRun++;
            if (improveOne()) {
                accepted++;
            }
        }

        int horizonBuckets = timeModel.horizonBuckets();
        LexicographicObjective.Score finalScore;
        synchronized (activePlan) {
            finalScore = LexicographicObjective.score(
                    activePlan.isActive() ? activePlan.planBook().all() : List.of(), horizonBuckets);
        }

        log.info("LNS pass: {} iterations run, {} accepted, final score {}",
                iterationsRun, accepted, finalScore);
        return new Result(iterationsRun, accepted, finalScore);
    }

    /**
     * One destroy-and-repair attempt, all-or-nothing.
     *
     * <p>The lock (the usual {@code activePlan} monitor) is taken per iteration rather than around
     * the whole budget: one iteration is the atomic unit, so a real dispatch or repair call can
     * interleave between iterations instead of waiting out an arbitrarily long improvement run.
     * Snapshot, policy, and horizon are resolved fresh inside this same lock — not once up front in
     * {@link #improve(int)} — precisely because that interleaving means the session, the graph, or
     * the compiled timeline could all be different by the time a later iteration runs; using a stale
     * reference would risk operating against a ledger sized for a different snapshot entirely. This
     * is also what makes a no-op the correct, exception-free outcome when no session is active yet:
     * the check happens before this method touches the graph or hazard caches at all.
     *
     * @return {@code true} if the plan was improved and the new route kept
     */
    private boolean improveOne() {
        synchronized (activePlan) {
            if (!activePlan.isActive()) {
                return false;
            }
            PlanBook planBook = activePlan.planBook();
            List<DispatchResult> committed = planBook.all();
            if (committed.isEmpty()) {
                return false;
            }

            GraphSnapshot snapshot = graphCache.get();
            TraversalPolicy policy = new TraversalPolicy(snapshot, hazardTimelineCache.get());
            int horizonBuckets = timeModel.horizonBuckets();
            ReservationLedger ledger = activePlan.ledger();
            DispatchResult target = pickByRegret(committed, snapshot, policy);
            LexicographicObjective.Score before =
                    LexicographicObjective.score(committed, horizonBuckets);

            ReservationLedger.Journal journal = ledger.journal(target.platoonId());
            ledger.release(target.platoonId());
            planBook.remove(target.platoonId());

            Map<Long, Integer> shelterRemaining = computeShelterRemaining(snapshot, planBook);
            Predicate<GraphSnapshot.ShelterRef> eligibility = shelter ->
                    shelter.status() == ShelterStatus.AVAILABLE
                            && shelterRemaining.getOrDefault(shelter.shelterId(), 0) >= target.size();

            SpaceTimeState origin = target.searchResult().walk().origin();
            SearchResult replanned = timeExpandedDijkstra.searchSpaceTime(
                    policy, origin.nodeIndex(), origin.bucket(), eligibility,
                    target.medicalPreferred(), ledger, target.size());

            if (replanned.feasible()
                    && DispatchService.reserveWalk(replanned.walk(), target.partyId(),
                            target.platoonId(), target.size(), ledger)) {

                DispatchResult candidate = new DispatchResult(
                        target.partyId(), target.platoonId(), target.waveIndex(), target.size(),
                        target.priority(), target.medicalPreferred(), replanned);

                // The target is already out of the book, so this is everyone else plus the candidate.
                List<DispatchResult> after = new ArrayList<>(planBook.all());
                after.add(candidate);

                if (LexicographicObjective.isBetter(
                        LexicographicObjective.score(after, horizonBuckets), before)) {
                    planBook.commit(candidate);
                    log.debug("LNS kept a new route for platoon {} (party {})",
                            target.platoonId(), target.partyId());
                    return true;
                }
            }

            // Rollback clears whatever the tentative attempt reserved before restoring the journal.
            ledger.rollback(target.platoonId(), journal);
            planBook.commit(target);
            return false;
        }
    }

    /**
     * The design's {@code pickByRegret}: the platoon whose committed route costs the most above its
     * own free-flow bound — the one with the most to gain from being re-planned.
     *
     * <p>An unreachable probe yields no usable bound, so the walk is scored against itself: regret is
     * exactly 0 rather than a formula that could go negative or NaN and win the maximum spuriously.
     */
    private DispatchResult pickByRegret(List<DispatchResult> committed, GraphSnapshot snapshot,
                                        TraversalPolicy policy) {
        DispatchResult worst = null;
        double worstRegret = Double.NEGATIVE_INFINITY;

        for (DispatchResult result : committed) {
            TimedWalk walk = result.searchResult().walk();
            MultiTargetShelterSearch.ShelterPathResult probe =
                    multiTargetShelterSearch.findNearestEligibleShelter(
                            snapshot, policy, walk.origin().nodeIndex(),
                            shelter -> shelter.status() == ShelterStatus.AVAILABLE);

            double bound = probe.reachable() ? probe.totalCost() : walk.totalCost();
            double regret = walk.totalCost() - bound;
            if (regret > worstRegret) {
                worstRegret = regret;
                worst = result;
            }
        }
        return worst;
    }

    /** Same derive-don't-track shelter accounting as {@code DispatchService}/{@code RepairService}. */
    private Map<Long, Integer> computeShelterRemaining(GraphSnapshot snapshot, PlanBook planBook) {
        Map<Long, Integer> remaining = new HashMap<>();
        for (GraphSnapshot.ShelterRef shelter : snapshot.shelters()) {
            remaining.put(shelter.shelterId(), shelter.availableCapacity());
        }
        for (DispatchResult result : planBook.all()) {
            remaining.merge(result.searchResult().shelter().shelterId(), -result.size(), Integer::sum);
        }
        return remaining;
    }
}