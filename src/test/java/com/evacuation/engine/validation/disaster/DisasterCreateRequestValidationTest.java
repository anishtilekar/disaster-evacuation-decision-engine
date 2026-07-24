package com.evacuation.engine.validation.disaster;

import com.evacuation.engine.dto.disaster.request.DisasterCreateRequest;
import com.evacuation.engine.model.enums.DisasterType;
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
 * Proves @ValidRawCoordinate is actually wired onto DisasterCreateRequest
 * (class-level annotation + implements HasRawCoordinates), not just that
 * RawCoordinateValidator's isolated logic is correct.
 */
class DisasterCreateRequestValidationTest {

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

    private DisasterCreateRequest.DisasterCreateRequestBuilder validBuilder() {
        return DisasterCreateRequest.builder()
                .disasterName("Mula River Flooding")
                .disasterType(DisasterType.FLOOD)
                .affectedRegion("Kothrud")
                .impactRadius(2.0);
    }

    @Test
    void isValid_whenCoordinateWithinPuneBounds() {
        DisasterCreateRequest request = validBuilder()
                .latitude(18.5204)
                .longitude(73.8567)
                .build();

        Set<ConstraintViolation<DisasterCreateRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void isInvalid_whenCoordinateOutsidePuneBounds() {
        DisasterCreateRequest request = validBuilder()
                .latitude(28.6139)
                .longitude(77.2090)
                .build();

        Set<ConstraintViolation<DisasterCreateRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anyMatch(v -> v.getMessage().equals("Coordinate must be within the Pune pilot area"));
    }
}
