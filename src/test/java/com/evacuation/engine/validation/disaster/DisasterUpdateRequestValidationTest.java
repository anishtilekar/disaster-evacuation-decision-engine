package com.evacuation.engine.validation.disaster;

import com.evacuation.engine.dto.disaster.request.DisasterUpdateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves both @ValidRawCoordinate and @ValidDisasterTimeRange are actually
 * wired onto DisasterUpdateRequest, since this DTO carries both constraints
 * stacked on the same class declaration.
 */
class DisasterUpdateRequestValidationTest {

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

    @Test
    void isValid_whenAllOptionalFieldsAreNull() {
        DisasterUpdateRequest request = DisasterUpdateRequest.builder().build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void isValid_whenCoordinateWithinPuneBoundsAndTimeRangeCorrect() {
        DisasterUpdateRequest request = DisasterUpdateRequest.builder()
                .latitude(18.5204)
                .longitude(73.8567)
                .startTime(LocalDateTime.of(2026, 7, 24, 6, 0))
                .endTime(LocalDateTime.of(2026, 7, 24, 10, 0))
                .build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void isInvalid_whenCoordinateOutsidePuneBounds() {
        DisasterUpdateRequest request = DisasterUpdateRequest.builder()
                .latitude(28.6139)
                .longitude(77.2090)
                .build();

        Set<ConstraintViolation<DisasterUpdateRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anyMatch(v -> v.getMessage().equals("Coordinate must be within the Pune pilot area"));
    }

    @Test
    void isInvalid_whenEndTimeBeforeStartTime() {
        DisasterUpdateRequest request = DisasterUpdateRequest.builder()
                .startTime(LocalDateTime.of(2026, 7, 24, 10, 0))
                .endTime(LocalDateTime.of(2026, 7, 24, 6, 0))
                .build();

        Set<ConstraintViolation<DisasterUpdateRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anyMatch(v -> v.getMessage().equals("Disaster end time must not be before start time"));
    }

    @Test
    void isValid_whenEndTimeEqualsStartTime() {
        LocalDateTime sameInstant = LocalDateTime.of(2026, 7, 24, 6, 0);
        DisasterUpdateRequest request = DisasterUpdateRequest.builder()
                .startTime(sameInstant)
                .endTime(sameInstant)
                .build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void isValid_whenOnlyStartTimeIsSet() {
        DisasterUpdateRequest request = DisasterUpdateRequest.builder()
                .startTime(LocalDateTime.of(2026, 7, 24, 6, 0))
                .build();

        assertThat(validator.validate(request)).isEmpty();
    }
}
