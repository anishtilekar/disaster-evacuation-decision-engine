package com.evacuation.engine.mapper.evacuation;

import com.evacuation.engine.dto.evacuation.request.ShelterRequestDTO;
import com.evacuation.engine.dto.evacuation.response.ShelterResponse;
import com.evacuation.engine.mapper.common.GeoCoordinateMapper;
import com.evacuation.engine.model.entity.Shelter;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = GeoCoordinateMapper.class)
public interface ShelterMapper {

    @Mapping(target = "coordinate", expression = "java(com.evacuation.engine.mapper.common.GeoCoordinateMapper.toGeoCoordinateDTO(entity.getLatitude(), entity.getLongitude()))")
    ShelterResponse toResponse(Shelter entity);

    @Mapping(target = "shelterId", ignore = true)
    @Mapping(target = "latitude", expression = "java(com.evacuation.engine.mapper.common.GeoCoordinateMapper.latitudeFromCoordinate(request.getCoordinate()))")
    @Mapping(target = "longitude", expression = "java(com.evacuation.engine.mapper.common.GeoCoordinateMapper.longitudeFromCoordinate(request.getCoordinate()))")
    @Mapping(target = "currentOccupancy", ignore = true)
    @Mapping(target = "shelterStatus", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    Shelter toEntity(ShelterRequestDTO request);
}
