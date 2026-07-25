package com.evacuation.engine.osm;

import com.evacuation.engine.osm.WardBoundary.Vertex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the ward boundary GeoJSON (drawn in geojson.io and checked into
 * {@code src/main/resources/ward/}) and parses it into a {@link WardBoundary}.
 *
 * <p>Kept small and testable by separating concerns: {@link #load(String)} handles resource
 * resolution and reading, while {@link #parse(String)} does pure string→polygon parsing so it can
 * be unit-tested without the filesystem. Note the coordinate flip — GeoJSON stores positions as
 * {@code [lon, lat]}, whereas {@link WardBoundary.Vertex} stores {@code (lat, lon)}.
 */
@Component
public class WardPolygonLoader {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    public WardPolygonLoader(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves and reads a ward GeoJSON resource, then parses it into a {@link WardBoundary}.
     *
     * <p>A bare {@code location} with no scheme is treated as a classpath resource.
     *
     * @param location a resource location ({@code classpath:}/{@code file:} prefix, or bare)
     * @return the parsed ward boundary
     * @throws IllegalStateException if the resource does not exist
     */
    public WardBoundary load(String location) {
        String resolved = hasScheme(location) ? location : "classpath:" + location;
        Resource resource = resourceLoader.getResource(resolved);

        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Ward polygon not found at " + resolved
                            + "; place the GeoJSON under src/main/resources/ward/");
        }

        String geoJson;
        try (InputStream in = resource.getInputStream()) {
            geoJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read ward polygon from " + resolved, ex);
        }

        return parse(geoJson);
    }

    /**
     * Parses a GeoJSON string into a {@link WardBoundary}, tolerating the common shapes: a
     * {@code FeatureCollection} (first Polygon/MultiPolygon feature), a single {@code Feature}, or a
     * bare {@code Polygon}/{@code MultiPolygon} geometry. The outer ring's {@code [lon, lat]} pairs
     * are flipped to {@link WardBoundary.Vertex} {@code (lat, lon)}.
     *
     * @param geoJson the raw GeoJSON text
     * @return the parsed ward boundary
     * @throws IllegalArgumentException if no usable polygon is found or coordinates are malformed
     */
    public WardBoundary parse(String geoJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(geoJson);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Ward polygon is not valid JSON", ex);
        }

        JsonNode geometry = locateGeometry(root);
        if (geometry == null) {
            throw new IllegalArgumentException(
                    "No Polygon or MultiPolygon geometry found in ward GeoJSON");
        }

        String type = text(geometry, "type");
        JsonNode coordinates = geometry.get("coordinates");
        if (coordinates == null || !coordinates.isArray()) {
            throw new IllegalArgumentException("Ward geometry has no coordinates array");
        }

        JsonNode outerRing = outerRingFor(type, coordinates);
        return new WardBoundary(toVertices(outerRing));
    }

    /**
     * Walks the common GeoJSON envelopes down to a Polygon/MultiPolygon geometry node, or returns
     * {@code null} if none is present.
     */
    private JsonNode locateGeometry(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }

        String type = text(root, "type");
        if (type == null) {
            return null;
        }

        return switch (type) {
            case "FeatureCollection" -> firstPolygonGeometry(root.get("features"));
            case "Feature" -> geometryIfPolygon(root.get("geometry"));
            case "Polygon", "MultiPolygon" -> root;
            default -> null;
        };
    }

    /** Returns the geometry of the first feature whose geometry is a Polygon/MultiPolygon. */
    private JsonNode firstPolygonGeometry(JsonNode features) {
        if (features == null || !features.isArray()) {
            return null;
        }
        for (JsonNode feature : features) {
            JsonNode geometry = geometryIfPolygon(feature.get("geometry"));
            if (geometry != null) {
                return geometry;
            }
        }
        return null;
    }

    /** Returns {@code geometry} if it is a Polygon/MultiPolygon, else {@code null}. */
    private JsonNode geometryIfPolygon(JsonNode geometry) {
        if (geometry == null) {
            return null;
        }
        String type = text(geometry, "type");
        return ("Polygon".equals(type) || "MultiPolygon".equals(type)) ? geometry : null;
    }

    /**
     * Extracts the outer ring: {@code coordinates[0]} for a Polygon, {@code coordinates[0][0]} for
     * the first polygon of a MultiPolygon.
     */
    private JsonNode outerRingFor(String type, JsonNode coordinates) {
        JsonNode ring;
        if ("MultiPolygon".equals(type)) {
            ring = coordinates.path(0).path(0);
        } else {
            ring = coordinates.path(0);
        }
        if (ring == null || !ring.isArray() || ring.size() < 3) {
            throw new IllegalArgumentException(
                    "Ward polygon outer ring must have at least 3 coordinate pairs");
        }
        return ring;
    }

    /** Converts an array of GeoJSON {@code [lon, lat]} pairs into {@code (lat, lon)} vertices. */
    private List<Vertex> toVertices(JsonNode ring) {
        List<Vertex> vertices = new ArrayList<>(ring.size());
        for (JsonNode pair : ring) {
            if (!pair.isArray() || pair.size() < 2
                    || !pair.get(0).isNumber() || !pair.get(1).isNumber()) {
                throw new IllegalArgumentException(
                        "Malformed coordinate pair in ward polygon; expected [lon, lat]");
            }
            double lon = pair.get(0).asDouble();
            double lat = pair.get(1).asDouble();
            vertices.add(new Vertex(lat, lon)); // flip: GeoJSON [lon,lat] -> Vertex(lat,lon)
        }
        return vertices;
    }

    private static boolean hasScheme(String location) {
        return location.startsWith("classpath:") || location.startsWith("file:");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value != null && value.isTextual()) ? value.asText() : null;
    }
}