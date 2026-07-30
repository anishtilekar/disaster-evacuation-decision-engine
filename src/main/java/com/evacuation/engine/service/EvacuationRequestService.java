package com.evacuation.engine.service;

import com.evacuation.engine.dto.evacuation.request.EvacuationRequestDTO;
import com.evacuation.engine.dto.evacuation.response.EvacuationRequestResponse;
import com.evacuation.engine.mapper.evacuation.EvacuationRequestMapper;
import com.evacuation.engine.model.entity.Disaster;
import com.evacuation.engine.model.entity.DisasterZone;
import com.evacuation.engine.model.entity.EvacuationRequest;
import com.evacuation.engine.model.entity.RoadNode;
import com.evacuation.engine.model.enums.EvacuationStatus;
import com.evacuation.engine.repository.disaster.DisasterRepository;
import com.evacuation.engine.repository.disaster.DisasterZoneRepository;
import com.evacuation.engine.repository.evacuation.EvacuationRequestRepository;
import com.evacuation.engine.repository.graph.RoadNodeRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Application service behind the evacuation-request intake endpoint — the operator's (or, in the
 * demo map's case, the simulated public's) "N people need to leave from this node" surface that
 * ultimately feeds {@link com.evacuation.engine.dispatch.DispatchService}.
 *
 * <p>Shaped like {@link GraphAdminService}: {@link EvacuationRequestMapper#toEntity} deliberately
 * leaves {@code disaster}, {@code disasterZone}, and {@code sourceRoadNode} null, so all three are
 * resolved by id and attached here before the entity is saved — the same reason
 * {@code BlockedRoadMapper} leaves its own associations for {@code GraphAdminService} to resolve. A
 * mapper that silently fabricated detached association stubs would be worse than one that leaves the
 * job to the layer that owns the repositories.
 *
 * <p>This class does not itself dispatch anything — creating a request only makes it visible to the
 * next planning pass as {@link EvacuationStatus#PENDING}. Turning pending requests into routed
 * platoons is {@code DispatchOrchestrationService}'s job.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EvacuationRequestService {

    private final EvacuationRequestRepository evacuationRequestRepository;
    private final EvacuationRequestMapper evacuationRequestMapper;
    private final DisasterRepository disasterRepository;
    private final DisasterZoneRepository disasterZoneRepository;
    private final RoadNodeRepository roadNodeRepository;

    /**
     * Records a new evacuation request, pending dispatch.
     *
     * @param request the requester's input, carrying the disaster, zone, and source-node references
     *                by id
     * @return the persisted request
     * @throws IllegalArgumentException if any referenced disaster, zone, or road node does not exist
     */
    @Transactional
    public EvacuationRequestResponse createRequest(EvacuationRequestDTO request) {
        Disaster disaster = disasterRepository.findById(request.getDisasterId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Disaster " + request.getDisasterId() + " not found"));

        DisasterZone disasterZone = disasterZoneRepository.findById(request.getDisasterZoneId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Disaster zone " + request.getDisasterZoneId() + " not found"));

        RoadNode sourceRoadNode = roadNodeRepository.findById(request.getSourceRoadNodeId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Road node " + request.getSourceRoadNodeId() + " not found"));

        EvacuationRequest entity = evacuationRequestMapper.toEntity(request);
        entity.setDisaster(disaster);
        entity.setDisasterZone(disasterZone);
        entity.setSourceRoadNode(sourceRoadNode);

        EvacuationRequest saved = evacuationRequestRepository.save(entity);

        log.info("Evacuation request {} created: {} people from node {}, priority {}",
                saved.getEvacuationRequestId(), saved.getNumberOfPeople(),
                sourceRoadNode.getNodeId(), saved.getPriority());

        return evacuationRequestMapper.toResponse(saved);
    }

    /**
     * Lists requests by status, most useful for {@link EvacuationStatus#PENDING} — the queue a
     * dispatch pass has not yet consumed.
     *
     * @param status the status to filter by
     * @return matching requests, response-mapped
     */
    @Transactional(readOnly = true)
    public List<EvacuationRequestResponse> findByStatus(EvacuationStatus status) {
        return evacuationRequestRepository.findByStatus(status).stream()
                .map(evacuationRequestMapper::toResponse)
                .toList();
    }
}
