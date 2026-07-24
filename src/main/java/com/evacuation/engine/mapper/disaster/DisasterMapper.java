package com.evacuation.engine.mapper.disaster;

import com.evacuation.engine.dto.disaster.request.DisasterCreateRequest;
import com.evacuation.engine.dto.disaster.request.DisasterUpdateRequest;
import com.evacuation.engine.dto.disaster.response.DisasterResponse;
import com.evacuation.engine.dto.disaster.response.DisasterSummaryDTO;
import com.evacuation.engine.mapper.common.GeoCoordinateMapper;
import com.evacuation.engine.model.entity.Disaster;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring", uses = GeoCoordinateMapper.class)
public interface DisasterMapper {

    @Mapping(target = "disasterId", ignore = true)
    @Mapping(target = "endTime", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "disasterZones", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    Disaster toEntity(DisasterCreateRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "disasterId", ignore = true)
    @Mapping(target = "disasterZones", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    void updateEntity(@MappingTarget Disaster entity, DisasterUpdateRequest request);

    @Mapping(target = "location", expression = "java(com.evacuation.engine.mapper.common.GeoCoordinateMapper.toGeoCoordinateDTO(entity.getLatitude(), entity.getLongitude()))")
    DisasterResponse toResponse(Disaster entity);

    DisasterSummaryDTO toSummary(Disaster entity);
}
