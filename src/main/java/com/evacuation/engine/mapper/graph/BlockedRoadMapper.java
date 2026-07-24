package com.evacuation.engine.mapper.graph;

import com.evacuation.engine.dto.graph.request.BlockedRoadRequest;
import com.evacuation.engine.dto.graph.response.BlockedRoadResponse;
import com.evacuation.engine.model.entity.BlockedRoad;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BlockedRoadMapper {

    @Mapping(target = "blockedRoadId", ignore = true)
    @Mapping(target = "disaster", ignore = true)
    @Mapping(target = "roadEdge", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    BlockedRoad toEntity(BlockedRoadRequest request);

    @Mapping(target = "disasterId", source = "disaster.disasterId")
    @Mapping(target = "disasterName", source = "disaster.disasterName")
    @Mapping(target = "roadEdgeId", source = "roadEdge.edgeId")
    @Mapping(target = "roadEdgeName", source = "roadEdge.roadName")
    @Mapping(target = "sourceNodeId", source = "roadEdge.sourceNode.nodeId")
    @Mapping(target = "destinationNodeId", source = "roadEdge.destinationNode.nodeId")
    BlockedRoadResponse toResponse(BlockedRoad entity);
}
