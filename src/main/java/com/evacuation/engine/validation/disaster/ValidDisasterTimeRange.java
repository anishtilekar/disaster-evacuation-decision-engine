package com.evacuation.engine.validation.disaster;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that endTime is not before startTime when both are present.
 */
@Target({ ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DisasterTimeRangeValidator.class)
public @interface ValidDisasterTimeRange {

    String message() default "Disaster end time must not be before start time";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
