package com.evacuation.engine.mapper.evacuation;

import com.evacuation.engine.dto.evacuation.response.EvacuationRouteResponse;
import com.evacuation.engine.mapper.graph.RoadNodeMapperImpl;
import com.evacuation.engine.model.entity.EvacuationRoute;
import com.evacuation.engine.model.entity.RoadNode;
import com.evacuation.engine.model.entity.Shelter;
import com.evacuation.engine.model.enums.NodeType;
import com.evacuation.engine.model.enums.RouteStatus;
import com.evacuation.engine.model.enums.ShelterStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class EvacuationRouteMapperTest {

    private EvacuationRouteMapper mapper;

    @BeforeEach
    void setUp() {
        EvacuationRouteMapperImpl impl = new EvacuationRouteMapperImpl();
        ReflectionTestUtils.setField(impl, "roadNodeMapper", new RoadNodeMapperImpl());
        ReflectionTestUtils.setField(impl, "shelterMapper", new ShelterMapperImpl());
        mapper = impl;
    }

    @Test
    void toResponse_composesFullRoadNodeResponseAndFullShelterResponse() {
        RoadNode sourceNode = RoadNode.builder()
                .nodeId(1L)
                .nodeName("Deccan Gymkhana Junction")
                .nodeType(NodeType.INTERSECTION)
                .latitude(18.5158)
                .longitude(73.8412)
                .active(true)
                .build();

        Shelter shelter = Shelter.builder()
                .shelterId(9L)
                .shelterName("Balewadi Sports Complex")
                .location("Balewadi High Street")
                .latitude(18.5679)
                .longitude(73.7749)
                .capacity(500)
                .currentOccupancy(120)
                .medicalFacility(true)
                .foodSupply(true)
                .waterSupply(true)
                .contactNumber("+919800000000")
                .shelterStatus(ShelterStatus.AVAILABLE)
                .build();

        EvacuationRoute entity = EvacuationRoute.builder()
                .routeId(30L)
                .routeName("Deccan to Balewadi Route")
                .sourceNode(sourceNode)
                .destinationShelter(shelter)
                .totalDistanceKm(12.4)
                .estimatedTravelTimeMinutes(35.0)
                .safetyScore(82.5)
                .routeStatus(RouteStatus.PLANNED)
                .build();

        EvacuationRouteResponse response = mapper.toResponse(entity);

        assertThat(response.getRouteId()).isEqualTo(30L);
        assertThat(response.getRouteName()).isEqualTo("Deccan to Balewadi Route");
        assertThat(response.getRouteStatus()).isEqualTo(RouteStatus.PLANNED);

        // Full nested RoadNodeResponse, not a lightweight summary
        assertThat(response.getSourceNode()).isNotNull();
        assertThat(response.getSourceNode().getNodeId()).isEqualTo(1L);
        assertThat(response.getSourceNode().getNodeName()).isEqualTo("Deccan Gymkhana Junction");
        assertThat(response.getSourceNode().getNodeType()).isEqualTo(NodeType.INTERSECTION);
        assertThat(response.getSourceNode().getCoordinate().latitude()).isEqualTo(18.5158);

        // Full nested ShelterResponse, including computed availableCapacity
        assertThat(response.getDestinationShelter()).isNotNull();
        assertThat(response.getDestinationShelter().getShelterId()).isEqualTo(9L);
        assertThat(response.getDestinationShelter().getShelterName()).isEqualTo("Balewadi Sports Complex");
        assertThat(response.getDestinationShelter().getAvailableCapacity()).isEqualTo(380);
        assertThat(response.getDestinationShelter().getCoordinate().longitude()).isEqualTo(73.7749);
    }

    @Test
    void toResponse_returnsNullWhenEntityNull() {
        assertThat(mapper.toResponse(null)).isNull();
    }
}
