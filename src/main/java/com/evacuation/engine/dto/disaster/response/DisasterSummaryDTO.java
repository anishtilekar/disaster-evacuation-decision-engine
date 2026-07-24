package com.evacuation.engine.dto.disaster.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.evacuation.engine.model.enums.DisasterStatus;
import com.evacuation.engine.model.enums.DisasterType;
import com.evacuation.engine.model.enums.SeverityLevel;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisasterSummaryDTO {

    private Long disasterId;

    private String disasterName;

    private DisasterType disasterType;

    private SeverityLevel severityLevel;

    private DisasterStatus status;

    private String affectedRegion;
}