package com.evacuation.engine.service;

import com.evacuation.engine.dto.disaster.request.DisasterCreateRequest;
import com.evacuation.engine.dto.disaster.request.DisasterZoneRequest;
import com.evacuation.engine.dto.disaster.response.DisasterResponse;
import com.evacuation.engine.dto.disaster.response.DisasterZoneResponse;
import com.evacuation.engine.mapper.disaster.DisasterMapper;
import com.evacuation.engine.mapper.disaster.DisasterZoneMapper;
import com.evacuation.engine.model.entity.Disaster;
import com.evacuation.engine.model.entity.DisasterZone;
import com.evacuation.engine.model.enums.DisasterStatus;
import com.evacuation.engine.repository.disaster.DisasterRepository;
import com.evacuation.engine.repository.disaster.DisasterZoneRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Application service behind the disaster and disaster-zone creation endpoints, plus the two read
 * methods a USER's self-service submission needs.
 *
 * <p><strong>Writes are create-only, deliberately.</strong> {@link com.evacuation.engine.dto.disaster.request}
 * already carries update DTOs and mappers for a fuller admin surface, but nothing in this project
 * needs update/delete for disasters or zones yet — building the rest of that admin surface now would
 * be speculative, matching the same "only what something needs" discipline
 * {@link GraphAdminService}/{@link HazardService} already follow.
 *
 * <p><strong>The two list methods exist because the frontend split (Part 4) genuinely needs
 * them, not as an admin convenience.</strong> {@code POST /api/disasters} is admin-only — a USER
 * cannot create one — so a USER submitting their own evacuation request still has to reference a
 * {@code disasterId}/{@code disasterZoneId} that already exists, and had no way to discover one
 * without these. {@link #listActiveDisasters()} and {@link #listZonesForDisaster(Long)} are that
 * discovery path, kept to exactly the read shape a submission form needs — no filtering by anything
 * but the one status ({@link DisasterStatus#ACTIVE}) that matters for "can I evacuate into this right now".
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DisasterService {

    private final DisasterRepository disasterRepository;
    private final DisasterMapper disasterMapper;
    private final DisasterZoneRepository disasterZoneRepository;
    private final DisasterZoneMapper disasterZoneMapper;

    /**
     * Records a new disaster.
     *
     * @param request the operator's input
     * @return the persisted disaster
     */
    @Transactional
    public DisasterResponse createDisaster(DisasterCreateRequest request) {
        Disaster saved = disasterRepository.save(disasterMapper.toEntity(request));
        log.info("Disaster {} created: {} ({})", saved.getDisasterId(), saved.getDisasterName(),
                saved.getDisasterType());
        return disasterMapper.toResponse(saved);
    }

    /**
     * Records a new zone under an existing disaster.
     *
     * @param request the operator's input, carrying the owning disaster by id
     * @return the persisted zone
     * @throws IllegalArgumentException if the referenced disaster does not exist
     */
    @Transactional
    public DisasterZoneResponse createZone(DisasterZoneRequest request) {
        Disaster disaster = disasterRepository.findById(request.getDisasterId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Disaster " + request.getDisasterId() + " not found"));

        DisasterZone entity = disasterZoneMapper.toEntity(request);
        entity.setDisaster(disaster);

        DisasterZone saved = disasterZoneRepository.save(entity);
        log.info("Disaster zone {} created under disaster {}: {}", saved.getZoneId(),
                disaster.getDisasterId(), saved.getZoneName());

        return disasterZoneMapper.toResponse(saved);
    }

    /**
     * Every currently-{@link DisasterStatus#ACTIVE} disaster — what a submission form offers a USER
     * to report their evacuation under.
     *
     * @return active disasters, response-mapped
     */
    @Transactional(readOnly = true)
    public List<DisasterResponse> listActiveDisasters() {
        return disasterRepository.findByStatus(DisasterStatus.ACTIVE).stream()
                .map(disasterMapper::toResponse)
                .toList();
    }

    /**
     * Every zone under one disaster, regardless of status — once a USER has picked a disaster, every
     * one of its zones is a legitimate choice.
     *
     * @param disasterId the owning disaster
     * @return that disaster's zones, response-mapped
     * @throws IllegalArgumentException if no such disaster exists
     */
    @Transactional(readOnly = true)
    public List<DisasterZoneResponse> listZonesForDisaster(Long disasterId) {
        if (!disasterRepository.existsById(disasterId)) {
            throw new IllegalArgumentException("Disaster " + disasterId + " not found");
        }
        return disasterZoneRepository.findByDisaster_DisasterId(disasterId).stream()
                .map(disasterZoneMapper::toResponse)
                .toList();
    }
}
