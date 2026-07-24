package com.evacuation.engine.dto.evacuation.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.evacuation.engine.model.enums.EvacuationPriority;
import com.evacuation.engine.model.enums.EvacuationStatus;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvacuationRequestResponse {

    private Long evacuationRequestId;

    private Long disasterId;

    private String disasterName;

    private Long disasterZoneId;

    private String zoneName;

    private String requesterName;

    private String contactNumber;

    private Integer numberOfPeople;

    private EvacuationPriority priority;

    private EvacuationStatus status;

    private LocalDateTime requestedAt;

    private String emergencyNotes;

    private Boolean medicalAssistanceRequired;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}