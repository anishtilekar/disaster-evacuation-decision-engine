package com.evacuation.engine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Externalised configuration for the graph engine, bound from the {@code graph.*} keys in
 * {@code application.properties}.
 *
 * <p>Holding the ward, Overpass settings, shelter parameters, and seeding behaviour here — rather
 * than hardcoding them — follows the architecture doc's "externalised configuration" principle and
 * lets the pilot ward be retargeted (a different Pune ward, different shelter rules) without a code
 * change.
 */
@Component
@ConfigurationProperties(prefix = "graph")
@Getter
@Setter
public class GraphEngineProperties {

    /**
     * Whether to import OSM and seed shelters/disaster at startup when the graph is empty. Enabled
     * by default so a fresh {@code create-drop} database comes up ready to route; set {@code false}
     * in tests so they stay hermetic.
     */
    private boolean seedOnStartup = true;

    /**
     * Assumed maximum network speed, in km/h. Used as the divisor in the A* time heuristic — so it
     * must exceed every real edge speed for the heuristic to stay admissible — and as a fallback
     * speed for edges with no usable {@code maxspeed}.
     */
    private double networkMaxSpeedKmh = 90.0;

    /**
     * OSM/Overpass import and shelter settings. Populated in place by JavaBean binding via its
     * getter, so the field is intentionally {@code final}.
     */
    private final Osm osm = new Osm();

    /**
     * Space-time model settings. Populated in place by JavaBean binding via its getter, so the field
     * is intentionally {@code final}.
     */
    private final Time time = new Time();

    /**
     * Dispatch cost weights. Populated in place by JavaBean binding via its getter, so the field is
     * intentionally {@code final}.
     */
    private final Dispatch dispatch = new Dispatch();

    /**
     * OpenStreetMap import, ward, and shelter settings, bound from {@code graph.osm.*}.
     */
    @Getter
    @Setter
    public static class Osm {

        /** Human-readable label for the target ward. */
        private String wardName = "Shivajinagar-Ghole Road";

        /**
         * Classpath-relative location of the ward boundary GeoJSON, loaded by
         * {@code WardPolygonLoader} and used to scope the Overpass query and clip imported data.
         */
        private String wardPolygonFile = "ward/shivajinagar-ghole-road.geojson";

        /** Overpass API endpoint queried for the ward's routable ways and nodes. */
        private String overpassUrl = "https://overpass-api.de/api/interpreter";

        /**
         * Directory (relative to the working dir) where raw Overpass responses are cached, so
         * repeated boots stay deterministic and offline after the first fetch.
         */
        private String cacheDir = "osm-cache";

        /** Overpass server-side query timeout, in seconds. */
        private int timeoutSeconds = 90;

        /**
         * OSM {@code amenity} values treated as candidate shelters. Mutable so property overrides
         * bind cleanly.
         */
        private List<String> shelterAmenities =
                new ArrayList<>(List.of("school", "hospital", "community_centre"));

        /**
         * Estimated capacity per amenity type — OSM carries none, so we assume by kind. Mutable so
         * property overrides bind cleanly.
         */
        private Map<String, Integer> shelterCapacityByAmenity = new LinkedHashMap<>(Map.of(
                "hospital", 300,
                "school", 500,
                "community_centre", 150
        ));
    }

    /**
     * Time-model levers for the STRIDE space-time engine, bound from {@code graph.time.*}.
     *
     * <p>These are the operator's knobs on the discretised time model: how wide each time bucket is,
     * how far ahead the rolling plan looks, and how much lethality-free margin an arc must retain
     * after a platoon clears it. Externalising them lets an operator trade resolution against horizon
     * and tune the safety margin per deployment without a code change.
     */
    @Getter
    @Setter
    public static class Time {

        /** Bucket width, in seconds — the granularity of the discretised time axis. */
        private int deltaSeconds = 15;

        /** Rolling planning horizon, in buckets (160 × 15s = 40 minutes). */
        private int horizonBuckets = 160;

        /**
         * Beta safety margin, in buckets (8 × 15s = 2 minutes): an arc must stay non-lethal for this
         * many buckets after a platoon exits it.
         */
        private int hazardMarginBuckets = 8;
    }

    /**
     * Dispatch cost weights for the space-time search, bound from {@code graph.dispatch.*}.
     *
     * <p>These are the terms that decide what the search is willing to trade for what: how dearly it
     * prices sending people through a RISKY arc, and how dearly it prices holding them still. Only the
     * levers the current phase's code actually reads live here. The design names several more —
     * {@code mu}/{@code q} for the BPR congestion term, the convoy {@code epsilon}, the
     * anti-starvation {@code gamma} — and those arrive with the capacity phase that consumes them,
     * rather than sitting here as configuration an operator can turn with no effect.
     *
     * <p><strong>On the medical preference being a penalty rather than a bonus.</strong> The design's
     * pseudocode writes it as a subtraction from the sink arc's cost. The space-time search does not
     * relax into a priced sink state at all — it harvests sink entries in the order states settle, and
     * stops once the queue's head can no longer beat the best entry found. That shortcut is sound only
     * while a state's sink cost is never below its own settled distance, so nothing already ruled out
     * could have come back cheaper. A subtraction breaks that outright by risking a negative cost; a
     * multiplicative factor breaks it too, and in fact has no room to work here — a matching shelter's
     * sink arc costs nothing, so a discount could only bite by pushing the arrival cost below zero.
     * Charging the <em>mismatched</em> option instead keeps every sink arc non-negative and leaves the
     * ordering the bonus intended untouched. The preference stays soft either way: a nearer ordinary
     * shelter still wins if the penalty does not cover the difference.
     */
    @Getter
    @Setter
    public static class Dispatch {

        /**
         * Lambda — the price per unit of RISKY-arc person-time exposure, relative to base travel time.
         * Above 1.0 the search will accept a measurably longer route to keep people out of a risk band.
         */
        private double exposureWeight = 2.0;

        /**
         * {@code w_wait} — the cost multiplier for waiting one bucket in place instead of moving.
         * At 1.0 a minute spent waiting costs exactly what a minute spent travelling costs; lowering it
         * makes the search more willing to hold a party for a hazard or a queue to clear.
         */
        private double waitCostWeight = 1.0;

        /**
         * The design's {@code medBonus}, re-expressed as a charge on the option it disfavours: seconds
         * added to the shelter arc when a medical-preferred party arrives at a shelter <em>without</em>
         * a medical facility. A matching shelter pays nothing. Must stay at or above zero; see the
         * class note on why this is a penalty rather than a bonus.
         */
        private double medicalMismatchPenaltySeconds = 180.0;

        /**
         * Eta — the fraction of an arc's nominal capacity {@code c_a} the ledger is actually allowed
         * to reserve against, e.g. 0.85 reserves against 85% of {@code c_a} and leaves the rest as
         * headroom. Real compliance with a computed plan is uncertain, and this is the design's
         * documented mitigation: plan against {@code eta * c_a} rather than modelling human behaviour
         * this project has no data to support. Must be in {@code (0, 1]}.
         */
        private double capacityHeadroom = 0.85;

        /**
         * The largest number of people routed as a single unit. A party bigger than this is split
         * into successive waves, which is what makes a large group a flow over time rather than a
         * fiction that hundreds of people traverse a road simultaneously. Must be positive.
         */
        private int maxPlatoonSize = 30;

        /**
         * Buckets of separation between one party's successive waves. Zero would send every wave at
         * once and leave the capacity constraint to sort them out; a small positive stagger spaces
         * them deliberately, which both reflects how a large group actually leaves and gives the
         * search a reason to keep later waves off the arcs the earlier ones are still occupying.
         */
        private int convoyStaggerBuckets = 2;
    }
}