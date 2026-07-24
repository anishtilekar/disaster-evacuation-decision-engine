package com.evacuation.engine.mapper.disaster;

import com.evacuation.engine.dto.disaster.request.DisasterZoneRequest;
import com.evacuation.engine.dto.disaster.response.DisasterZoneResponse;
import com.evacuation.engine.mapper.common.GeoCoordinateMapper;
import com.evacuation.engine.model.entity.DisasterZone;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = GeoCoordinateMapper.class)
public interface DisasterZoneMapper {

    @Mapping(target = "zoneId", ignore = true)
    @Mapping(target = "disaster", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    DisasterZone toEntity(DisasterZoneRequest request);

    @Mapping(target = "location", expression = "java(com.evacuation.engine.mapper.common.GeoCoordinateMapper.toGeoCoordinateDTO(entity.getLatitude(), entity.getLongitude()))")
    @Mapping(target = "disasterId", source = "disaster.disasterId")
    @Mapping(target = "disasterName", source = "disaster.disasterName")
    DisasterZoneResponse toResponse(DisasterZone entity);
}
