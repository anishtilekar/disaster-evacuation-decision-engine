package com.evacuation.engine.dto.hazard.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import com.evacuation.engine.model.enums.DisasterType;

import java.time.LocalDateTime;

/**
 * Operator input for a predicted, time-varying hazard — the "polygon + growth rate" event the
 * hazard timeline compiles into future-dated onset buckets.
 *
 * <p>The shape on the wire is an expanding <strong>circle</strong>: an origin, the radius it already
 * covers, and the rate its front advances. That is the entity's own simplification of a polygon,
 * carried through here unchanged rather than accepting a polygon the engine could not buffer.
 *
 * <p>{@code eventStartTime} is deliberately unconstrained in either direction. A hazard that has been
 * spreading for ten minutes and a forecast that begins spreading in twenty are both legitimate, and
 * the second is the more valuable one — planning around a front before it arrives is the entire point
 * of a timeline. A past-or-present constraint here would reject exactly the input worth having.
 *
 * <p>Validation of the origin cascades: {@code @Valid} carries into {@link GeoCoordinateDTO}'s own
 * {@code @ValidCoordinate} constraint, so a hazard cannot be posted outside the Pune pilot bounding
 * box.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HazardEventRequest {

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    private String description;

    @NotNull(message = "Hazard type is required")
    private DisasterType hazardType;

    @Valid
    @NotNull(message = "Origin coordinate is required")
    private GeoCoordinateDTO origin;

    @NotNull(message = "Initial radius is required")
    @PositiveOrZero(message = "Initial radius cannot be negative")
    private Double initialRadiusMeters;

    @NotNull(message = "Growth rate is required")
    @Positive(message = "Growth rate must be greater than zero")
    private Double growthRateMetersPerMinute;

    @PositiveOrZero(message = "Leading risk buffer cannot be negative")
    private Double leadingRiskBufferMeters;

    @DecimalMin(value = "0.0", message = "Risk factor must be >= 0")
    @DecimalMax(value = "1.0", message = "Risk factor must be <= 1")
    private Double riskFactor;

    @NotNull(message = "Event start time is required")
    private LocalDateTime eventStartTime;
}