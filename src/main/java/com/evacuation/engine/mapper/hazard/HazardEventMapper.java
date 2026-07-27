package com.evacuation.engine.mapper.hazard;

import com.evacuation.engine.dto.hazard.request.HazardEventRequest;
import com.evacuation.engine.dto.hazard.response.HazardEventResponse;
import com.evacuation.engine.mapper.common.GeoCoordinateMapper;
import com.evacuation.engine.model.entity.HazardEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = GeoCoordinateMapper.class)
public interface HazardEventMapper {

    @Mapping(target = "origin", expression = "java(com.evacuation.engine.mapper.common.GeoCoordinateMapper.toGeoCoordinateDTO(entity.getOriginLatitude(), entity.getOriginLongitude()))")
    HazardEventResponse toResponse(HazardEvent entity);

    @Mapping(target = "hazardEventId", ignore = true)
    @Mapping(target = "originLatitude", expression = "java(com.evacuation.engine.mapper.common.GeoCoordinateMapper.latitudeFromCoordinate(request.getOrigin()))")
    @Mapping(target = "originLongitude", expression = "java(com.evacuation.engine.mapper.common.GeoCoordinateMapper.longitudeFromCoordinate(request.getOrigin()))")
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    HazardEvent toEntity(HazardEventRequest request);
}