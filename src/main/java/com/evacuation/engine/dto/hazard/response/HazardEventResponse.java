package com.evacuation.engine.dto.hazard.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import com.evacuation.engine.model.enums.DisasterType;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HazardEventResponse {

    private Long hazardEventId;

    private String description;

    private DisasterType hazardType;

    private GeoCoordinateDTO origin;

    private Double initialRadiusMeters;

    private Double growthRateMetersPerMinute;

    private Double leadingRiskBufferMeters;

    private Double riskFactor;

    private LocalDateTime eventStartTime;

    private Boolean active;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}