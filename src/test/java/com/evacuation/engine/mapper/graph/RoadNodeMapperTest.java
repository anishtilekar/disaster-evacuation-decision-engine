package com.evacuation.engine.mapper.graph;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import com.evacuation.engine.dto.graph.request.RoadNodeRequest;
import com.evacuation.engine.dto.graph.response.RoadNodeResponse;
import com.evacuation.engine.model.entity.RoadNode;
import com.evacuation.engine.model.enums.NodeType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RoadNodeMapperTest {

    private final RoadNodeMapper mapper = new RoadNodeMapperImpl();

    @Test
    void toResponse_mapsFieldsAndBuildsCoordinateFromLatLng() {
        RoadNode entity = RoadNode.builder()
                .nodeId(1L)
                .nodeName("Shivajinagar Junction")
                .nodeType(NodeType.INTERSECTION)
                .latitude(18.5304)
                .longitude(73.8567)
                .active(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 1, 2, 10, 0))
                .build();

        RoadNodeResponse response = mapper.toResponse(entity);

        assertThat(response.getNodeId()).isEqualTo(1L);
        assertThat(response.getNodeName()).isEqualTo("Shivajinagar Junction");
        assertThat(response.getNodeType()).isEqualTo(NodeType.INTERSECTION);
        assertThat(response.getActive()).isTrue();
        assertThat(response.getCoordinate()).isNotNull();
        assertThat(response.getCoordinate().latitude()).isEqualTo(18.5304);
        assertThat(response.getCoordinate().longitude()).isEqualTo(73.8567);
        assertThat(response.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 10, 0));
        assertThat(response.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 2, 10, 0));
    }

    @Test
    void toResponse_returnsNullWhenEntityNull() {
        assertThat(mapper.toResponse(null)).isNull();
    }

    @Test
    void toEntity_splitsCoordinateIntoLatLngAndIgnoresServerManagedFields() {
        RoadNodeRequest request = RoadNodeRequest.builder()
                .nodeName("Pune Station Gate")
                .nodeType(NodeType.EMERGENCY_ENTRY_POINT)
                .coordinate(new GeoCoordinateDTO(18.5289, 73.8744))
                .active(true)
                .build();

        RoadNode entity = mapper.toEntity(request);

        assertThat(entity.getNodeName()).isEqualTo("Pune Station Gate");
        assertThat(entity.getNodeType()).isEqualTo(NodeType.EMERGENCY_ENTRY_POINT);
        assertThat(entity.getLatitude()).isEqualTo(18.5289);
        assertThat(entity.getLongitude()).isEqualTo(73.8744);
        assertThat(entity.getActive()).isTrue();

        assertThat(entity.getNodeId()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
        assertThat(entity.getVersion()).isNull();
    }
}
