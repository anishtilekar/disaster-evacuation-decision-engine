package com.evacuation.engine.dto.decisionengine;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.evacuation.engine.model.enums.EvacuationPriority;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteOptimizationRequestDTO {

    @NotNull(message = "Disaster ID is required")
    private Long disasterId;

    @NotNull(message = "Source node ID is required")
    private Long sourceNodeId;

    private Long destinationShelterId;

    @NotNull(message = "Number of people is required")
    @Positive(message = "Number of people must be greater than zero")
    private Integer numberOfPeople;

    @NotNull(message = "Priority is required")
    private EvacuationPriority priority;

    @NotNull(message = "Medical assistance required flag must be set")
    private Boolean medicalAssistanceRequired;
}