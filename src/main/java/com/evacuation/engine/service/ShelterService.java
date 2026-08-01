package com.evacuation.engine.service;

import com.evacuation.engine.dispatch.ActivePlan;
import com.evacuation.engine.dto.evacuation.request.ShelterRequestDTO;
import com.evacuation.engine.dto.evacuation.request.ShelterUpdateRequestDTO;
import com.evacuation.engine.dto.evacuation.response.ShelterResponse;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimelineCache;
import com.evacuation.engine.loader.GraphCache;
import com.evacuation.engine.mapper.evacuation.ShelterMapper;
import com.evacuation.engine.model.entity.Shelter;
import com.evacuation.engine.model.enums.ShelterStatus;
import com.evacuation.engine.repository.evacuation.ShelterRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The admin-only shelter surface: add a shelter, open/close one, adjust its capacity. Nothing here
 * existed before this class — {@link ShelterRequestDTO}, {@link ShelterResponse}, and
 * {@link ShelterMapper} were built early but never got a service or controller; shelters have only
 * ever entered the system through {@code osm.OsmImportService} at ward-import time.
 *
 * <p><strong>Why every write here ends by reloading the graph.</strong> {@code TimeExpandedDijkstra}
 * and every other search read shelters exclusively from {@link GraphSnapshot#shelters()}, never from
 * {@link ShelterRepository} directly — the same reason a blocked road is invisible to routing until
 * {@code GraphAdminService} recompiles the hazard timeline. A shelter saved to the database but not
 * yet folded into a snapshot cannot be routed to, so {@link #republishGraph()} is not an optimization,
 * it is what makes the write real. {@code loader.GraphBuilder} already re-reads every
 * {@code Shelter} row and re-snaps it to its nearest node on every {@link GraphCache#reload()} call,
 * so this class does no snapping itself — it only has to trigger the rebuild.
 *
 * <p><strong>The graph reload is safe against a live dispatch session, and this is not incidental.</strong>
 * A shelter always snaps onto an <em>existing</em> road node; adding, closing, or resizing one never
 * changes {@code nodeCount} or {@code edgeSlotCount}. {@code DispatchService.ensureSession} checks
 * exactly those two counts to decide whether an active session's {@code ReservationLedger} still
 * matches the current graph shape — so a shelter write here never forces a session restart, and every
 * reservation already sitting in the ledger survives untouched. The hazard timeline is recompiled
 * immediately afterward for the same reason {@code GraphAdminService} recompiles after every write:
 * {@code TraversalPolicy}'s constructor refuses to pair a snapshot with a timeline compiled against a
 * different {@code graphVersion}, and {@link GraphCache#reload()} always bumps the version.
 *
 * <p><strong>No {@code RepairService} call, ever — but for two different reasons depending on the
 * write.</strong> Creating a shelter only adds capacity, and {@code RepairService}'s own contract is
 * that capacity-adding events invalidate nothing, so there is nothing to repair. Closing a shelter or
 * cutting its capacity is the opposite — it genuinely removes capacity — but {@code RepairService}
 * finds victims via {@code ReservationLedger.platoonsIntersecting}, which keys on edge slots and
 * node indices, and a shelter is neither. So closing a shelter correctly excludes it from every
 * <em>future</em> plan (the live eligibility filter in {@code DispatchService}/{@code RepairService}
 * reads {@link Shelter#getShelterStatus()} and remaining capacity fresh on every call) but does
 * <strong>not</strong> retroactively re-route platoons already committed to it. That gap is real,
 * documented here rather than silently accepted, and left as follow-up work — extending repair to a
 * shelter-keyed event is a genuine feature, not a bug fix, and out of scope for this change.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ShelterService {

    private final ShelterRepository shelterRepository;
    private final ShelterMapper shelterMapper;
    private final GraphCache graphCache;
    private final HazardTimelineCache hazardTimelineCache;
    private final ActivePlan activePlan;

    /**
     * Adds a new shelter and republishes the graph so it is reachable by the very next search.
     *
     * @param request the operator's input
     * @return the persisted shelter
     */
    @Transactional
    public ShelterResponse createShelter(ShelterRequestDTO request) {
        Shelter entity = shelterMapper.toEntity(request);
        Shelter saved = shelterRepository.save(entity);

        republishGraph();

        log.info("Shelter '{}' (id {}) created with capacity {}; graph reloaded so routing can reach it",
                saved.getShelterName(), saved.getShelterId(), saved.getCapacity());

        return shelterMapper.toResponse(saved);
    }

    /**
     * Opens/closes a shelter and/or adjusts its capacity, then republishes the graph.
     *
     * <p>See the class Javadoc for the one honest limitation this method carries: a status change to
     * something other than {@link ShelterStatus#AVAILABLE}, or a capacity cut, is logged as a warning
     * precisely because it does not retroactively repair anyone already routed there.
     *
     * @param shelterId the shelter to update
     * @param request   the fields to change; either may be {@code null} to leave it as-is
     * @return the updated shelter
     * @throws IllegalArgumentException if no such shelter exists
     */
    @Transactional
    public ShelterResponse updateShelter(Long shelterId, ShelterUpdateRequestDTO request) {
        Shelter shelter = shelterRepository.findById(shelterId)
                .orElseThrow(() -> new IllegalArgumentException("Shelter " + shelterId + " not found"));

        boolean capacityReduced = request.getCapacity() != null
                && request.getCapacity() < shelter.getCapacity();
        if (request.getCapacity() != null) {
            shelter.setCapacity(request.getCapacity());
        }

        boolean closed = request.getShelterStatus() != null
                && shelter.getShelterStatus() == ShelterStatus.AVAILABLE
                && request.getShelterStatus() != ShelterStatus.AVAILABLE;
        if (request.getShelterStatus() != null) {
            shelter.setShelterStatus(request.getShelterStatus());
        }

        Shelter saved = shelterRepository.save(shelter);
        republishGraph();

        if (closed) {
            log.warn("Shelter {} closed; platoons already committed to it are NOT retroactively "
                    + "repaired -- only plans made from here on see the closure", shelterId);
        }
        if (capacityReduced) {
            log.warn("Shelter {} capacity reduced; platoons already committed against the old "
                    + "capacity are NOT retroactively repaired -- only plans made from here on see "
                    + "the new limit", shelterId);
        }

        log.info("Shelter {} updated: status={}, capacity={}", shelterId,
                saved.getShelterStatus(), saved.getCapacity());

        return shelterMapper.toResponse(saved);
    }

    /**
     * Lists every shelter, regardless of status — the admin console's inventory view, as opposed to
     * the live routing view {@link GraphSnapshot#shelters()} exposes only the reachable subset of.
     *
     * @return every persisted shelter, response-mapped
     */
    @Transactional(readOnly = true)
    public List<ShelterResponse> listShelters() {
        return shelterRepository.findAll().stream()
                .map(shelterMapper::toResponse)
                .toList();
    }

    /**
     * Rebuilds the graph snapshot (which re-reads and re-snaps every shelter row) and recompiles the
     * hazard timeline to match its new {@code graphVersion} — the same two-step publish
     * {@code GraphAdminService} performs after every write, anchored to the live session's epoch when
     * one exists so bucket 0 does not move under reservations already sitting in its ledger.
     */
    private void republishGraph() {
        GraphSnapshot snapshot = graphCache.reload();
        LocalDateTime epoch = activePlan.isActive() ? activePlan.sessionEpoch() : LocalDateTime.now();
        hazardTimelineCache.reload(snapshot, epoch);
    }
}
