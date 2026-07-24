package com.evacuation.engine.mapper.common;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface GeoCoordinateMapper {

    @Named("toGeoCoordinateDTO")
    static GeoCoordinateDTO toGeoCoordinateDTO(Double latitude, Double longitude) {
        if (latitude == null && longitude == null) {
            return null;
        }
        return new GeoCoordinateDTO(latitude, longitude);
    }

    static Double latitudeFromCoordinate(GeoCoordinateDTO coordinate) {
        if (coordinate == null) {
            return null;
        }
        return coordinate.latitude();
    }

    static Double longitudeFromCoordinate(GeoCoordinateDTO coordinate) {
        if (coordinate == null) {
            return null;
        }
        return coordinate.longitude();
    }
}
