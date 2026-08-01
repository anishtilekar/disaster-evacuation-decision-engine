package com.evacuation.engine.dto.evacuation.request;

import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.evacuation.engine.model.enums.ShelterStatus;

/**
 * The admin-only PATCH input for an existing shelter: open/close it and adjust its capacity.
 * Both fields are optional — {@code null} means "leave this field unchanged" — so a caller can
 * update either one alone without first reading the shelter back.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShelterUpdateRequestDTO {

    /** {@code null} leaves the current status unchanged. */
    private ShelterStatus shelterStatus;

    /** {@code null} leaves the current capacity unchanged. */
    @Positive(message = "Capacity must be greater than zero")
    private Integer capacity;
}
