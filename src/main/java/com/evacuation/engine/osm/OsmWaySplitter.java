package com.evacuation.engine.osm;

import com.evacuation.engine.graph.spatial.GeoUtils;
import com.evacuation.engine.osm.dto.OsmElement;
import com.evacuation.engine.osm.dto.OsmResponse;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implements the architecture doc's Phase 2 "way-splitting".
 *
 * <p>An OSM way traces many GPS shape points but only a handful are real routing decisions. Only
 * <strong>way endpoints</strong> and <strong>nodes shared by two or more ways</strong> (true
 * intersections) become graph vertices; the intermediate shape points are dropped, but their
 * haversine length is accumulated into the resulting segment. This keeps the node count
 * "routing-sized" rather than "GPS-trace-sized", which is what the in-memory graph and the
 * shortest-path search actually need.
 */
@Component
public class OsmWaySplitter {

    /** Maximum stored length of a derived road name, matching {@code RoadEdge.roadName}. */
    private static final int MAX_ROAD_NAME_LENGTH = 150;

    /** A graph vertex with its OSM id and coordinates. */
    public record SplitNode(long osmId, double lat, double lon) {
    }

    /**
     * A single routable segment between two graph vertices. {@code lengthKm} is the summed
     * haversine length of the shape points it collapses; {@code bidirectional} is {@code false}
     * for one-way roads (the graph builder expands two-way segments into two directed edges).
     */
    public record SplitSegment(long fromOsmId, long toOsmId, double lengthKm, String roadName,
                               double speedKmh, boolean bidirectional) {
    }

    /** The vertices and segments produced from an Overpass response. */
    public record SplitResult(List<SplitNode> nodes, List<SplitSegment> segments) {
    }

    /**
     * Splits an Overpass response into graph vertices and segments.
     *
     * <p>Iterates ways in input order for deterministic output. Nodes referenced by a way but
     * absent from the response are skipped defensively; degenerate (self-looping or zero-length)
     * segments are dropped.
     *
     * @param response the parsed Overpass response (nodes and ways)
     * @param speeds   resolves an assumed speed per way from its {@code highway}/{@code maxspeed}
     * @return the vertices actually used by emitted segments, plus those segments
     */
    public SplitResult split(OsmResponse response, OsmSpeedTable speeds) {
        if (response == null || response.elements == null) {
            return new SplitResult(List.of(), List.of());
        }

        // 1. Index every node by OSM id for coordinate lookup.
        Map<Long, OsmElement> nodeById = new HashMap<>();
        for (OsmElement element : response.elements) {
            if (element.isNode()) {
                nodeById.put(element.id, element);
            }
        }

        // 2. Collect routable ways (highway-tagged, at least two nodes), in input order.
        List<OsmElement> ways = new ArrayList<>();
        for (OsmElement element : response.elements) {
            if (element.isWay()
                    && element.tag("highway") != null
                    && element.nodes != null
                    && element.nodes.size() >= 2) {
                ways.add(element);
            }
        }

        // 3. Vertex rule: an endpoint of any way, or a node shared by >= 2 ways (present in data).
        Set<Long> vertexIds = computeVertexIds(ways, nodeById);

        // 4-5. Walk each way, emitting one segment per vertex-to-vertex span.
        List<SplitSegment> segments = new ArrayList<>();
        Set<Long> usedNodeIds = new LinkedHashSet<>();
        for (OsmElement way : ways) {
            processWay(way, nodeById, vertexIds, speeds, segments, usedNodeIds);
        }

        // 6. Materialise only the vertices actually referenced by an emitted segment.
        List<SplitNode> nodes = new ArrayList<>(usedNodeIds.size());
        for (Long id : usedNodeIds) {
            OsmElement node = nodeById.get(id);
            nodes.add(new SplitNode(id, node.lat, node.lon));
        }

        return new SplitResult(nodes, segments);
    }

    /**
     * Computes the graph-vertex id set: first/last node of any way, or any node referenced by two
     * or more distinct ways. Only ids present in {@code nodeById} qualify.
     */
    private Set<Long> computeVertexIds(List<OsmElement> ways, Map<Long, OsmElement> nodeById) {
        Set<Long> endpointIds = new HashSet<>();
        Map<Long, Integer> wayReferenceCount = new HashMap<>();

        for (OsmElement way : ways) {
            List<Long> ids = way.nodes;
            endpointIds.add(ids.get(0));
            endpointIds.add(ids.get(ids.size() - 1));
            for (Long id : new HashSet<>(ids)) {
                wayReferenceCount.merge(id, 1, Integer::sum);
            }
        }

        Set<Long> vertexIds = new HashSet<>();
        for (Long id : endpointIds) {
            if (nodeById.containsKey(id)) {
                vertexIds.add(id);
            }
        }
        for (Map.Entry<Long, Integer> entry : wayReferenceCount.entrySet()) {
            if (entry.getValue() >= 2 && nodeById.containsKey(entry.getKey())) {
                vertexIds.add(entry.getKey());
            }
        }
        return vertexIds;
    }

    /**
     * Walks one way's node list, summing shape-point lengths into segments that begin and end on
     * graph vertices, and appends them to {@code segments} / {@code usedNodeIds}.
     */
    private void processWay(OsmElement way,
                            Map<Long, OsmElement> nodeById,
                            Set<Long> vertexIds,
                            OsmSpeedTable speeds,
                            List<SplitSegment> segments,
                            Set<Long> usedNodeIds) {

        String roadName = resolveRoadName(way);
        double speedKmh = speeds.speedKmh(way.tag("highway"), way.tag("maxspeed"));

        boolean reverse = false;
        boolean oneWay;
        String oneway = way.tag("oneway");
        String direction = oneway == null ? "" : oneway.trim().toLowerCase();
        if (direction.equals("-1") || direction.equals("reverse")) {
            oneWay = true;
            reverse = true; // digitised backwards: flip so the edge points the way of travel
        } else if (direction.equals("yes") || direction.equals("true") || direction.equals("1")) {
            oneWay = true;
        } else {
            oneWay = "roundabout".equalsIgnoreCase(way.tag("junction"));
        }
        boolean bidirectional = !oneWay;

        Long segStart = null;
        Long prev = null;
        double accumulatedKm = 0.0;

        for (Long id : way.nodes) {
            OsmElement node = nodeById.get(id);
            if (node == null) {
                continue; // referenced node missing from the response; skip defensively
            }
            if (prev != null) {
                OsmElement prevNode = nodeById.get(prev);
                accumulatedKm += GeoUtils.haversineKm(prevNode.lat, prevNode.lon, node.lat, node.lon);
            }
            if (segStart == null) {
                segStart = id;
                accumulatedKm = 0.0;
            } else if (vertexIds.contains(id)) {
                emitSegment(segStart, id, accumulatedKm, roadName, speedKmh, bidirectional, reverse,
                        segments, usedNodeIds);
                segStart = id;
                accumulatedKm = 0.0;
            }
            prev = id;
        }
    }

    /**
     * Emits a segment between two vertices, honouring reverse one-ways (from/to swapped) and
     * skipping degenerate self-loops or zero-length spans.
     */
    private void emitSegment(long segStart, long current, double lengthKm, String roadName,
                             double speedKmh, boolean bidirectional, boolean reverse,
                             List<SplitSegment> segments, Set<Long> usedNodeIds) {
        long from = reverse ? current : segStart;
        long to = reverse ? segStart : current;
        if (from == to || lengthKm <= 0.0) {
            return;
        }
        segments.add(new SplitSegment(from, to, lengthKm, roadName, speedKmh, bidirectional));
        usedNodeIds.add(from);
        usedNodeIds.add(to);
    }

    /** Derives a road name from {@code name}, falling back to {@code highway}, capped to length. */
    private String resolveRoadName(OsmElement way) {
        String name = way.tag("name");
        String roadName = (name != null && !name.isBlank()) ? name : way.tag("highway");
        if (roadName != null && roadName.length() > MAX_ROAD_NAME_LENGTH) {
            roadName = roadName.substring(0, MAX_ROAD_NAME_LENGTH);
        }
        return roadName;
    }
}