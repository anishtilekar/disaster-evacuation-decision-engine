package com.evacuation.engine.dto.evacuation.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.evacuation.engine.dto.graph.response.RoadNodeResponse;
import com.evacuation.engine.model.enums.RouteStatus;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvacuationRouteResponse {

    private Long routeId;

    private String routeName;

    private RoadNodeResponse sourceNode;

    private ShelterResponse destinationShelter;

    private Double totalDistanceKm;

    private Double estimatedTravelTimeMinutes;

    private Double safetyScore;

    private RouteStatus routeStatus;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}