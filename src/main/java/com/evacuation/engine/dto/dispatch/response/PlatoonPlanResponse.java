package com.evacuation.engine.dto.dispatch.response;

import java.util.List;

/**
 * One committed platoon's route, coordinate-resolved for direct rendering: a departure bucket, an
 * ordered list of waypoints, and the shelter it ends at.
 *
 * @param partyId          the owning party
 * @param platoonId        this platoon's id
 * @param waveIndex        this platoon's position among its party's waves
 * @param size             how many people this platoon carries
 * @param priority         the owning party's priority, as its enum name
 * @param medicalPreferred whether this platoon was searched with a medical-facility preference
 * @param destinationKind  {@code "SHELTER"} for a nearest-eligible-shelter route, {@code "CHOSEN"}
 *                         for a route to a destination the requester picked themselves
 * @param shelterId        the destination shelter's id, or {@code null} for a {@code "CHOSEN"} route
 * @param destinationName  the destination shelter's display name, or {@code null} for a
 *                         {@code "CHOSEN"} route with no name to show
 * @param destinationLat   destination latitude
 * @param destinationLon   destination longitude
 * @param waypoints        the walk's states in order, origin first — a frontend interpolates
 *                         between consecutive waypoints by bucket to animate the platoon smoothly
 *                         rather than jumping node to node
 * @param departureBucket  the first waypoint's bucket
 * @param arrivalBucket    the last waypoint's bucket
 * @param totalCost        the search's accumulated cost for this walk
 * @param totalDistanceKm  total road distance travelled
 */
public record PlatoonPlanResponse(long partyId, long platoonId, int waveIndex, int size,
                                  String priority, boolean medicalPreferred,
                                  String destinationKind, Long shelterId, String destinationName,
                                  double destinationLat, double destinationLon,
                                  List<RouteWaypoint> waypoints,
                                  int departureBucket, int arrivalBucket,
                                  double totalCost, double totalDistanceKm) {

    /**
     * @param nodeIndex the dense node index this waypoint sits at
     * @param lat       latitude
     * @param lon       longitude
     * @param bucket    the time bucket the platoon reaches this waypoint
     * @param isWait    {@code true} if this waypoint is reached by waiting in place rather than
     *                  traversing an edge — the same node as the previous waypoint, one or more
     *                  buckets later
     */
    public record RouteWaypoint(int nodeIndex, double lat, double lon, int bucket, boolean isWait) {
    }
}
