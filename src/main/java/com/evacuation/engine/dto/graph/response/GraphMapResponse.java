package com.evacuation.engine.dto.graph.response;

import java.util.List;

/**
 * The map frontend's read model of the live routing graph — built directly from the in-memory
 * {@link com.evacuation.engine.graph.structure.GraphSnapshot}, not from the database entities
 * {@link RoadNetworkGraphDTO} serializes.
 *
 * <p>That distinction matters: the snapshot is the CSR structure every algorithm in this project
 * actually routes against, dense-indexed and already carrying shelters snapped onto their nodes. The
 * DB-entity DTOs describe what was imported, not what is currently routable, and have no shelter
 * field at all. A map that is going to draw hazard overlays and animate platoons along the same
 * dense node indices the search itself uses needs the snapshot's own numbering, so this reads from
 * {@link com.evacuation.engine.loader.GraphCache} directly.
 *
 * @param graphVersion the snapshot version this response was built from
 * @param nodes        every node in the current snapshot
 * @param edges        every directed CSR edge slot in the current snapshot — a bidirectional road
 *                     contributes two entries, one per direction, since that is how the snapshot
 *                     itself models it and how a hazard timeline's lethality is addressed
 * @param shelters     every shelter, already snapped to its owning node
 */
public record GraphMapResponse(long graphVersion, List<MapNode> nodes, List<MapEdge> edges,
                               List<MapShelter> shelters) {

    /**
     * @param nodeIndex the dense CSR index — the same id used everywhere else in this API (walk
     *                  waypoints, hazard cells) to refer to this node
     * @param dbNodeId  the database {@code RoadNode.nodeId}, for cross-referencing against admin
     *                  endpoints that still speak in database ids (e.g. blocking a road)
     * @param name      the node's display name
     * @param lat       latitude
     * @param lon       longitude
     * @param nodeType  the node's type, as its enum name
     * @param active    whether the base graph considers this node active
     */
    public record MapNode(int nodeIndex, long dbNodeId, String name, double lat, double lon,
                          String nodeType, boolean active) {
    }

    /**
     * @param slot                   the CSR edge slot — the id a hazard-overlay or repair-diff
     *                               response uses to name this exact directed traversal
     * @param edgeDbId               the database {@code RoadEdge.edgeId}; both directions of a
     *                               bidirectional road share this
     * @param fromNodeIndex          the dense index this slot departs from
     * @param toNodeIndex            the dense index this slot arrives at
     * @param distanceKm             road distance
     * @param timeMin                free-flow travel time
     * @param capacityPersonsPerHour hourly throughput capacity
     */
    public record MapEdge(int slot, long edgeDbId, int fromNodeIndex, int toNodeIndex,
                          double distanceKm, double timeMin, double capacityPersonsPerHour) {
    }

    /**
     * @param shelterId         the database shelter id
     * @param nodeIndex         the dense node index this shelter is snapped to
     * @param shelterName       display name
     * @param status            the shelter's status, as its enum name
     * @param availableCapacity the shelter's static capacity (not live remaining room — that is a
     *                          function of the current plan, not the graph, and belongs to the
     *                          dispatch endpoints)
     * @param medicalFacility   whether this shelter is preferred for medical-assistance platoons
     * @param lat               latitude
     * @param lon               longitude
     */
    public record MapShelter(long shelterId, int nodeIndex, String shelterName, String status,
                             int availableCapacity, boolean medicalFacility, double lat, double lon) {
    }
}
