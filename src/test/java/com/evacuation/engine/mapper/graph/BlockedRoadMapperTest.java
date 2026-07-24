package com.evacuation.engine.mapper.graph;

import com.evacuation.engine.dto.graph.request.BlockedRoadRequest;
import com.evacuation.engine.dto.graph.response.BlockedRoadResponse;
import com.evacuation.engine.model.entity.BlockedRoad;
import com.evacuation.engine.model.entity.Disaster;
import com.evacuation.engine.model.entity.RoadEdge;
import com.evacuation.engine.model.entity.RoadNode;
import com.evacuation.engine.model.enums.BlockageReason;
import com.evacuation.engine.model.enums.DisasterType;
import com.evacuation.engine.model.enums.NodeType;
import com.evacuation.engine.model.enums.RoadStatus;
import com.evacuation.engine.model.enums.SeverityLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlockedRoadMapperTest {

    private final BlockedRoadMapper mapper = new BlockedRoadMapperImpl();

    @Test
    void toEntity_ignoresDisasterAndRoadEdgeRelations() {
        BlockedRoadRequest request = BlockedRoadRequest.builder()
                .disasterId(1L)
                .roadEdgeId(2L)
                .blockageReason(BlockageReason.FLOODING)
                .impactSeverity(SeverityLevel.HIGH)
                .active(true)
                .remarks("Waist-deep water reported")
                .build();

        BlockedRoad entity = mapper.toEntity(request);

        assertThat(entity.getBlockageReason()).isEqualTo(BlockageReason.FLOODING);
        assertThat(entity.getImpactSeverity()).isEqualTo(SeverityLevel.HIGH);
        assertThat(entity.getRemarks()).isEqualTo("Waist-deep water reported");

        assertThat(entity.getBlockedRoadId()).isNull();
        assertThat(entity.getDisaster()).isNull();
        assertThat(entity.getRoadEdge()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
        assertThat(entity.getVersion()).isNull();
    }

    @Test
    void toResponse_flattensTwoHopRelationsFromDisasterAndRoadEdge() {
        Disaster disaster = Disaster.builder()
                .disasterId(1L)
                .disasterName("Mula River Flooding")
                .disasterType(DisasterType.FLOOD)
                .build();

        RoadNode sourceNode = RoadNode.builder().nodeId(10L).nodeName("Source Node").nodeType(NodeType.INTERSECTION).build();
        RoadNode destinationNode = RoadNode.builder().nodeId(11L).nodeName("Destination Node").nodeType(NodeType.INTERSECTION).build();

        RoadEdge roadEdge = RoadEdge.builder()
                .edgeId(2L)
                .roadName("Sinhagad Road")
                .sourceNode(sourceNode)
                .destinationNode(destinationNode)
                .build();

        BlockedRoad entity = BlockedRoad.builder()
                .blockedRoadId(100L)
                .disaster(disaster)
                .roadEdge(roadEdge)
                .blockageReason(BlockageReason.FLOODING)
                .impactSeverity(SeverityLevel.CRITICAL)
                .active(true)
                .build();

        BlockedRoadResponse response = mapper.toResponse(entity);

        assertThat(response.getBlockedRoadId()).isEqualTo(100L);
        assertThat(response.getDisasterId()).isEqualTo(1L);
        assertThat(response.getDisasterName()).isEqualTo("Mula River Flooding");
        assertThat(response.getRoadEdgeId()).isEqualTo(2L);
        assertThat(response.getRoadEdgeName()).isEqualTo("Sinhagad Road");
        assertThat(response.getSourceNodeId()).isEqualTo(10L);
        assertThat(response.getDestinationNodeId()).isEqualTo(11L);
    }

    @Test
    void toResponse_twoHopFieldsAreNullWhenIntermediateRelationIsNull() {
        RoadEdge roadEdgeWithoutNodes = RoadEdge.builder()
                .edgeId(3L)
                .roadName("Unlinked Road")
                .sourceNode(null)
                .destinationNode(null)
                .build();

        BlockedRoad entity = BlockedRoad.builder()
                .blockedRoadId(101L)
                .disaster(null)
                .roadEdge(roadEdgeWithoutNodes)
                .blockageReason(BlockageReason.DEBRIS)
                .impactSeverity(SeverityLevel.MEDIUM)
                .active(true)
                .build();

        BlockedRoadResponse response = mapper.toResponse(entity);

        assertThat(response.getDisasterId()).isNull();
        assertThat(response.getDisasterName()).isNull();
        assertThat(response.getRoadEdgeId()).isEqualTo(3L);
        assertThat(response.getSourceNodeId()).isNull();
        assertThat(response.getDestinationNodeId()).isNull();
    }

    @Test
    void toResponse_roadEdgeFieldsAreNullWhenRoadEdgeItselfIsNull() {
        BlockedRoad entity = BlockedRoad.builder()
                .blockedRoadId(102L)
                .disaster(null)
                .roadEdge(null)
                .blockageReason(BlockageReason.OTHER)
                .impactSeverity(SeverityLevel.LOW)
                .active(false)
                .build();

        BlockedRoadResponse response = mapper.toResponse(entity);

        assertThat(response.getRoadEdgeId()).isNull();
        assertThat(response.getRoadEdgeName()).isNull();
        assertThat(response.getSourceNodeId()).isNull();
        assertThat(response.getDestinationNodeId()).isNull();
    }
}
