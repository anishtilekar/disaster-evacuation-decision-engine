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
    // TODO: EvacuationRequestDTO carries no source-node field even though the entity requires sourceRoadNode (@NotNull) — this is a DTO/entity contract gap to resolve at the Service phase, not fixed here.
    @Mapping(target = "sourceRoadNode", ignore = true)
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
