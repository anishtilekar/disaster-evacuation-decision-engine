package com.evacuation.engine.dto.common;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import com.evacuation.engine.validation.common.ValidCoordinate;
/**
 * Reusable geographic coordinate value object (WGS84).
 * Shared across Disaster, RoadNode, Shelter, and future map API integrations.
 */
@ValidCoordinate
public record GeoCoordinateDTO(
        @NotNull(message = "Latitude is required")
        @DecimalMin(value = "-90.0", message = "Latitude must be >= -90")
        @DecimalMax(value = "90.0", message = "Latitude must be <= 90")
        Double latitude,
        @NotNull(message = "Longitude is required")
        @DecimalMin(value = "-180.0", message = "Longitude must be >= -180")
        @DecimalMax(value = "180.0", message = "Longitude must be <= 180")
        Double longitude
) {
}