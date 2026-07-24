package com.evacuation.engine.validation.disaster;

import com.evacuation.engine.validation.common.PuneBoundingBox;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class RawCoordinateValidator implements ConstraintValidator<ValidRawCoordinate, HasRawCoordinates> {

    @Override
    public boolean isValid(HasRawCoordinates value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        if (value.getLatitude() == null || value.getLongitude() == null) {
            return true;
        }

        double latitude = value.getLatitude();
        double longitude = value.getLongitude();

        return latitude >= PuneBoundingBox.MIN_LATITUDE
                && latitude <= PuneBoundingBox.MAX_LATITUDE
                && longitude >= PuneBoundingBox.MIN_LONGITUDE
                && longitude <= PuneBoundingBox.MAX_LONGITUDE;
    }
}
