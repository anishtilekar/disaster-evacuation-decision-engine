package com.evacuation.engine.service;

import com.evacuation.engine.dto.hazard.request.HazardEventRequest;
import com.evacuation.engine.dto.hazard.response.HazardEventResponse;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimelineCache;
import com.evacuation.engine.loader.GraphCache;
import com.evacuation.engine.mapper.hazard.HazardEventMapper;
import com.evacuation.engine.model.entity.HazardEvent;
import com.evacuation.engine.repository.graph.HazardEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HazardService {

    private final HazardEventRepository hazardEventRepository;
    private final HazardEventMapper hazardEventMapper;
    private final GraphCache graphCache;
    private final HazardTimelineCache hazardTimelineCache;

    /**
     * Persists a new hazard event and republishes the hazard timeline so it takes effect immediately.
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

        // Recompile against the current graph and swap the timeline in — the cache logs its own
        // summary, so nothing to repeat here.
        GraphSnapshot snapshot = graphCache.get();
        hazardTimelineCache.reload(snapshot);

        log.info("Hazard event {} created: type={}, origin=({}, {}); hazard timeline recompiled",
                saved.getHazardEventId(), saved.getHazardType(),
                saved.getOriginLatitude(), saved.getOriginLongitude());

        return hazardEventMapper.toResponse(saved);
    }
}