package com.evacuation.engine.validation.disaster;

/**
 * Shared contract for DTOs with raw lat/lng fields, so RawCoordinateValidator can validate them without reflection.
 */
public interface HasRawCoordinates {

    Double getLatitude();

    Double getLongitude();
}
