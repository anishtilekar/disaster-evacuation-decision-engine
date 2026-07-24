package com.evacuation.engine.validation.common;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import com.evacuation.engine.dto.evacuation.request.ShelterRequestDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves @Valid on ShelterRequestDTO.coordinate actually cascades into
 * GeoCoordinateDTO's class-level @ValidCoordinate constraint.
 */
class ShelterRequestDTOCoordinateCascadeTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private ShelterRequestDTO.ShelterRequestDTOBuilder validBuilder() {
        return ShelterRequestDTO.builder()
                .shelterName("Baner Relief Center")
                .location("Baner Road")
                .capacity(100)
                .contactNumber("+919812345678");
    }

    @Test
    void isValid_whenCoordinateWithinPuneBounds() {
        ShelterRequestDTO request = validBuilder()
                .coordinate(new GeoCoordinateDTO(18.5590, 73.7868))
                .build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void isInvalid_whenCoordinateOutsidePuneBounds() {
        ShelterRequestDTO request = validBuilder()
                .coordinate(new GeoCoordinateDTO(12.9716, 77.5946))
                .build();

        Set<ConstraintViolation<ShelterRequestDTO>> violations = validator.validate(request);

        assertThat(violations)
                .anyMatch(v -> v.getMessage().equals("Coordinate must be within the Pune pilot area"));
    }
}
