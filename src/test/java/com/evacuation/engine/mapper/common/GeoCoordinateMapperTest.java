package com.evacuation.engine.mapper.common;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoCoordinateMapperTest {

    @Test
    void toGeoCoordinateDTO_buildsDtoFromLatLng() {
        GeoCoordinateDTO result = GeoCoordinateMapper.toGeoCoordinateDTO(18.5204, 73.8567);

        assertThat(result).isNotNull();
        assertThat(result.latitude()).isEqualTo(18.5204);
        assertThat(result.longitude()).isEqualTo(73.8567);
    }

    @Test
    void toGeoCoordinateDTO_returnsNullWhenBothInputsNull() {
        assertThat(GeoCoordinateMapper.toGeoCoordinateDTO(null, null)).isNull();
    }

    @Test
    void toGeoCoordinateDTO_buildsDtoEvenWithOnePartialNull() {
        GeoCoordinateDTO result = GeoCoordinateMapper.toGeoCoordinateDTO(18.5204, null);

        assertThat(result).isNotNull();
        assertThat(result.latitude()).isEqualTo(18.5204);
        assertThat(result.longitude()).isNull();
    }

    @Test
    void latitudeFromCoordinate_extractsLatitude() {
        GeoCoordinateDTO coordinate = new GeoCoordinateDTO(18.5204, 73.8567);

        assertThat(GeoCoordinateMapper.latitudeFromCoordinate(coordinate)).isEqualTo(18.5204);
    }

    @Test
    void latitudeFromCoordinate_returnsNullWhenCoordinateNull() {
        assertThat(GeoCoordinateMapper.latitudeFromCoordinate(null)).isNull();
    }

    @Test
    void longitudeFromCoordinate_extractsLongitude() {
        GeoCoordinateDTO coordinate = new GeoCoordinateDTO(18.5204, 73.8567);

        assertThat(GeoCoordinateMapper.longitudeFromCoordinate(coordinate)).isEqualTo(73.8567);
    }

    @Test
    void longitudeFromCoordinate_returnsNullWhenCoordinateNull() {
        assertThat(GeoCoordinateMapper.longitudeFromCoordinate(null)).isNull();
    }
}
