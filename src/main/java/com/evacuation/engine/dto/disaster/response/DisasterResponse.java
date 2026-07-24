package com.evacuation.engine.dto.disaster.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import com.evacuation.engine.model.enums.DisasterStatus;
import com.evacuation.engine.model.enums.DisasterType;
import com.evacuation.engine.model.enums.SeverityLevel;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisasterResponse {

    private Long disasterId;

    private String disasterName;

    private DisasterType disasterType;

    private String description;

    private SeverityLevel severityLevel;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private DisasterStatus status;

    private String affectedRegion;

    private GeoCoordinateDTO location;

    private Double impactRadius;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}