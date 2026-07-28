package com.evacuation.engine.dispatch;

import com.evacuation.engine.algorithm.spacetime.SearchResult;

/**
 * The outcome of committing one {@link Platoon}'s space-time search — the search's own
 * {@link SearchResult} (walk, shelter, feasibility) plus the platoon identity needed to trace it
 * back to a party and a wave, reserve its cells in the {@link ReservationLedger}, and persist it.
 *
 * <p>Deliberately does not duplicate {@link SearchResult#feasible()} as a second field; a
 * {@code DispatchResult} is only ever constructed from a real search outcome, so
 * {@link #feasible()} simply delegates.
 *
 * @param partyId      the {@link Party} this platoon belongs to
 * @param platoonId    the {@link Platoon#platoonId()} this result is for
 * @param waveIndex    the platoon's position among its party's waves, carried through for
 *                     diagnostics and for building an {@link InstructionSet.Shortfall} if a later
 *                     wave of the same party fails
 * @param size         the platoon's size, carried through so a caller need not re-look up the
 *                     {@link Platoon} to know how many people this result accounts for
 * @param searchResult the search's own outcome
 */
public record DispatchResult(long partyId, long platoonId, int waveIndex, int size,
                             SearchResult searchResult) {

    public DispatchResult {
        if (searchResult == null) {
            throw new IllegalArgumentException("searchResult must not be null");
        }
    }

    /** Delegates to the wrapped {@link SearchResult}; see its own Javadoc for what this means. */
    public boolean feasible() {
        return searchResult.feasible();
    }
}
