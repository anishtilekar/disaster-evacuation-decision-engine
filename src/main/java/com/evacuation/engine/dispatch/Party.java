package com.evacuation.engine.dispatch;

import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.model.entity.EvacuationRequest;
import com.evacuation.engine.model.enums.EvacuationPriority;

import java.time.LocalDateTime;

/**
 * One evacuation request, translated into the dense-index world the dispatch loop and the
 * space-time search actually operate in — the design's {@code k_i} (group size) and {@code r_i}
 * (request time) for party {@code i}, plus the dense origin the search needs, rather than a JPA
 * entity carrying lazy associations the planning loop has no business touching.
 *
 * <p>{@link #requestedAt()} is kept as a real timestamp rather than pre-converted into a bucket:
 * buckets are always relative to "now" at the moment a plan is computed, and a {@code Party} built
 * once but planned against later would otherwise carry a silently stale bucket. Whoever runs the
 * dispatch loop converts {@code requestedAt} into a bucket at the point it actually needs one,
 * using the same {@code now} the rest of that planning pass uses.
 *
 * <p>A party is not itself routed — {@link DispatchService} splits it into one or more
 * {@link Platoon}s (the design's {@code p_ij}, {@code Sum p_ij = k_i}) and routes each separately,
 * since a large group is a wave of arc-traversals over time, not a single instantaneous move.
 *
 * @param partyId                 the source {@link EvacuationRequest#getEvacuationRequestId()}
 * @param originNodeIndex         dense CSR index of the request's source road node
 * @param numberOfPeople          the design's {@code k_i}; always positive
 * @param priority                drives the "class desc" half of dispatch ordering
 * @param medicalAssistanceRequired whether this party's platoons pay the shelter mismatch penalty
 *                                   at a non-medical-facility shelter
 * @param requestedAt             the design's {@code r_i}, kept as a real timestamp — see above
 */
public record Party(long partyId, int originNodeIndex, int numberOfPeople,
                    EvacuationPriority priority, boolean medicalAssistanceRequired,
                    LocalDateTime requestedAt) {

    public Party {
        if (originNodeIndex < 0) {
            throw new IllegalArgumentException("originNodeIndex must be >= 0, got " + originNodeIndex);
        }
        if (numberOfPeople <= 0) {
            throw new IllegalArgumentException("numberOfPeople must be > 0, got " + numberOfPeople);
        }
        if (priority == null) {
            throw new IllegalArgumentException("priority must not be null");
        }
        if (requestedAt == null) {
            throw new IllegalArgumentException("requestedAt must not be null");
        }
    }

    /**
     * Translates a persisted request into a {@code Party}, resolving its source node against the
     * current snapshot.
     *
     * @throws IllegalStateException if the request's source node is not present in {@code snapshot}
     *                                — a genuine data-consistency problem (a stale or orphaned
     *                                request referencing a node the current graph no longer has),
     *                                not something a caller can recover from by retrying
     */
    public static Party fromEvacuationRequest(EvacuationRequest request, GraphSnapshot snapshot) {
        long sourceNodeDbId = request.getSourceRoadNode().getNodeId();
        Integer originNodeIndex = snapshot.indexOfDbNodeId(sourceNodeDbId);
        if (originNodeIndex == null) {
            throw new IllegalStateException(
                    "Evacuation request " + request.getEvacuationRequestId()
                            + " references source node " + sourceNodeDbId
                            + ", which is not present in the current graph snapshot");
        }

        return new Party(
                request.getEvacuationRequestId(),
                originNodeIndex,
                request.getNumberOfPeople(),
                request.getPriority(),
                Boolean.TRUE.equals(request.getMedicalAssistanceRequired()),
                request.getRequestedAt());
    }
}
