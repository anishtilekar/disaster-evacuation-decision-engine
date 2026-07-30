package com.evacuation.engine.service;

import com.evacuation.engine.dispatch.ActivePlan;
import com.evacuation.engine.dispatch.InstructionSet;
import com.evacuation.engine.dispatch.RepairService;
import com.evacuation.engine.dto.hazard.request.HazardEventRequest;
import com.evacuation.engine.dto.hazard.response.HazardEventResponse;
import com.evacuation.engine.dto.hazard.response.HazardTimelineResponse;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimeline;
import com.evacuation.engine.graph.time.HazardTimelineCache;
import com.evacuation.engine.graph.time.TimeModel;
import com.evacuation.engine.loader.GraphCache;
import com.evacuation.engine.mapper.hazard.HazardEventMapper;
import com.evacuation.engine.model.entity.HazardEvent;
import com.evacuation.engine.repository.graph.HazardEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Application service behind the hazard endpoints.
 *
 * <p><strong>Recompiling the timeline is part of creating a hazard, not an optimisation.</strong>
 * The search layer never reads {@code hazard_events}; it reads the compiled {@link
 * com.evacuation.engine.graph.time.HazardTimeline} held in the cache. A hazard that is persisted but
 * not compiled is therefore inert — every route planned after the POST would be planned as though the
 * flood front did not exist, and the API would have returned 201 to say so. The row would only start
 * mattering whenever some unrelated event happened to trigger a reload, which is the worst possible
 * failure mode for this particular system: silent, delayed, and invisible to the operator who just
 * reported the hazard.
 *
 * <p>So the write and the swap happen together. Both run inside the one transaction, which is also
 * what makes the compile see the new row: the compiler's {@code findByActive} query forces a flush of
 * the pending insert before it executes, so the timeline it produces already includes this hazard.
 *
 * <p>This is the seam the repair service later generalises — any event that changes hazard or block
 * state recompiles and swaps — so the shape here is deliberately the shape that will be reused:
 * persist, recompile against the current snapshot, publish atomically, respond.
 *
 * <p><strong>Repair is wired here too, mirroring {@code GraphAdminService.blockRoad}.</strong> A
 * hazard event can raise cells to LETHAL exactly as a block does, so the same
 * old-timeline-vs-new-timeline diff that method's sibling scenario in {@code PerturbationScenarios}
 * uses — now {@link HazardTimeline#newlyLethal} — is applied here: capture the timeline as it stood,
 * recompile, diff, and hand {@link RepairService} exactly the cells that just became lethal. If no
 * dispatch session is active yet there is nothing to repair, so that case is skipped rather than
 * routed through {@code onEvent}'s own no-op path — there is also no prior timeline to diff against
 * in that case.
 *
 * <p><strong>Epoch discipline matches {@code GraphAdminService}</strong>: a live session's fixed
 * {@link ActivePlan#sessionEpoch()} anchors the recompile so bucket 0 does not silently move for
 * reservations already sitting in that session's ledger; with no session active, {@code now()} is
 * the honest epoch since there is nothing to stay consistent with.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HazardService {

    private final HazardEventRepository hazardEventRepository;
    private final HazardEventMapper hazardEventMapper;
    private final GraphCache graphCache;
    private final HazardTimelineCache hazardTimelineCache;
    private final ActivePlan activePlan;
    private final RepairService repairService;
    private final TimeModel timeModel;

    /**
     * Persists a new hazard event, republishes the hazard timeline so it takes effect immediately,
     * and repairs any committed platoons the newly-lethal cells have just compromised.
     *
     * @param request the operator's hazard input
     * @return the persisted hazard event
     * @throws IllegalStateException if no graph snapshot has been built yet — the server genuinely
     *                               is not ready to compile a timeline, and pretending otherwise
     *                               would leave the hazard uncompiled
     */
    @Transactional
    public HazardEventResponse createHazardEvent(HazardEventRequest request) {
        HazardEvent saved = hazardEventRepository.save(hazardEventMapper.toEntity(request));

        GraphSnapshot snapshot = graphCache.get();
        boolean sessionActive = activePlan.isActive();
        HazardTimeline oldTimeline =
                sessionActive && hazardTimelineCache.isLoaded() ? hazardTimelineCache.get() : null;
        LocalDateTime epoch = sessionActive ? activePlan.sessionEpoch() : LocalDateTime.now();

        // Recompile against the current graph and swap the timeline in — the cache logs its own
        // summary, so nothing to repeat here.
        HazardTimeline newTimeline = hazardTimelineCache.reload(snapshot, epoch);

        log.info("Hazard event {} created: type={}, origin=({}, {}); hazard timeline recompiled",
                saved.getHazardEventId(), saved.getHazardType(),
                saved.getOriginLatitude(), saved.getOriginLongitude());

        if (oldTimeline != null) {
            LocalDateTime now = LocalDateTime.now();
            long secondsSinceEpoch = Duration.between(epoch, now).toSeconds();
            int nowBucket = secondsSinceEpoch <= 0 ? 0 : timeModel.bucketsForSeconds(secondsSinceEpoch);

            HazardTimeline.NewlyLethalCells newlyLethal =
                    HazardTimeline.newlyLethal(oldTimeline, newTimeline, nowBucket);
            InstructionSet repair = repairService.onEvent(
                    newlyLethal.edgeSlots(), newlyLethal.nodeIndices(), now);

            log.info("Repair triggered by hazard event {}: {} platoons replanned, {} stranded",
                    saved.getHazardEventId(), repair.committed().size(), repair.shortfalls().size());
        }

        return hazardEventMapper.toResponse(saved);
    }

    /**
     * A compact read of the currently-compiled timeline, for a map overlay: every edge slot and
     * node that turns LETHAL within the horizon, and the bucket it first does. See
     * {@link HazardTimelineResponse} for why onset buckets rather than the full packed grid.
     *
     * @return the current timeline's onsets, or {@code null} if none has been compiled yet
     */
    @Transactional(readOnly = true)
    public HazardTimelineResponse getTimeline() {
        if (!hazardTimelineCache.isLoaded()) {
            return null;
        }
        GraphSnapshot snapshot = graphCache.get();
        HazardTimeline timeline = hazardTimelineCache.get();
        int horizon = timeline.horizonBuckets();

        List<HazardTimelineResponse.EdgeOnset> edgeOnsets = new ArrayList<>();
        for (int slot = 0; slot < snapshot.edgeSlotCount(); slot++) {
            for (int bucket = 0; bucket < horizon; bucket++) {
                if (timeline.isEdgeLethal(slot, bucket)) {
                    edgeOnsets.add(new HazardTimelineResponse.EdgeOnset(slot, bucket));
                    break;
                }
            }
        }

        List<HazardTimelineResponse.NodeOnset> nodeOnsets = new ArrayList<>();
        for (int nodeIndex = 0; nodeIndex < snapshot.nodeCount(); nodeIndex++) {
            for (int bucket = 0; bucket < horizon; bucket++) {
                if (timeline.isNodeLethal(nodeIndex, bucket)) {
                    nodeOnsets.add(new HazardTimelineResponse.NodeOnset(nodeIndex, bucket));
                    break;
                }
            }
        }

        return new HazardTimelineResponse(timeline.graphVersion(), timeline.timelineVersion(),
                timeline.builtAt(), horizon, edgeOnsets, nodeOnsets);
    }
}