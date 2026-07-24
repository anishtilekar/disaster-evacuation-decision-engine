package com.evacuation.engine.validation.disaster;

import com.evacuation.engine.dto.disaster.request.DisasterZoneRequest;
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
 * Proves @ValidRawCoordinate is actually wired onto DisasterZoneRequest.
 */
class DisasterZoneRequestValidationTest {

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

    private DisasterZoneRequest.DisasterZoneRequestBuilder validBuilder() {
        return DisasterZoneRequest.builder()
                .disasterId(1L)
                .zoneName("Kothrud Zone B")
                .population(1000);
    }

    @Test
    void isValid_whenCoordinateWithinPuneBounds() {
        DisasterZoneRequest request = validBuilder()
                .latitude(18.5074)
                .longitude(73.8077)
                .build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void isInvalid_whenCoordinateOutsidePuneBounds() {
        DisasterZoneRequest request = validBuilder()
                .latitude(19.0760)
                .longitude(72.8777)
                .build();

        Set<ConstraintViolation<DisasterZoneRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anyMatch(v -> v.getMessage().equals("Coordinate must be within the Pune pilot area"));
    }
}
