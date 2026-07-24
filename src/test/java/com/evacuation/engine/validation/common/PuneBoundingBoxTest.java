package com.evacuation.engine.validation.common;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PuneBoundingBoxTest {

    @Test
    void boundsAreConsistentAndNonInverted() {
        assertThat(PuneBoundingBox.MIN_LATITUDE).isLessThan(PuneBoundingBox.MAX_LATITUDE);
        assertThat(PuneBoundingBox.MIN_LONGITUDE).isLessThan(PuneBoundingBox.MAX_LONGITUDE);
    }

    @Test
    void constructorThrowsUnsupportedOperationException() throws NoSuchMethodException {
        Constructor<PuneBoundingBox> constructor = PuneBoundingBox.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
