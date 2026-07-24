package com.evacuation.engine.dto.graph.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import com.evacuation.engine.model.enums.RoadStatus;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoadEdgeResponse {

    private Long edgeId;

    private String roadName;

    private NodeSummaryDTO sourceNode;

    private NodeSummaryDTO destinationNode;

    private Double distanceKm;

    private Double estimatedTravelTimeMinutes;

    private RoadStatus roadStatus;

    private Boolean bidirectional;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeSummaryDTO {

        private Long nodeId;

        private String nodeName;

        private GeoCoordinateDTO coordinate;
    }
}