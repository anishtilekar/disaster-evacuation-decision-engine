package com.evacuation.engine.validation.disaster;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a DTO's raw latitude/longitude fields fall within the Pune pilot area's approximate bounding box, for DTOs that don't nest a GeoCoordinateDTO.
 */
@Target({ ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = RawCoordinateValidator.class)
public @interface ValidRawCoordinate {

    String message() default "Coordinate must be within the Pune pilot area";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
