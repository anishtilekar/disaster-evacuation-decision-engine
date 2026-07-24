package com.evacuation.engine.dto.decisionengine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import com.evacuation.engine.model.enums.EvacuationPriority;
import com.evacuation.engine.model.enums.EvacuationStatus;
import com.evacuation.engine.model.enums.ShelterStatus;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvacuationPlanDTO {

    private Long evacuationRequestId;

    private Long disasterId;

    private String disasterName;

    private Long disasterZoneId;

    private String zoneName;

    private String requesterName;

    private String contactNumber;

    private Integer numberOfPeople;

    private Boolean medicalAssistanceRequired;

    private EvacuationPriority priority;

    private EvacuationStatus evacuationStatus;

    private ShelterAllocationDTO assignedShelter;

    private RouteOptimizationResultDTO assignedRoute;

    private LocalDateTime planGeneratedAt;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShelterAllocationDTO {

        private Long shelterId;

        private String shelterName;

        private GeoCoordinateDTO coordinate;

        private Integer allocatedCapacity;

        private Integer remainingCapacity;

        private ShelterStatus shelterStatus;
    }
}