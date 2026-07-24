package com.evacuation.engine.validation.common;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CoordinateValidator implements ConstraintValidator<ValidCoordinate, GeoCoordinateDTO> {

    @Override
    public boolean isValid(GeoCoordinateDTO value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        if (value.latitude() == null || value.longitude() == null) {
            return true;
        }

        double latitude = value.latitude();
        double longitude = value.longitude();

        return latitude >= PuneBoundingBox.MIN_LATITUDE
                && latitude <= PuneBoundingBox.MAX_LATITUDE
                && longitude >= PuneBoundingBox.MIN_LONGITUDE
                && longitude <= PuneBoundingBox.MAX_LONGITUDE;
    }
}
