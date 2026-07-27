package com.evacuation.engine.osm;

import com.evacuation.engine.config.BoundingBox;
import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.model.entity.Disaster;
import com.evacuation.engine.model.entity.RoadEdge;
import com.evacuation.engine.model.entity.RoadNode;
import com.evacuation.engine.model.entity.Shelter;
import com.evacuation.engine.model.enums.DisasterStatus;
import com.evacuation.engine.model.enums.DisasterType;
import com.evacuation.engine.model.enums.NodeType;
import com.evacuation.engine.model.enums.RoadStatus;
import com.evacuation.engine.model.enums.SeverityLevel;
import com.evacuation.engine.osm.dto.OsmResponse;
import com.evacuation.engine.repository.disaster.DisasterRepository;
import com.evacuation.engine.repository.evacuation.ShelterRepository;
import com.evacuation.engine.repository.graph.RoadEdgeRepository;
import com.evacuation.engine.repository.graph.RoadNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the one-shot import of a real Pune ward into the persistence model.
 *
 * <p>The pipeline: load the ward boundary GeoJSON, obtain the raw Overpass JSON for that polygon,
 * parse it, way-split the roads into graph vertices/segments, extract real shelter facilities, then
 * persist {@link RoadNode}s, {@link RoadEdge}s, {@link Shelter}s, and one sample {@link Disaster}.
 *
 * <p>The raw Overpass response is cached to a file keyed by ward name (a slug of
 * {@code graph.osm.wardName}). After the first fetch the import is deterministic and offline: a
 * {@code create-drop} schema is re-seeded each boot from the cached JSON, with no network call.
 */
@Service
public class OsmImportService {

    private static final Logger log = LoggerFactory.getLogger(OsmImportService.class);

    /** Column cap mirrored from {@link RoadEdge#getRoadName()}. */
    private static final int MAX_ROAD_NAME_LENGTH = 150;

    /** Floor for a segment's length so the @Positive edge constraint always holds. */
    private static final double MIN_DISTANCE_KM = 0.001;

    /** Floor for travel time so the @Positive edge constraint always holds. */
    private static final double MIN_TRAVEL_TIME_MIN = 0.1;

    /** Fallback road name when a segment carries none. */
    private static final String DEFAULT_ROAD_NAME = "Road";

    /** Nominal impact radius (km) for the seeded demo disaster. */
    private static final double SAMPLE_IMPACT_RADIUS_KM = 1.0;

    private final WardPolygonLoader wardPolygonLoader;
    private final OverpassClient overpassClient;
    private final OsmWaySplitter waySplitter;
    private final OsmSpeedTable speedTable;
    private final OsmCapacityTable capacityTable;
    private final OsmShelterExtractor osmShelterExtractor;
    private final GraphEngineProperties props;
    private final RoadNodeRepository roadNodeRepository;
    private final RoadEdgeRepository roadEdgeRepository;
    private final ShelterRepository shelterRepository;
    private final DisasterRepository disasterRepository;
    private final ObjectMapper objectMapper;

    public OsmImportService(WardPolygonLoader wardPolygonLoader,
                            OverpassClient overpassClient,
                            OsmWaySplitter waySplitter,
                            OsmSpeedTable speedTable,
                            OsmCapacityTable capacityTable,
                            OsmShelterExtractor osmShelterExtractor,
                            GraphEngineProperties props,
                            RoadNodeRepository roadNodeRepository,
                            RoadEdgeRepository roadEdgeRepository,
                            ShelterRepository shelterRepository,
                            DisasterRepository disasterRepository,
                            ObjectMapper objectMapper) {
        this.wardPolygonLoader = wardPolygonLoader;
        this.overpassClient = overpassClient;
        this.waySplitter = waySplitter;
        this.speedTable = speedTable;
        this.capacityTable = capacityTable;
        this.osmShelterExtractor = osmShelterExtractor;
        this.props = props;
        this.roadNodeRepository = roadNodeRepository;
        this.roadEdgeRepository = roadEdgeRepository;
        this.shelterRepository = shelterRepository;
        this.disasterRepository = disasterRepository;
        this.objectMapper = objectMapper;
    }

    /** Summary of a completed ward import. */
    public record ImportSummary(String wardName, int nodes, int edges, int shelters) {
    }

    /** @return {@code true} if no road nodes are persisted yet (the graph needs importing). */
    public boolean isGraphEmpty() {
        return roadNodeRepository.count() == 0;
    }

    /**
     * Runs the full ward import and persists the result.
     *
     * <p>Any I/O or JSON error while obtaining the raw Overpass data is surfaced as an
     * {@link UncheckedIOException} so the public method stays free of checked exceptions.
     *
     * @return counts of what was imported for the configured ward
     */
    @Transactional
    public ImportSummary importWard() {
        WardBoundary ward = wardPolygonLoader.load(props.getOsm().getWardPolygonFile());

        OsmResponse response;
        try {
            String rawJson = loadRawJson(ward);
            response = objectMapper.readValue(rawJson, OsmResponse.class);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load or parse OSM data for the ward import", ex);
        }

        OsmWaySplitter.SplitResult split = waySplitter.split(response, speedTable);

        // Nodes: one RoadNode per split vertex, indexed by OSM id for edge resolution.
        Map<Long, RoadNode> byOsmId = new HashMap<>();
        List<RoadNode> nodes = new ArrayList<>(split.nodes().size());
        for (OsmWaySplitter.SplitNode splitNode : split.nodes()) {
            RoadNode node = RoadNode.builder()
                    .nodeName("Node-" + splitNode.osmId())
                    .nodeType(NodeType.INTERSECTION)
                    .latitude(splitNode.lat())
                    .longitude(splitNode.lon())
                    .active(true)
                    .build();
            byOsmId.put(splitNode.osmId(), node);
            nodes.add(node);
        }
        roadNodeRepository.saveAll(nodes);

        // Edges: one RoadEdge per split segment whose endpoints both resolved to distinct nodes.
        List<RoadEdge> edges = new ArrayList<>();
        for (OsmWaySplitter.SplitSegment segment : split.segments()) {
            RoadNode from = byOsmId.get(segment.fromOsmId());
            RoadNode to = byOsmId.get(segment.toOsmId());
            if (from == null || to == null || from == to) {
                continue;
            }

            double distanceKm = Math.max(segment.lengthKm(), MIN_DISTANCE_KM);
            double speedKmh = segment.speedKmh() > 0 ? segment.speedKmh() : props.getNetworkMaxSpeedKmh();
            double travelTimeMin = Math.max(distanceKm / speedKmh * 60.0, MIN_TRAVEL_TIME_MIN);
            double capacityPersonsPerHour = capacityTable.capacityPersonsPerHour(
                    segment.highwayType(), segment.lanes());
            String roadName = segment.roadName() != null
                    ? truncate(segment.roadName(), MAX_ROAD_NAME_LENGTH)
                    : DEFAULT_ROAD_NAME;

            edges.add(RoadEdge.builder()
                    .roadName(roadName)
                    .sourceNode(from)
                    .destinationNode(to)
                    .distanceKm(distanceKm)
                    .estimatedTravelTimeMinutes(travelTimeMin)
                    .capacityPersonsPerHour(capacityPersonsPerHour)
                    .roadStatus(RoadStatus.OPEN)
                    .bidirectional(segment.bidirectional())
                    .build());
        }
        roadEdgeRepository.saveAll(edges);

        // Shelters: real OSM amenities clipped to the ward.
        List<Shelter> shelters = osmShelterExtractor.extract(response.elements, ward);
        shelterRepository.saveAll(shelters);

        // One sample disaster centred on the ward, for route-optimization demos.
        BoundingBox bbox = ward.toBoundingBox();
        double centroidLat = (bbox.south() + bbox.north()) / 2.0;
        double centroidLon = (bbox.west() + bbox.east()) / 2.0;
        Disaster disaster = Disaster.builder()
                .disasterName("Sample Disaster - " + props.getOsm().getWardName())
                .disasterType(DisasterType.FLOOD)
                .description("Seeded sample disaster for route-optimization demos")
                .severityLevel(SeverityLevel.MEDIUM)
                .startTime(LocalDateTime.now())
                .status(DisasterStatus.ACTIVE)
                .affectedRegion(props.getOsm().getWardName())
                .latitude(centroidLat)
                .longitude(centroidLon)
                .impactRadius(SAMPLE_IMPACT_RADIUS_KM)
                .build();
        disasterRepository.save(disaster);

        ImportSummary summary = new ImportSummary(
                props.getOsm().getWardName(), nodes.size(), edges.size(), shelters.size());
        log.info("Imported ward '{}': {} nodes, {} edges, {} shelters",
                summary.wardName(), summary.nodes(), summary.edges(), summary.shelters());
        return summary;
    }

    /**
     * Returns the raw Overpass JSON for the ward, cache-first.
     *
     * <p>The cache file is keyed by a slug of the ward name under {@code graph.osm.cacheDir}. If it
     * exists it is read verbatim; otherwise the data is fetched live, written to that file (parent
     * dirs created), and returned — so subsequent boots are deterministic and offline.
     */
    private String loadRawJson(WardBoundary ward) throws IOException {
        String token = props.getOsm().getWardName().toLowerCase().replaceAll("[^a-z0-9]+", "-");
        Path cacheFile = Path.of(props.getOsm().getCacheDir(), token + ".json");

        if (Files.exists(cacheFile)) {
            log.debug("Using cached Overpass response at {}", cacheFile);
            return Files.readString(cacheFile);
        }

        log.info("No cache at {}; fetching Overpass data live for ward '{}'",
                cacheFile, props.getOsm().getWardName());
        String rawJson = overpassClient.fetchRaw(
                ward, props.getOsm().getShelterAmenities(), props.getOsm().getTimeoutSeconds());

        Path parent = cacheFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(cacheFile, rawJson, StandardCharsets.UTF_8);
        return rawJson;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}