package com.evacuation.engine.service;

import com.evacuation.engine.dto.disaster.request.DisasterCreateRequest;
import com.evacuation.engine.dto.disaster.request.DisasterZoneRequest;
import com.evacuation.engine.dto.disaster.response.DisasterResponse;
import com.evacuation.engine.dto.disaster.response.DisasterZoneResponse;
import com.evacuation.engine.mapper.disaster.DisasterMapper;
import com.evacuation.engine.mapper.disaster.DisasterZoneMapper;
import com.evacuation.engine.model.entity.Disaster;
import com.evacuation.engine.model.entity.DisasterZone;
import com.evacuation.engine.repository.disaster.DisasterRepository;
import com.evacuation.engine.repository.disaster.DisasterZoneRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service behind the disaster and disaster-zone creation endpoints.
 *
 * <p><strong>Create-only, deliberately.</strong> {@link com.evacuation.engine.dto.disaster.request}
 * already carries update DTOs and mappers for a fuller admin surface, but nothing in this project
 * needs update/list/delete for disasters or zones yet — {@link EvacuationRequestService} is the only
 * consumer, and it only ever needs a disaster and a zone to already exist so a request can reference
 * them by id. Building the rest of that admin surface now would be speculative; this exists to close
 * exactly the gap that blocks evacuation-request intake, matching the same
 * "two endpoints only, more arrives when something needs it" discipline
 * {@link GraphAdminService}/{@link HazardService} already follow.
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
}
