package com.evacuation.engine.dto.evacuation.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.evacuation.engine.model.enums.EvacuationPriority;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvacuationRequestDTO {

    @NotNull(message = "Disaster ID is required")
    private Long disasterId;

    @NotNull(message = "Disaster zone ID is required")
    private Long disasterZoneId;

    @NotNull(message = "Source road node ID is required")
    private Long sourceRoadNodeId;

    @NotBlank(message = "Requester name is required")
    @Size(max = 150, message = "Requester name cannot exceed 150 characters")
    private String requesterName;

    @NotBlank(message = "Contact number is required")
    @Pattern(regexp = "^[+]?[0-9]{10,15}$", message = "Contact number must be a valid phone number")
    private String contactNumber;

    @NotNull(message = "Number of people is required")
    @Positive(message = "Number of people must be greater than zero")
    private Integer numberOfPeople;

    private EvacuationPriority priority;

    @Size(max = 500, message = "Emergency notes cannot exceed 500 characters")
    private String emergencyNotes;

    private Boolean medicalAssistanceRequired;
}