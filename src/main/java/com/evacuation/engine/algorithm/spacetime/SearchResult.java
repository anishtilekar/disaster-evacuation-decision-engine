package com.evacuation.engine.algorithm.spacetime;

import com.evacuation.engine.graph.structure.GraphSnapshot;

/**
 * The outcome of one {@code searchSpaceTime(...)} call — the direct analog of the design's §3.2
 * pseudocode, which either returns {@code reconstruct(parents)} on reaching the sink or falls out of
 * the loop with {@code INFEASIBLE_WITHIN_HORIZON}.
 *
 * <p>Infeasibility here means something narrower than "no path exists": it means no space-time path
 * exists <em>within the compiled horizon T</em>, under the hazard timeline (and, from the capacity
 * phase on, the reservations) in force. The same origin may well be feasible against a later compile,
 * a longer horizon, or once other parties release their cells — so an infeasible result is a
 * planning outcome to escalate, not an error to throw.
 *
 * <p>Which is why the failure branch is a flag beside a sentinel walk rather than a {@code null}
 * {@link TimedWalk}: a caller checks {@link #feasible()} and never a reference, and an infeasible
 * result still reports the origin it failed from — the fact an operator alert needs.
 *
 * <p>The winning shelter travels with the result rather than being re-derived from the walk's
 * arrival node. One node can host several eligible shelters, and under a medical preference their
 * sink costs differ — so the arrival node alone does not identify which one the search actually
 * chose. Dispatch needs that identity directly, to charge the assignment against the right
 * shelter's remaining capacity.
 *
 * <p><strong>{@code shelter} is {@code null} whenever the search was not chasing a shelter at
 * all</strong> — not only on an infeasible result. A {@link Destination.FixedNode} search has no
 * shelter to report even when {@link #feasible()} is {@code true}; {@link #toNode(TimedWalk)} is
 * that branch's factory, the counterpart to {@link #of(TimedWalk, GraphSnapshot.ShelterRef)}. Every
 * caller that reads {@code shelter()} must therefore null-check it rather than assuming it is
 * present whenever {@code feasible()} is.
 *
 * @param feasible whether a space-time path was found within the horizon
 * @param walk     the reconstructed walk when feasible; the origin's trivial walk otherwise, never
 *                 {@code null}
 * @param shelter  the shelter the walk terminates at, when the search was routing to
 *                 {@link Destination.AnyEligibleShelter} and found one; {@code null} for an
 *                 infeasible result or a feasible {@link Destination.FixedNode} result
 */
public record SearchResult(boolean feasible, TimedWalk walk, GraphSnapshot.ShelterRef shelter) {

    /**
     * The design's {@code INFEASIBLE_WITHIN_HORIZON} branch.
     *
     * @param origin the departure state the search failed from
     * @return an infeasible result carrying that origin
     */
    public static SearchResult infeasible(SpaceTimeState origin) {
        return new SearchResult(false, TimedWalk.trivial(origin), null);
    }

    /**
     * The design's {@code return reconstruct(parents)} branch, for a search that reached an
     * eligible shelter.
     *
     * @param walk    the reconstructed space-time path
     * @param shelter the shelter it terminates at
     * @return a feasible result wrapping them
     */
    public static SearchResult of(TimedWalk walk, GraphSnapshot.ShelterRef shelter) {
        return new SearchResult(true, walk, shelter);
    }

    /**
     * The feasible branch for a {@link Destination.FixedNode} search: reached the target node with
     * no shelter to report.
     *
     * @param walk the reconstructed space-time path, ending at the target node
     * @return a feasible result with a {@code null} shelter
     */
    public static SearchResult toNode(TimedWalk walk) {
        return new SearchResult(true, walk, null);
    }
}