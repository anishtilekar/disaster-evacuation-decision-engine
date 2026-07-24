package com.evacuation.engine.mapper.evacuation;

import com.evacuation.engine.dto.evacuation.response.EvacuationRouteResponse;
import com.evacuation.engine.mapper.graph.RoadNodeMapper;
import com.evacuation.engine.model.entity.EvacuationRoute;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring", uses = {RoadNodeMapper.class, ShelterMapper.class})
public interface EvacuationRouteMapper {

    EvacuationRouteResponse toResponse(EvacuationRoute entity);
}
