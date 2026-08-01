package com.evacuation.engine.mapper.evacuation;

import com.evacuation.engine.dto.evacuation.request.EvacuationRequestDTO;
import com.evacuation.engine.dto.evacuation.response.EvacuationRequestResponse;
import com.evacuation.engine.model.entity.EvacuationRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface EvacuationRequestMapper {

    @Mapping(target = "evacuationRequestId", ignore = true)
    @Mapping(target = "disaster", ignore = true)
    @Mapping(target = "disasterZone", ignore = true)
    // Resolved and set by EvacuationRequestService from the DTO's sourceRoadNodeId/
    // destinationRoadNodeId, the same resolve-by-id-then-attach pattern GraphAdminService uses for
    // its own associations.
    @Mapping(target = "sourceRoadNode", ignore = true)
    @Mapping(target = "destinationRoadNode", ignore = true)
    // Stamped by EvacuationRequestService from the authenticated principal, never client input.
    @Mapping(target = "requestedBy", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "requestedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    EvacuationRequest toEntity(EvacuationRequestDTO request);

    @Mapping(target = "disasterId", source = "disaster.disasterId")
    @Mapping(target = "disasterName", source = "disaster.disasterName")
    @Mapping(target = "disasterZoneId", source = "disasterZone.zoneId")
    @Mapping(target = "zoneName", source = "disasterZone.zoneName")
    EvacuationRequestResponse toResponse(EvacuationRequest entity);
}
