package com.evacuation.engine.osm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Root of an Overpass API JSON response.
 *
 * <p>Carries only the {@link #elements} array the import needs; surrounding Overpass metadata
 * ({@code version}, {@code osm3s}, etc.) is ignored so parsing stays tolerant of fields we don't
 * consume.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OsmResponse {

    /** The nodes and ways returned for the queried bounding box. */
    public List<OsmElement> elements;
}