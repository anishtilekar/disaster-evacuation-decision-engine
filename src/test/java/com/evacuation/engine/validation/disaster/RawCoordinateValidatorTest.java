package com.evacuation.engine.validation.disaster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RawCoordinateValidatorTest {

    private final RawCoordinateValidator validator = new RawCoordinateValidator();

    private record TestCoordinates(Double latitude, Double longitude) implements HasRawCoordinates {
        @Override
        public Double getLatitude() {
            return latitude;
        }

        @Override
        public Double getLongitude() {
            return longitude;
        }
    }

    @Test
    void isValid_returnsTrueForCoordinateWithinPuneBounds() {
        assertThat(validator.isValid(new TestCoordinates(18.5204, 73.8567), null)).isTrue();
    }

    @Test
    void isValid_returnsFalseForCoordinateOutsidePuneBounds() {
        assertThat(validator.isValid(new TestCoordinates(28.6139, 77.2090), null)).isFalse();
    }

    @Test
    void isValid_returnsTrueWhenLatitudeIsNull() {
        assertThat(validator.isValid(new TestCoordinates(null, 73.8567), null)).isTrue();
    }

    @Test
    void isValid_returnsTrueWhenLongitudeIsNull() {
        assertThat(validator.isValid(new TestCoordinates(18.5204, null), null)).isTrue();
    }

    @Test
    void isValid_returnsTrueWhenValueItselfIsNull() {
        assertThat(validator.isValid(null, null)).isTrue();
    }
}
