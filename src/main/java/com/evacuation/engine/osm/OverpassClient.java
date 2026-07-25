package com.evacuation.engine.osm;

import com.evacuation.engine.config.GraphEngineProperties;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * Thin HTTP client that fetches the ward's routable roads <em>and</em> its shelter facilities in a
 * single Overpass call, returning the raw JSON.
 *
 * <p>Both are pulled in one polygon-scoped query so the road network and the shelter POIs come from
 * exactly the same ward boundary and the same snapshot — no risk of them drifting apart across two
 * requests, and one fewer round trip to a rate-limited public API. The class stays deliberately
 * narrow: it only builds the query and performs the POST; caching and parsing are
 * {@code OsmImportService}'s job. A bounded read timeout ensures a slow Overpass server can never
 * hang startup indefinitely.
 */
@Component
public class OverpassClient {

    /** Connect timeout — a fast fail if Overpass is unreachable. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /** Client-side read timeout headroom over the Overpass server-side query timeout, in seconds. */
    private static final long READ_TIMEOUT_HEADROOM_SECONDS = 30;

    private final RestClient restClient;

    public OverpassClient(GraphEngineProperties props) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(
                Duration.ofSeconds(props.getOsm().getTimeoutSeconds() + READ_TIMEOUT_HEADROOM_SECONDS));

        this.restClient = RestClient.builder()
                .baseUrl(props.getOsm().getOverpassUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Fetches the raw Overpass JSON for the ward — routable roads plus shelter POIs — in one query.
     *
     * <p>Every filter is scoped to the ward polygon via {@link WardBoundary#toOverpassPoly()}. The
     * union collects highway ways, plus amenity nodes and ways matching {@code shelterAmenities};
     * the trailing recursion ({@code (._;>;);}) pulls in every node the matched ways reference, so
     * ways arrive with full geometry.
     *
     * @param ward             the ward boundary defining the {@code poly:} filter
     * @param shelterAmenities OSM {@code amenity} values to treat as shelters (joined into the regex)
     * @param timeoutSeconds   the Overpass server-side query timeout to request
     * @return the raw JSON response body
     */
    public String fetchRaw(WardBoundary ward, List<String> shelterAmenities, int timeoutSeconds) {
        String poly = ward.toOverpassPoly();
        String amenities = String.join("|", shelterAmenities);

        String query = """
                [out:json][timeout:%d];
                (
                  way["highway"~"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|service)$"](poly:"%s");
                  node["amenity"~"^(%s)$"](poly:"%s");
                  way["amenity"~"^(%s)$"](poly:"%s");
                );
                (._;>;);
                out body;
                """.formatted(timeoutSeconds, poly, amenities, poly, amenities, poly);

        return restClient.post()
                .contentType(MediaType.TEXT_PLAIN)
                .body(query)
                .retrieve()
                .body(String.class);
    }
}