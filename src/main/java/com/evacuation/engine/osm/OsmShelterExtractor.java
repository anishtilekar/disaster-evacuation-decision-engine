package com.evacuation.engine.osm;

import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.model.entity.Shelter;
import com.evacuation.engine.model.enums.ShelterStatus;
import com.evacuation.engine.osm.dto.OsmElement;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the OSM {@code amenity} elements (schools, hospitals, community centres) fetched inside the
 * ward polygon into real {@link Shelter} entities.
 *
 * <p>OSM gives us a genuine <em>name</em> and <em>location</em> for each facility, but nothing a
 * shelter model needs operationally: no capacity, occupancy, supplies, or contact number. So those
 * fields are estimated or synthesized by amenity type — capacity from a configured per-type table,
 * a deterministic pattern-valid placeholder phone derived from the OSM id — while the coordinates
 * stay real. The recognized amenity types (and their capacities) are exactly the configured
 * {@code shelterCapacityByAmenity} keys, so recognition and capacity come from one source.
 */
@Component
public class OsmShelterExtractor {

    /** Capacity assumed when an amenity type has no configured estimate. */
    private static final int DEFAULT_CAPACITY = 100;

    /** Column caps mirrored from the {@link Shelter} entity. */
    private static final int MAX_NAME_LENGTH = 150;
    private static final int MAX_LOCATION_LENGTH = 250;

    /** Decimal places used when rounding coordinates for de-duplication. */
    private static final double DEDUP_SCALE = 100_000.0; // 5 decimal places

    private final GraphEngineProperties props;

    public OsmShelterExtractor(GraphEngineProperties props) {
        this.props = props;
    }

    /**
     * Builds shelters from the amenity elements in an OSM response.
     *
     * <p>Node amenities use their own coordinates; way amenities (e.g. a school mapped as a
     * building) use the centroid of their constituent nodes. Elements outside the ward polygon are
     * skipped defensively, and a way and its constituent node mapping the same facility are
     * de-duplicated by rounded coordinate.
     *
     * @param elements the parsed OSM elements (nodes and ways)
     * @param ward     the ward boundary used to clip stray boundary-straddling results
     * @return the built shelters, in input order; never {@code null}
     */
    public List<Shelter> extract(List<OsmElement> elements, WardBoundary ward) {
        if (elements == null) {
            return new ArrayList<>();
        }

        // 1. Index nodes so a way's centroid can be resolved from its constituent coordinates.
        Map<Long, OsmElement> nodeById = new HashMap<>();
        for (OsmElement element : elements) {
            if (element.isNode()) {
                nodeById.put(element.id, element);
            }
        }

        Map<String, Integer> capacityByAmenity = props.getOsm().getShelterCapacityByAmenity();
        Set<String> recognized = new HashSet<>();
        for (String key : capacityByAmenity.keySet()) {
            recognized.add(key.toLowerCase());
        }

        List<Shelter> shelters = new ArrayList<>();
        Set<String> seenCoordinates = new LinkedHashSet<>();

        for (OsmElement element : elements) {
            String amenityTag = element.tag("amenity");
            if (amenityTag == null) {
                continue;
            }
            String amenity = amenityTag.trim().toLowerCase();
            if (!recognized.contains(amenity) || (!element.isNode() && !element.isWay())) {
                continue;
            }

            double[] latLon = resolveLatLon(element, nodeById);
            if (latLon == null) {
                continue; // way with no resolvable constituent nodes
            }
            double lat = latLon[0];
            double lon = latLon[1];

            // Defensive: Overpass can include ways whose centroid drifts just outside the polygon.
            if (!ward.contains(lat, lon)) {
                continue;
            }

            // 3. De-duplicate by rounded coordinate (a way and its node can both match).
            if (!seenCoordinates.add(coordinateKey(lat, lon))) {
                continue;
            }

            shelters.add(Shelter.builder()
                    .shelterName(buildShelterName(element, amenity))
                    .location(buildLocation(element))
                    .latitude(lat)
                    .longitude(lon)
                    .capacity(capacityByAmenity.getOrDefault(amenity, DEFAULT_CAPACITY))
                    .currentOccupancy(0)
                    .medicalFacility("hospital".equalsIgnoreCase(amenity))
                    // v1 simplification: assume a functioning public facility has basic supplies.
                    .foodSupply(true)
                    .waterSupply(true)
                    .contactNumber(syntheticContact(element.id))
                    .shelterStatus(ShelterStatus.AVAILABLE)
                    .build());
        }

        return shelters;
    }

    /**
     * Resolves an amenity's coordinate: a node's own position, or the centroid of a way's resolvable
     * constituent nodes. Returns {@code null} for a way with no resolvable nodes.
     */
    private double[] resolveLatLon(OsmElement element, Map<Long, OsmElement> nodeById) {
        if (element.isNode()) {
            return new double[]{element.lat, element.lon};
        }

        if (element.nodes == null || element.nodes.isEmpty()) {
            return null;
        }

        double sumLat = 0.0;
        double sumLon = 0.0;
        int count = 0;
        for (Long id : element.nodes) {
            OsmElement node = nodeById.get(id);
            if (node == null) {
                continue; // referenced node missing from the response; skip
            }
            sumLat += node.lat;
            sumLon += node.lon;
            count++;
        }

        return count == 0 ? null : new double[]{sumLat / count, sumLon / count};
    }

    /** Real OSM {@code name} when present, else a title-cased amenity label with " Shelter". */
    private String buildShelterName(OsmElement element, String amenity) {
        String name = element.tag("name");
        String shelterName = (name != null && !name.isBlank())
                ? name
                : titleCase(amenity) + " Shelter";
        return truncate(shelterName, MAX_NAME_LENGTH);
    }

    /** Short free-text location: the street address if tagged, else the ward label. */
    private String buildLocation(OsmElement element) {
        String street = element.tag("addr:street");
        String location = (street != null && !street.isBlank())
                ? street
                : props.getOsm().getWardName() + " ward, Pune";
        return truncate(location, MAX_LOCATION_LENGTH);
    }

    /**
     * Deterministic, pattern-valid placeholder phone number derived from the OSM id, so every
     * shelter satisfies the {@code ^[+]?[0-9]{10,15}$} constraint without real contact data.
     */
    private static String syntheticContact(long osmId) {
        long digits = Math.floorMod(osmId, 10_000_000_000L); // stable 10-digit tail
        return "+91" + String.format("%010d", digits);
    }

    /** Rounds to 5 decimal places (locale-independent) for coordinate-based de-duplication. */
    private static String coordinateKey(double lat, double lon) {
        return Math.round(lat * DEDUP_SCALE) + "," + Math.round(lon * DEDUP_SCALE);
    }

    private static String titleCase(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}