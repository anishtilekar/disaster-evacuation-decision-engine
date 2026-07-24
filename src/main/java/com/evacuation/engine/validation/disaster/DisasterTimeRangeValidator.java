package com.evacuation.engine.validation.disaster;

import com.evacuation.engine.dto.disaster.request.DisasterUpdateRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DisasterTimeRangeValidator implements ConstraintValidator<ValidDisasterTimeRange, DisasterUpdateRequest> {

    @Override
    public boolean isValid(DisasterUpdateRequest value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        if (value.getStartTime() == null || value.getEndTime() == null) {
            return true;
        }

        return !value.getEndTime().isBefore(value.getStartTime());
    }
}
