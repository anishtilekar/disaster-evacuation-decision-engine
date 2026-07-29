package com.evacuation.engine.dispatch;

import com.evacuation.engine.algorithm.spacetime.SearchResult;
import com.evacuation.engine.model.enums.EvacuationPriority;

/**
 * The outcome of committing one {@link Platoon}'s space-time search — the search's own
 * {@link SearchResult} (walk, shelter, feasibility) plus the platoon identity needed to trace it
 * back to a party and a wave, reserve its cells in the {@link ReservationLedger}, and persist it.
 *
 * <p>Deliberately does not duplicate {@link SearchResult#feasible()} as a second field; a
 * {@code DispatchResult} is only ever constructed from a real search outcome, so
 * {@link #feasible()} simply delegates.
 *
 * <p><strong>Why {@code priority} and {@code medicalPreferred} are carried here, not just on
 * {@link Party}/{@link Platoon}.</strong> A repair triggered by an event has no batch of
 * {@code Party} objects to work from — it only has the {@code PlanBook} this result lives in. Both
 * fields are exactly what a repaired replan needs (priority to order the affected set, the
 * mismatch flag to pass back into the search) and exactly what would otherwise force repair to
 * re-fetch the original request from the database just to recover two booleans-worth of
 * information the plan already had at commit time. Copied in once, here, rather than looked up
 * again later.
 *
 * @param partyId          the {@link Party} this platoon belongs to
 * @param platoonId        the {@link Platoon#platoonId()} this result is for
 * @param waveIndex        the platoon's position among its party's waves, carried through for
 *                         diagnostics and for building an {@link InstructionSet.Shortfall} if a
 *                         later wave of the same party fails
 * @param size             the platoon's size, carried through so a caller need not re-look up the
 *                         {@link Platoon} to know how many people this result accounts for
 * @param priority         the owning {@link Party#priority()} at the time this was committed
 * @param medicalPreferred the {@link Platoon#medicalPreferred()} this result was searched under
 * @param searchResult     the search's own outcome
 */
public record DispatchResult(long partyId, long platoonId, int waveIndex, int size,
                             EvacuationPriority priority, boolean medicalPreferred,
                             SearchResult searchResult) {

    public DispatchResult {
        if (priority == null) {
            throw new IllegalArgumentException("priority must not be null");
        }
        if (searchResult == null) {
            throw new IllegalArgumentException("searchResult must not be null");
        }
    }

    /** Delegates to the wrapped {@link SearchResult}; see its own Javadoc for what this means. */
    public boolean feasible() {
        return searchResult.feasible();
    }
}
