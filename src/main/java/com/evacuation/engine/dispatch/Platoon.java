package com.evacuation.engine.dispatch;

/**
 * One wave of a {@link Party} — the design's {@code p_ij}, the {@code j}-th chunk of party
 * {@code i}'s total group, sized so it never exceeds a configured maximum. Splitting a large party
 * into platoons is what turns a single instantaneous "move k people" into the flow-over-time
 * problem this engine actually solves: a 200-person party crossing a 30-person-per-bucket bridge
 * is physically a sequence of waves, and {@link DispatchService} plans it that way rather than
 * pretending the whole party is one atomic unit.
 *
 * <p>Deliberately carries no departure bucket or route of its own — those are dispatch-time
 * decisions (when this wave leaves, staggered against its siblings; where it goes) computed fresh
 * each time {@link DispatchService} routes it, not baked into what is otherwise just "who this
 * wave is": how many people, where they start, and whether they need a medical-facility shelter.
 *
 * @param platoonId               an opaque identifier distinct from every other platoon (including
 *                                 this one's own siblings), used as the {@link ReservationLedger}
 *                                 key so each wave's reservations can be released independently
 * @param partyId                 the {@link Party} this wave belongs to
 * @param waveIndex               0-based position among this party's platoons, for convoy staggering
 *                                 and traceability — not a routing input in its own right
 * @param size                    how many people this platoon carries; always positive, and every
 *                                 party's platoon sizes must sum to exactly its {@code numberOfPeople}
 * @param originNodeIndex         copied from the parent {@link Party}; a platoon starts where its
 *                                 party starts
 * @param medicalPreferred        copied from the parent {@link Party#medicalAssistanceRequired()}
 */
public record Platoon(long platoonId, long partyId, int waveIndex, int size, int originNodeIndex,
                      boolean medicalPreferred) {

    public Platoon {
        if (waveIndex < 0) {
            throw new IllegalArgumentException("waveIndex must be >= 0, got " + waveIndex);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0, got " + size);
        }
        if (originNodeIndex < 0) {
            throw new IllegalArgumentException("originNodeIndex must be >= 0, got " + originNodeIndex);
        }
    }
}
