package com.evacuation.engine.dto.graph.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.evacuation.engine.model.enums.RoadStatus;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoadEdgeRequest {

    @NotBlank(message = "Road name is required")
    @Size(max = 150, message = "Road name cannot exceed 150 characters")
    private String roadName;

    @NotNull(message = "Source node ID is required")
    private Long sourceNodeId;

    @NotNull(message = "Destination node ID is required")
    private Long destinationNodeId;

    @NotNull(message = "Distance is required")
    @Positive(message = "Distance must be greater than zero")
    private Double distanceKm;

    @NotNull(message = "Estimated travel time is required")
    @Positive(message = "Estimated travel time must be greater than zero")
    private Double estimatedTravelTimeMinutes;

    private RoadStatus roadStatus;

    private Boolean bidirectional;
}