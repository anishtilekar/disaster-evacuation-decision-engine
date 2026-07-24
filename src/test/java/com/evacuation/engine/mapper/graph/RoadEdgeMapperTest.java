package com.evacuation.engine.mapper.graph;

import com.evacuation.engine.dto.graph.request.RoadEdgeRequest;
import com.evacuation.engine.dto.graph.response.RoadEdgeResponse;
import com.evacuation.engine.model.entity.RoadEdge;
import com.evacuation.engine.model.entity.RoadNode;
import com.evacuation.engine.model.enums.NodeType;
import com.evacuation.engine.model.enums.RoadStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoadEdgeMapperTest {

    private final RoadEdgeMapper mapper = new RoadEdgeMapperImpl();

    private RoadNode buildNode(Long id, String name, double lat, double lng) {
        return RoadNode.builder()
                .nodeId(id)
                .nodeName(name)
                .nodeType(NodeType.INTERSECTION)
                .latitude(lat)
                .longitude(lng)
                .active(true)
                .build();
    }

    @Test
    void toResponse_buildsNodeSummariesForSourceAndDestination() {
        RoadNode source = buildNode(1L, "FC Road Junction", 18.5236, 73.8478);
        RoadNode destination = buildNode(2L, "JM Road Circle", 18.5184, 73.8419);

        RoadEdge entity = RoadEdge.builder()
                .edgeId(10L)
                .roadName("FC Road")
                .sourceNode(source)
                .destinationNode(destination)
                .distanceKm(2.3)
                .estimatedTravelTimeMinutes(8.5)
                .roadStatus(RoadStatus.OPEN)
                .bidirectional(true)
                .build();

        RoadEdgeResponse response = mapper.toResponse(entity);

        assertThat(response.getEdgeId()).isEqualTo(10L);
        assertThat(response.getRoadName()).isEqualTo("FC Road");
        assertThat(response.getRoadStatus()).isEqualTo(RoadStatus.OPEN);

        assertThat(response.getSourceNode().getNodeId()).isEqualTo(1L);
        assertThat(response.getSourceNode().getNodeName()).isEqualTo("FC Road Junction");
        assertThat(response.getSourceNode().getCoordinate().latitude()).isEqualTo(18.5236);
        assertThat(response.getSourceNode().getCoordinate().longitude()).isEqualTo(73.8478);

        assertThat(response.getDestinationNode().getNodeId()).isEqualTo(2L);
        assertThat(response.getDestinationNode().getNodeName()).isEqualTo("JM Road Circle");
    }

    @Test
    void toResponse_nodeSummaryIsNullWhenRelationIsNull() {
        RoadEdge entity = RoadEdge.builder()
                .edgeId(11L)
                .roadName("Unlinked Edge")
                .sourceNode(null)
                .destinationNode(null)
                .distanceKm(1.0)
                .estimatedTravelTimeMinutes(2.0)
                .roadStatus(RoadStatus.OPEN)
                .bidirectional(true)
                .build();

        RoadEdgeResponse response = mapper.toResponse(entity);

        assertThat(response.getSourceNode()).isNull();
        assertThat(response.getDestinationNode()).isNull();
    }

    @Test
    void toEntity_ignoresRelationsAndServerManagedFields() {
        RoadEdgeRequest request = RoadEdgeRequest.builder()
                .roadName("Karve Road")
                .sourceNodeId(3L)
                .destinationNodeId(4L)
                .distanceKm(1.8)
                .estimatedTravelTimeMinutes(6.0)
                .roadStatus(RoadStatus.PARTIALLY_BLOCKED)
                .bidirectional(false)
                .build();

        RoadEdge entity = mapper.toEntity(request);

        assertThat(entity.getRoadName()).isEqualTo("Karve Road");
        assertThat(entity.getDistanceKm()).isEqualTo(1.8);
        assertThat(entity.getRoadStatus()).isEqualTo(RoadStatus.PARTIALLY_BLOCKED);
        assertThat(entity.getBidirectional()).isFalse();

        assertThat(entity.getEdgeId()).isNull();
        assertThat(entity.getSourceNode()).isNull();
        assertThat(entity.getDestinationNode()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
        assertThat(entity.getVersion()).isNull();
    }
}
