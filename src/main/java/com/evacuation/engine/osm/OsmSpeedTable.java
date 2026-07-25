package com.evacuation.engine.osm;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an OSM {@code highway} type (and optional {@code maxspeed} tag) into an assumed driving
 * speed in km/h.
 *
 * <p>The import needs a speed for every edge to derive {@code estimatedTravelTimeMinutes} from its
 * length, but OSM data is patchy: many ways carry no {@code maxspeed}. So we key off the always-
 * present {@code highway} classification for a sane default, and let a real {@code maxspeed} tag
 * override it when one exists. This keeps travel-time estimates reasonable across a whole bbox
 * without requiring fully tagged data.
 */
@Component
public class OsmSpeedTable {

    /** Fallback speed (km/h) for unknown or missing highway types. */
    private static final double DEFAULT_SPEED_KMH = 30.0;

    /** One mile per hour in km/h, for converting {@code mph} maxspeed values. */
    private static final double MPH_TO_KMH = 1.609344;

    /** Leading numeric portion of a maxspeed value, e.g. the {@code 50} in {@code "50 km/h"}. */
    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d+(?:\\.\\d+)?)");

    /** Default speeds (km/h) by OSM highway classification. */
    private static final Map<String, Double> DEFAULT_SPEEDS = Map.ofEntries(
            Map.entry("motorway", 80.0),
            Map.entry("trunk", 60.0),
            Map.entry("primary", 50.0),
            Map.entry("secondary", 45.0),
            Map.entry("tertiary", 40.0),
            Map.entry("unclassified", 35.0),
            Map.entry("residential", 30.0),
            Map.entry("living_street", 15.0),
            Map.entry("service", 20.0),
            Map.entry("road", 40.0)
    );

    /**
     * Resolves the driving speed to assume for an edge, in km/h.
     *
     * <p>A usable {@code maxspeed} tag wins; otherwise the {@code highway} type's default is used
     * ({@code *_link} ramps fall back to their parent type); otherwise a sane global default.
     *
     * @param highway     the OSM {@code highway} tag value (may be {@code null})
     * @param maxspeedTag the OSM {@code maxspeed} tag value (may be {@code null})
     * @return the assumed speed in km/h, always positive
     */
    public double speedKmh(String highway, String maxspeedTag) {
        Double fromTag = parseMaxspeed(maxspeedTag);
        if (fromTag != null) {
            return fromTag;
        }

        if (highway != null) {
            String type = highway.trim().toLowerCase();
            if (type.endsWith("_link")) {
                type = type.substring(0, type.length() - "_link".length());
            }
            Double fromType = DEFAULT_SPEEDS.get(type);
            if (fromType != null) {
                return fromType;
            }
        }

        return DEFAULT_SPEED_KMH;
    }

    /**
     * Parses an OSM {@code maxspeed} value into km/h.
     *
     * <p>Handles bare numbers ({@code "50"}), km/h forms ({@code "50 km/h"}, {@code "50kmh"}), and
     * {@code mph} values ({@code "30 mph"} → converted). Non-numeric conventions like {@code "walk"},
     * {@code "none"}, or {@code "signals"} yield {@code null} so the caller falls back to a default.
     *
     * @param maxspeedTag the raw tag value
     * @return the speed in km/h, or {@code null} if the value is missing or unusable
     */
    private Double parseMaxspeed(String maxspeedTag) {
        if (maxspeedTag == null || maxspeedTag.isBlank()) {
            return null;
        }

        String value = maxspeedTag.trim().toLowerCase();
        boolean mph = value.contains("mph");

        Matcher matcher = LEADING_NUMBER.matcher(value);
        if (!matcher.find()) {
            return null;
        }

        double speed = Double.parseDouble(matcher.group(1));
        if (speed <= 0.0) {
            return null;
        }

        return mph ? speed * MPH_TO_KMH : speed;
    }
}