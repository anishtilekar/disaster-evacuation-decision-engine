package com.evacuation.engine.dto.decisionengine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import com.evacuation.engine.model.enums.RouteStatus;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteOptimizationResultDTO {

    private Long routeId;

    private String routeName;

    private Long sourceNodeId;

    private String sourceNodeName;

    private GeoCoordinateDTO sourceCoordinate;

    private Long destinationShelterId;

    private String destinationShelterName;

    private GeoCoordinateDTO destinationCoordinate;

    private Double totalDistanceKm;

    private Double estimatedTravelTimeMinutes;

    private Double safetyScore;

    private RouteStatus routeStatus;

    private List<RoutePathNodeDTO> path;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoutePathNodeDTO {

        private Long nodeId;

        private String nodeName;

        private GeoCoordinateDTO coordinate;

        private Integer sequenceOrder;
    }
}