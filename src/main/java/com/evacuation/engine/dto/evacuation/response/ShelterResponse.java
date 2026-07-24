package com.evacuation.engine.dto.evacuation.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import com.evacuation.engine.model.enums.ShelterStatus;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShelterResponse {

    private Long shelterId;

    private String shelterName;

    private String location;

    private GeoCoordinateDTO coordinate;

    private Integer capacity;

    private Integer currentOccupancy;

    private Integer availableCapacity;

    private Boolean medicalFacility;

    private Boolean foodSupply;

    private Boolean waterSupply;

    private String contactNumber;

    private ShelterStatus shelterStatus;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}