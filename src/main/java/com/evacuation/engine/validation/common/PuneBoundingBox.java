package com.evacuation.engine.validation.common;

public final class PuneBoundingBox {

    public static final double MIN_LATITUDE = 18.40;
    public static final double MAX_LATITUDE = 18.65;
    public static final double MIN_LONGITUDE = 73.70;
    public static final double MAX_LONGITUDE = 73.95;

    private PuneBoundingBox() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
