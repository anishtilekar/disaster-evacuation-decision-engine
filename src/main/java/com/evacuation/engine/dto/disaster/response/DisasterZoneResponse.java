package com.evacuation.engine.dto.disaster.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import com.evacuation.engine.model.enums.RiskLevel;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisasterZoneResponse {

    private Long zoneId;

    private String zoneName;

    private RiskLevel riskLevel;

    private GeoCoordinateDTO location;

    private Integer population;

    private Boolean evacuationRequired;

    private Long disasterId;

    private String disasterName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}