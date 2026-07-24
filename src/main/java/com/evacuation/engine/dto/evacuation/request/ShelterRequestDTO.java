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

import com.evacuation.engine.dto.common.GeoCoordinateDTO;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShelterRequestDTO {

    @NotBlank(message = "Shelter name is required")
    @Size(max = 150, message = "Shelter name cannot exceed 150 characters")
    private String shelterName;

    @NotBlank(message = "Location description is required")
    @Size(max = 250, message = "Location cannot exceed 250 characters")
    private String location;

    @NotNull(message = "Coordinate is required")
    private GeoCoordinateDTO coordinate;

    @NotNull(message = "Capacity is required")
    @Positive(message = "Capacity must be greater than zero")
    private Integer capacity;

    private Boolean medicalFacility;

    private Boolean foodSupply;

    private Boolean waterSupply;

    @NotBlank(message = "Contact number is required")
    @Pattern(regexp = "^[+]?[0-9]{10,15}$", message = "Contact number must be a valid phone number")
    private String contactNumber;
}