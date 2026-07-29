package com.evacuation.engine.service;

import com.evacuation.engine.dispatch.ActivePlan;
import com.evacuation.engine.dispatch.InstructionSet;
import com.evacuation.engine.dispatch.RepairService;
import com.evacuation.engine.dto.graph.request.BlockedRoadRequest;
import com.evacuation.engine.dto.graph.response.BlockedRoadResponse;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimelineCache;
import com.evacuation.engine.loader.GraphCache;
import com.evacuation.engine.mapper.graph.BlockedRoadMapper;
import com.evacuation.engine.model.entity.BlockedRoad;
import com.evacuation.engine.model.entity.Disaster;
import com.evacuation.engine.model.entity.RoadEdge;
import com.evacuation.engine.repository.disaster.DisasterRepository;
import com.evacuation.engine.repository.graph.BlockedRoadRepository;
import com.evacuation.engine.repository.graph.RoadEdgeRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Application service behind the block/unblock endpoints — the operator's "this road is impassable"
 * and "this road is open again" surface.
 *
 * <p>Shaped exactly like {@link HazardService}: persist, recompile against the current snapshot,
 * publish atomically, respond. The reasoning is the same too. The search layer never reads
 * {@code blocked_roads}; it reads the compiled {@link com.evacuation.engine.graph.time.HazardTimeline}
 * held in the cache, so a blockage that is persisted but not compiled is inert — every route planned
 * after the POST would send people down a road the operator has just reported as impassable, and the
 * API would have returned 201 to say it had been handled. So the write and the swap happen together,
 * inside one transaction, whose flush-before-query semantics are also what let the compile see the
 * row this call just wrote.
 *
 * <p><strong>One thing this service does that {@code HazardService} never had to.</strong>
 * {@link BlockedRoadMapper#toEntity} deliberately ignores the {@code disaster} and {@code roadEdge}
 * associations — the same pattern {@code RoadEdgeMapper} follows for its own node associations — so
 * both are resolved by id here and set on the mapped entity before it is saved. A mapper that
 * silently fabricated detached association stubs would be far worse than one that leaves the job to
 * the layer that owns the repositories.
 *
 * <p><strong>Epoch discipline.</strong> Every recompile here is anchored with the explicit
 * {@code reload(snapshot, epoch)} overload, never the {@code now()}-defaulting one. If a dispatch
 * session is live, its fixed {@link ActivePlan#sessionEpoch()} is used: bucket 0 already means a
 * particular real instant to every reservation sitting in that session's ledger, and a block
 * reported mid-evacuation must not silently re-anchor it — that would misalign the whole board
 * against reservations nobody has touched. With no session active there is nothing to stay
 * consistent with, so {@code LocalDateTime.now()} is the honest epoch.
 *
 * <p><strong>Repair is wired to {@link #blockRoad} only.</strong> Blocking a road is precisely the
 * capacity/safety-removing event {@link RepairService#onEvent} exists for, so after the timeline is
 * recompiled, this method resolves the blocked edge's own CSR slots (both directions, if
 * bidirectional — the same reverse lookup {@code HazardTimelineCompiler} builds for its own
 * active-block pass, done here as a direct scan since it is needed for one edge, not the whole
 * graph) and calls repair with them. If no dispatch session is active yet, {@code onEvent} already
 * returns an empty result on its own — this method does not need to check first.
 *
 * <p>{@link #unblockRoad} is deliberately asymmetric and never calls repair. Opening a road adds
 * capacity and safety rather than removing them, and the design's own pseudocode has repair return
 * {@code []} for such events: un-blocking invalidates nobody's plan, because every existing plan was
 * already feasible without that road. The recompile is still required — it is what makes the
 * reopened road usable by the next search — but no repair pass follows it, then or later.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GraphAdminService {

    private final BlockedRoadRepository blockedRoadRepository;
    private final BlockedRoadMapper blockedRoadMapper;
    private final RoadEdgeRepository roadEdgeRepository;
    private final DisasterRepository disasterRepository;
    private final GraphCache graphCache;
    private final HazardTimelineCache hazardTimelineCache;
    private final ActivePlan activePlan;
    private final RepairService repairService;

    /**
     * Records a road as blocked and republishes the hazard timeline so the blockage takes effect for
     * every route planned from here on.
     *
     * @param request the operator's blockage input, carrying the edge and disaster to link it to
     * @return the persisted blockage
     * @throws IllegalArgumentException if the referenced road edge or disaster does not exist
     * @throws IllegalStateException    if no graph snapshot has been built yet — the server genuinely
     *                                  cannot compile a timeline, and pretending otherwise would
     *                                  leave the blockage uncompiled
     */
    @Transactional
    public BlockedRoadResponse blockRoad(BlockedRoadRequest request) {
        RoadEdge roadEdge = roadEdgeRepository.findById(request.getRoadEdgeId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Road edge " + request.getRoadEdgeId() + " not found"));

        Disaster disaster = disasterRepository.findById(request.getDisasterId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Disaster " + request.getDisasterId() + " not found"));

        // The mapper leaves both associations null by design, so they are attached here.
        BlockedRoad entity = blockedRoadMapper.toEntity(request);
        entity.setRoadEdge(roadEdge);
        entity.setDisaster(disaster);

        BlockedRoad saved = blockedRoadRepository.save(entity);

        // Recompile against the current graph and swap the timeline in — the cache logs its own
        // summary, so nothing to repeat here.
        GraphSnapshot snapshot = graphCache.get();
        LocalDateTime epoch = currentEpoch();
        hazardTimelineCache.reload(snapshot, epoch);

        log.info("Road edge {} blocked (blocked road {}); hazard timeline recompiled at epoch {}",
                roadEdge.getEdgeId(), saved.getBlockedRoadId(), epoch);

        // The event happened now, in real wall-clock time — never the session epoch itself, which
        // would make every repair land at bucket 0 regardless of how long the session has actually
        // been running. onEvent is a no-op if no session is active, so no isActive() check first.
        Set<Integer> affectedSlots = slotsForEdge(snapshot, roadEdge.getEdgeId());
        InstructionSet repair = repairService.onEvent(affectedSlots, Set.of(), LocalDateTime.now());

        log.info("Repair triggered by blocked road {}: {} platoons replanned, {} stranded",
                saved.getBlockedRoadId(), repair.committed().size(), repair.shortfalls().size());

        return blockedRoadMapper.toResponse(saved);
    }

    /**
     * Both directed CSR slots a bidirectional edge occupies (one, for a one-way edge) — a direct scan
     * since this is resolving one specific edge, not building the whole-graph reverse index
     * {@code HazardTimelineCompiler} needs for its own pass over every active block at once.
     */
    private Set<Integer> slotsForEdge(GraphSnapshot snapshot, long edgeDbId) {
        Set<Integer> slots = new HashSet<>();
        for (int slot = 0; slot < snapshot.edgeSlotCount(); slot++) {
            if (snapshot.edgeDbId(slot) == edgeDbId) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /**
     * Deactivates an existing blockage and republishes the hazard timeline so the reopened road
     * becomes usable again.
     *
     * @param blockedRoadId the blockage to lift
     * @return the deactivated blockage
     * @throws IllegalArgumentException if no such blockage exists
     * @throws IllegalStateException    if no graph snapshot has been built yet
     */
    @Transactional
    public BlockedRoadResponse unblockRoad(Long blockedRoadId) {
        BlockedRoad blockedRoad = blockedRoadRepository.findById(blockedRoadId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Blocked road " + blockedRoadId + " not found"));

        blockedRoad.setActive(Boolean.FALSE);
        BlockedRoad saved = blockedRoadRepository.save(blockedRoad);

        // Adding capacity invalidates no existing plan, but the timeline still has to be recompiled:
        // until it is, the reopened road stays lethal to every search that consults it.
        GraphSnapshot snapshot = graphCache.get();
        LocalDateTime epoch = currentEpoch();
        hazardTimelineCache.reload(snapshot, epoch);

        log.info("Blocked road {} deactivated (edge {}); hazard timeline recompiled at epoch {}",
                saved.getBlockedRoadId(),
                saved.getRoadEdge() == null ? null : saved.getRoadEdge().getEdgeId(),
                epoch);

        return blockedRoadMapper.toResponse(saved);
    }

    /**
     * The epoch this call's recompile must be anchored to: the live session's fixed epoch if one
     * exists, so bucket 0 keeps meaning what every reservation in its ledger already assumes, and
     * otherwise now, since there is no prior compile to stay aligned with.
     *
     * <p>{@link ActivePlan#sessionEpoch()} throws when no session is active, which is exactly why
     * the {@code isActive()} check gates it — in this usage it cannot throw.
     */
    private LocalDateTime currentEpoch() {
        return activePlan.isActive() ? activePlan.sessionEpoch() : LocalDateTime.now();
    }
}