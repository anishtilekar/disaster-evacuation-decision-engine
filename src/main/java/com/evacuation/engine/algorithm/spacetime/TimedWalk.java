package com.evacuation.engine.algorithm.spacetime;

import java.util.List;

/**
 * A reconstructed space-time path: what the engine actually decides for a platoon — a departure
 * bucket and the sequence of moves that follows from it.
 *
 * <p>The design defines a platoon's plan as a departure time plus a sequence of arcs, and this
 * generalizes that by one move type. In the time-expanded search a move is either traversing an edge
 * slot (advancing {@code tau_a} buckets) or <em>waiting</em> in place while the clock advances one
 * bucket, which is a real decision and often the right one: holding a party at a safe node for a
 * minute to let a bridge clear, or to let a hazard front pass an arc, beats leaving early into
 * congestion. A walk that dropped its waits would not reconstruct into a followable instruction.
 *
 * <p>{@code origin} carries the departure state, so the walk is self-describing even when it is
 * trivial. A trivial walk — origin equals destination, as when the source node already hosts an
 * eligible shelter — has an empty {@code steps} list, never {@code null}, per the same
 * never-null-collections convention the point-query results follow.
 *
 * @param origin          the departure state {@code (v0, b0)}
 * @param steps           the ordered moves from {@code origin}; empty for a trivial walk, never {@code null}
 * @param totalCost       the search's accumulated cost of the walk
 * @param totalDistanceKm the summed road distance travelled, in kilometres — a separate quantity from
 *                        {@code totalCost}, and not interchangeable with it
 */
public record TimedWalk(SpaceTimeState origin, List<Step> steps,
                        double totalCost, double totalDistanceKm) {

    /**
     * One move in the walk: either a wait or an edge traversal.
     *
     * <p>A wait has {@code isWait == true} and {@code edgeSlot == -1}, since staying put consumes no
     * CSR edge; {@code to} is then the same node one bucket later. A traversal carries the slot
     * actually used — the slot rather than the {@code edgeDbId}, because a bidirectional road
     * contributes two slots sharing one id and only the slot says which way the party went.
     *
     * @param from     the state the move starts in
     * @param to       the state it ends in
     * @param isWait   {@code true} if this move waits in place rather than traversing
     * @param edgeSlot the CSR slot traversed, or {@code -1} for a wait
     */
    public record Step(SpaceTimeState from, SpaceTimeState to, boolean isWait, int edgeSlot) {
    }

    /**
     * @return the state this walk ends in — the last step's destination, or {@code origin} if there
     *         are no steps
     */
    public SpaceTimeState arrival() {
        return steps.isEmpty() ? origin : steps.get(steps.size() - 1).to();
    }

    /**
     * The zero-move walk: already at the destination, nothing to do and nothing to cost.
     *
     * @param origin the state that is both departure and arrival
     * @return a walk with no steps and zero cost
     */
    public static TimedWalk trivial(SpaceTimeState origin) {
        return new TimedWalk(origin, List.of(), 0.0, 0.0);
    }
}