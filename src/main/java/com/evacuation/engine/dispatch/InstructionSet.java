package com.evacuation.engine.dispatch;

import java.util.List;

/**
 * The design's {@code planBook.instructionSet()} — everything one {@code DispatchService.plan(...)}
 * call produced: every platoon that was successfully committed, and every party that could not be
 * fully routed.
 *
 * @param committed  one entry per platoon that reserved a route, across every party planned this
 *                   pass; never {@code null}, empty if nothing was feasible
 * @param shortfalls one entry per party that could not place its full group; never {@code null},
 *                   empty if every party was fully routed
 */
public record InstructionSet(List<DispatchResult> committed, List<Shortfall> shortfalls) {

    public InstructionSet {
        if (committed == null) {
            throw new IllegalArgumentException("committed must not be null (use List.of() for none)");
        }
        if (shortfalls == null) {
            throw new IllegalArgumentException("shortfalls must not be null (use List.of() for none)");
        }
    }

    /**
     * The design's {@code record shortfall for i} — a party whose group could not be fully placed:
     * the pseudocode splits a party into waves and stops at the first infeasible one, so this
     * records everyone from that failed wave onward, not just the single wave that first failed.
     *
     * @param partyId          the {@link Party} that came up short
     * @param unroutedSize     how many people from this party were never committed to a route —
     *                         the failed wave's own size plus every wave after it that was never
     *                         attempted
     * @param failedAtWaveIndex the 0-based {@link Platoon#waveIndex()} of the first wave that could
     *                         not find a feasible route
     */
    public record Shortfall(long partyId, int unroutedSize, int failedAtWaveIndex) {

        public Shortfall {
            if (unroutedSize <= 0) {
                throw new IllegalArgumentException("unroutedSize must be > 0, got " + unroutedSize);
            }
            if (failedAtWaveIndex < 0) {
                throw new IllegalArgumentException(
                        "failedAtWaveIndex must be >= 0, got " + failedAtWaveIndex);
            }
        }
    }
}
