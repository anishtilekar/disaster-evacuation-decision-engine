package com.evacuation.engine.validation.common;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CoordinateValidatorTest {

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
    void isValid_returnsTrueForCoordinateWithinPuneBounds() {
        GeoCoordinateDTO coordinate = new GeoCoordinateDTO(18.5204, 73.8567);

        Set<ConstraintViolation<GeoCoordinateDTO>> violations = validator.validate(coordinate);

        assertThat(violations).isEmpty();
    }

    @Test
    void isValid_returnsFalseForLatitudeOutsidePuneBounds() {
        GeoCoordinateDTO coordinate = new GeoCoordinateDTO(28.6139, 73.8567);

        Set<ConstraintViolation<GeoCoordinateDTO>> violations = validator.validate(coordinate);

        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .anyMatch(v -> v.getMessage().equals("Coordinate must be within the Pune pilot area"));
    }

    @Test
    void isValid_returnsFalseForLongitudeOutsidePuneBounds() {
        GeoCoordinateDTO coordinate = new GeoCoordinateDTO(18.5204, 77.5946);

        Set<ConstraintViolation<GeoCoordinateDTO>> violations = validator.validate(coordinate);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void isValid_directCallReturnsTrueWhenLatitudeIsNull() {
        CoordinateValidator coordinateValidator = new CoordinateValidator();
        GeoCoordinateDTO coordinateWithNullLatitude = new GeoCoordinateDTO(null, 73.8567);

        // Bypass the record's own @NotNull check by calling the validator directly,
        // isolating this validator's null-skip behavior from GeoCoordinateDTO's own constraints.
        assertThat(coordinateValidator.isValid(coordinateWithNullLatitude, null)).isTrue();
    }

    @Test
    void isValid_directCallReturnsTrueWhenValueItselfIsNull() {
        CoordinateValidator coordinateValidator = new CoordinateValidator();

        assertThat(coordinateValidator.isValid(null, null)).isTrue();
    }

    @Test
    void isValid_boundaryCoordinatesAreAccepted() {
        GeoCoordinateDTO minCorner = new GeoCoordinateDTO(PuneBoundingBox.MIN_LATITUDE, PuneBoundingBox.MIN_LONGITUDE);
        GeoCoordinateDTO maxCorner = new GeoCoordinateDTO(PuneBoundingBox.MAX_LATITUDE, PuneBoundingBox.MAX_LONGITUDE);

        assertThat(validator.validate(minCorner)).isEmpty();
        assertThat(validator.validate(maxCorner)).isEmpty();
    }
}
