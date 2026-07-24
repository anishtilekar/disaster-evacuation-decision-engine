package com.evacuation.engine.mapper.graph;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import com.evacuation.engine.dto.graph.request.RoadEdgeRequest;
import com.evacuation.engine.dto.graph.response.RoadEdgeResponse;
import com.evacuation.engine.mapper.common.GeoCoordinateMapper;
import com.evacuation.engine.model.entity.RoadEdge;
import com.evacuation.engine.model.entity.RoadNode;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = GeoCoordinateMapper.class)
public interface RoadEdgeMapper {

    RoadEdgeResponse toResponse(RoadEdge entity);

    @Mapping(target = "edgeId", ignore = true)
    @Mapping(target = "sourceNode", ignore = true)
    @Mapping(target = "destinationNode", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    RoadEdge toEntity(RoadEdgeRequest request);

    /**
     * Builds the nested NodeSummaryDTO for sourceNode/destinationNode.
     * MapStruct auto-applies this to both fields in toResponse() since the
     * single-parameter signature (RoadNode -> NodeSummaryDTO) matches by type.
     *
     * Note: this is a plain default method on the interface itself, so it does
     * not have access to the GeoCoordinateMapper instance that Spring injects
     * into the *generated* RoadEdgeMapperImpl class (that field only exists in
     * generated code, not in source-level default methods). The coordinate
     * null-safety here intentionally mirrors GeoCoordinateMapper.toGeoCoordinateDTO
     * (null if both lat/lng are null) rather than delegating to it.
     */
    default RoadEdgeResponse.NodeSummaryDTO roadNodeToSummary(RoadNode node) {
        if (node == null) {
            return null;
        }
        Double latitude = node.getLatitude();
        Double longitude = node.getLongitude();
        GeoCoordinateDTO coordinate = (latitude == null && longitude == null)
                ? null
                : new GeoCoordinateDTO(latitude, longitude);

        return RoadEdgeResponse.NodeSummaryDTO.builder()
                .nodeId(node.getNodeId())
                .nodeName(node.getNodeName())
                .coordinate(coordinate)
                .build();
    }
}
