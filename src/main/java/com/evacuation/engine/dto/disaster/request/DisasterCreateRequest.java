package com.evacuation.engine.dto.disaster.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.evacuation.engine.model.enums.DisasterType;
import com.evacuation.engine.model.enums.SeverityLevel;
import com.evacuation.engine.validation.disaster.HasRawCoordinates;
import com.evacuation.engine.validation.disaster.ValidRawCoordinate;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ValidRawCoordinate
public class DisasterCreateRequest implements HasRawCoordinates {

    @NotBlank(message = "Disaster name is required")
    @Size(max = 150, message = "Disaster name cannot exceed 150 characters")
    private String disasterName;

    @NotNull(message = "Disaster type is required")
    private DisasterType disasterType;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    private String description;

    private SeverityLevel severityLevel;

    private LocalDateTime startTime;

    @NotBlank(message = "Affected region is required")
    @Size(max = 150, message = "Affected region cannot exceed 150 characters")
    private String affectedRegion;

    @NotNull(message = "Latitude is required")
    @DecimalMin(value = "-90.0", message = "Latitude must be >= -90")
    @DecimalMax(value = "90.0", message = "Latitude must be <= 90")
    private Double latitude;

    @NotNull(message = "Longitude is required")
    @DecimalMin(value = "-180.0", message = "Longitude must be >= -180")
    @DecimalMax(value = "180.0", message = "Longitude must be <= 180")
    private Double longitude;

    @NotNull(message = "Impact radius is required")
    @PositiveOrZero(message = "Impact radius cannot be negative")
    private Double impactRadius;
}