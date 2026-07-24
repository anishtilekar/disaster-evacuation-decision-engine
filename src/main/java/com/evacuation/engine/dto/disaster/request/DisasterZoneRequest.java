package com.evacuation.engine.dto.disaster.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.evacuation.engine.model.enums.RiskLevel;
import com.evacuation.engine.validation.disaster.HasRawCoordinates;
import com.evacuation.engine.validation.disaster.ValidRawCoordinate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ValidRawCoordinate
public class DisasterZoneRequest implements HasRawCoordinates {

    @NotNull(message = "Disaster ID is required")
    private Long disasterId;

    @NotBlank(message = "Zone name is required")
    @Size(max = 150, message = "Zone name cannot exceed 150 characters")
    private String zoneName;

    private RiskLevel riskLevel;

    @NotNull(message = "Latitude is required")
    private Double latitude;

    @NotNull(message = "Longitude is required")
    private Double longitude;

    @NotNull(message = "Population is required")
    @PositiveOrZero(message = "Population cannot be negative")
    private Integer population;

    private Boolean evacuationRequired;
}