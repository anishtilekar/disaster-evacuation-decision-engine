package com.evacuation.engine.validation.graph;

import com.evacuation.engine.dto.graph.request.BlockedRoadRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class BlockedRoadTimeRangeValidator implements ConstraintValidator<ValidBlockedRoadTimeRange, BlockedRoadRequest> {

    @Override
    public boolean isValid(BlockedRoadRequest value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        if (value.getBlockedAt() == null || value.getExpectedClearTime() == null) {
            return true;
        }

        return !value.getExpectedClearTime().isBefore(value.getBlockedAt());
    }
}
