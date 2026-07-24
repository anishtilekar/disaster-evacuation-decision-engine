package com.evacuation.engine.validation.graph;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that expectedClearTime is not before blockedAt when both are present.
 */
@Target({ ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = BlockedRoadTimeRangeValidator.class)
public @interface ValidBlockedRoadTimeRange {

    String message() default "Expected clear time must not be before blocked-at time";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
