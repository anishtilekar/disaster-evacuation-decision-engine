package com.evacuation.engine.graph.time;

/**
 * Per-(cell, bucket) safety classification in the STRIDE hazard timeline.
 *
 * <p>Each grid cell, at each discrete time bucket, is one of three states:
 * <ul>
 *   <li>{@link #SAFE} — no hazard; traversal carries no risk penalty.</li>
 *   <li>{@link #RISKY} — passable but costly; the traversal is allowed and priced up by a risk
 *       factor carried on {@code HazardInterval}, not by this enum.</li>
 *   <li>{@link #LETHAL} — never traversable; no route may enter the cell during such a bucket.</li>
 * </ul>
 */
public enum HazardState {

    /** No hazard: traversal carries no penalty. */
    SAFE,

    /** Passable but costly: allowed, with a risk factor applied elsewhere (on {@code HazardInterval}). */
    RISKY,

    /** Impassable: a route may never traverse a cell in this state. */
    LETHAL;

    /**
     * Whether this state forbids traversal outright.
     *
     * @return {@code true} only for {@link #LETHAL}; {@code SAFE} and {@code RISKY} are both passable
     *         (RISKY is priced via its risk factor rather than blocked)
     */
    public boolean blocksTraversal() {
        return this == LETHAL;
    }
}