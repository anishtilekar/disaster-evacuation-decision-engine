package com.evacuation.engine.validation.common;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a GeoCoordinateDTO's latitude/longitude fall within the Pune pilot area's approximate bounding box.
 */
@Target({ ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CoordinateValidator.class)
public @interface ValidCoordinate {

    String message() default "Coordinate must be within the Pune pilot area";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
