package com.evacuation.engine.mapper.graph;

import com.evacuation.engine.dto.graph.request.RoadNodeRequest;
import com.evacuation.engine.dto.graph.response.RoadNodeResponse;
import com.evacuation.engine.mapper.common.GeoCoordinateMapper;
import com.evacuation.engine.model.entity.RoadNode;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = GeoCoordinateMapper.class)
public interface RoadNodeMapper {

    @Mapping(target = "coordinate", expression = "java(com.evacuation.engine.mapper.common.GeoCoordinateMapper.toGeoCoordinateDTO(entity.getLatitude(), entity.getLongitude()))")
    RoadNodeResponse toResponse(RoadNode entity);

    @Mapping(target = "nodeId", ignore = true)
    @Mapping(target = "latitude", expression = "java(com.evacuation.engine.mapper.common.GeoCoordinateMapper.latitudeFromCoordinate(request.getCoordinate()))")
    @Mapping(target = "longitude", expression = "java(com.evacuation.engine.mapper.common.GeoCoordinateMapper.longitudeFromCoordinate(request.getCoordinate()))")
    @Mapping(target = "outgoingEdges", ignore = true)
    @Mapping(target = "incomingEdges", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    RoadNode toEntity(RoadNodeRequest request);
}
