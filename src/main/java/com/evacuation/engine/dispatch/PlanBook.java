package com.evacuation.engine.dispatch;

import com.evacuation.engine.algorithm.spacetime.SpaceTimeState;
import com.evacuation.engine.algorithm.spacetime.TimedWalk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The design's §3.1 {@code PlanBook}: the record of every platoon currently committed to a route,
 * plus the {@code projectedPosition(now)} anchor state that mid-evacuation replanning is built on.
 *
 * <p><strong>Why the anchor state matters.</strong> When a hazard event invalidates part of a
 * platoon's reserved walk, repair cannot simply re-run the original search — by then the platoon has
 * been moving for some minutes and is no longer anywhere near where it started. Replanning from its
 * origin would produce instructions that are honest about nothing: a route from a place the platoon
 * has already left, at a time that has already passed. {@link #projectedPosition(long, int)} answers
 * the question repair actually needs — <em>where along its reserved walk should this platoon be at
 * the current bucket</em> — and that state is what the replan departs from.
 *
 * <p><strong>Lifecycle.</strong> A plain class, deliberately not a Spring bean, exactly like
 * {@code GraphSnapshot} and {@link ReservationLedger}. {@code ActivePlan} is the Spring-managed piece
 * that owns one of these for the life of an operational session and clears it on reset.
 *
 * <p><strong>Storage.</strong> One map keyed by {@code platoonId}, and no secondary party index: a
 * party's platoons are found by a linear filter over the map's values. That is a deliberate choice
 * rather than an oversight. At this project's ward scale the committed set is small enough that a
 * scan costs less than maintaining a second structure in sync through every commit, overwrite, and
 * removal — the same tradeoff the rest of this engine makes wherever a scan and an index would both
 * work. A city-wide deployment is where that judgement would be worth revisiting.
 */
public final class PlanBook {

    /** Every committed platoon's result, keyed by {@link DispatchResult#platoonId()}. */
    private final Map<Long, DispatchResult> byPlatoonId = new HashMap<>();

    /**
     * Records a platoon's committed route, replacing any route that platoon already had.
     *
     * <p>The overwrite is the point, not a side effect: a repair that reroutes a platoon commits its
     * new result over the old one, and the plan book then reflects only the route now in force.
     *
     * @param result the committed outcome to record
     * @throws IllegalArgumentException if the result is infeasible — an infeasible search is a
     *                                  shortfall to report, not a route to commit, and the caller
     *                                  must gate on feasibility before committing rather than rely
     *                                  on this method to catch it
     */
    public void commit(DispatchResult result) {
        if (!result.feasible()) {
            throw new IllegalArgumentException(
                    "only feasible results belong in the plan book — the caller must gate on "
                            + "feasibility before committing, not rely on this method to catch it");
        }
        byPlatoonId.put(result.platoonId(), result);
    }

    /**
     * @param platoonId the platoon to look up
     * @return that platoon's committed result, or empty if it holds no route
     */
    public Optional<DispatchResult> get(long platoonId) {
        return Optional.ofNullable(byPlatoonId.get(platoonId));
    }

    /**
     * Every platoon of one party that currently holds a route.
     *
     * @param partyId the party whose waves to collect
     * @return that party's committed platoons, in no guaranteed order; empty if it has none
     */
    public List<DispatchResult> platoonsOf(long partyId) {
        List<DispatchResult> matches = new ArrayList<>();
        for (DispatchResult result : byPlatoonId.values()) {
            if (result.partyId() == partyId) {
                matches.add(result);
            }
        }
        return matches;
    }

    /**
     * @return every committed result, as an unmodifiable snapshot copy — the internal map keeps
     *         changing as dispatch and repair proceed, so handing back a live view would surprise
     *         any caller that iterates while something else commits
     */
    public List<DispatchResult> all() {
        return Collections.unmodifiableList(new ArrayList<>(byPlatoonId.values()));
    }

    /**
     * Drops a platoon's committed route. A platoon that holds none is a no-op rather than an error,
     * matching {@link ReservationLedger#release(long)}'s idempotent-removal convention.
     *
     * @param platoonId the platoon to forget
     */
    public void remove(long platoonId) {
        byPlatoonId.remove(platoonId);
    }

    /**
     * Where a committed platoon should be at {@code nowBucket}, according to the walk it reserved.
     *
     * <p>The walk is a sequence of moves, each ending at a known {@code (node, bucket)} state, so the
     * projection is a scan rather than a simulation: the platoon has completed every step that ends
     * at or before {@code nowBucket}, and is therefore at the destination state of the <em>last</em>
     * such step. Three cases fall out of that naturally:
     *
     * <ul>
     *   <li>{@code nowBucket} at or before the departure bucket — the platoon has not left yet, so
     *       the answer is the walk's origin.</li>
     *   <li>{@code nowBucket} at or past the arrival bucket — every step qualifies, so the scan
     *       returns the last step's destination, which <em>is</em> {@link TimedWalk#arrival()}.</li>
     *   <li>a walk with no steps (origin already was the shelter) — there is nowhere else to be, so
     *       the origin is the answer at any bucket.</li>
     * </ul>
     *
     * <p>Mid-traversal is resolved conservatively to the last state the platoon is known to have
     * reached: a platoon partway along an arc is reported at the node it left, because that is the
     * last position the reserved plan actually places it at.
     *
     * @param platoonId the committed platoon to project
     * @param nowBucket the current bucket
     * @return the platoon's projected space-time state
     * @throws IllegalArgumentException if the platoon holds no committed route — the only caller is
     *                                  repair, which reaches this method with an id it already knows
     *                                  is committed, so an unknown id is a caller bug rather than a
     *                                  legitimate "no answer"
     */
    public SpaceTimeState projectedPosition(long platoonId, int nowBucket) {
        DispatchResult result = byPlatoonId.get(platoonId);
        if (result == null) {
            throw new IllegalArgumentException(
                    "platoon " + platoonId + " holds no committed route in the plan book");
        }

        TimedWalk walk = result.searchResult().walk();
        if (nowBucket <= walk.origin().bucket() || walk.steps().isEmpty()) {
            return walk.origin();
        }

        SpaceTimeState reached = walk.origin();
        for (TimedWalk.Step step : walk.steps()) {
            if (step.to().bucket() > nowBucket) {
                break;
            }
            reached = step.to();
        }
        return reached;
    }

    /** Empties the book — what {@code ActivePlan.reset(...)} calls to begin a fresh session. */
    public void clear() {
        byPlatoonId.clear();
    }
}