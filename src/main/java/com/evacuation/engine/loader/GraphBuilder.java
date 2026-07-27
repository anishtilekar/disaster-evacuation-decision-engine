package com.evacuation.engine.loader;

import com.evacuation.engine.graph.spatial.NearestNodeLocator;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.model.entity.RoadEdge;
import com.evacuation.engine.model.entity.RoadNode;
import com.evacuation.engine.model.entity.Shelter;
import com.evacuation.engine.model.enums.NodeType;
import com.evacuation.engine.model.enums.RoadStatus;
import com.evacuation.engine.repository.evacuation.ShelterRepository;
import com.evacuation.engine.repository.graph.RoadEdgeRepository;
import com.evacuation.engine.repository.graph.RoadNodeRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compiles the persisted road network and shelters into an immutable {@link GraphSnapshot}:
 * dense-id node arrays, CSR-encoded directed edge slots (a bidirectional {@link RoadEdge} becomes
 * two slots sharing one {@code edgeDbId}), and shelters snapped to their nearest routable node.
 *
 * <p>This is the architecture doc's "mutable builder → immutable runtime structure" step: a one-shot
 * compile off the query hot path, run to produce the snapshot that {@code GraphCache} swaps in.
 *
 * <p>Building runs in two phases because of shelter snapping. {@link NearestNodeLocator} searches a
 * {@link GraphSnapshot}, but a shelter can only be placed once the nodes and CSR exist. So the
 * builder first assembles an <em>intermediate</em> snapshot with no shelters (just enough to search),
 * snaps every shelter against it, then constructs the <em>final</em> snapshot from the same node and
 * CSR arrays plus the resolved shelter refs and a real {@code graphVersion}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GraphBuilder {

    /** A shelter is snapped only to a node within this distance; farther matches are implausible. */
    private static final double MAX_SNAP_DISTANCE_KM = 2.0;

    /**
     * Fallback waiting capacity for a node with no incident edges at all (a degenerate case — an
     * isolated node has no useful role in routing anyway — but every node still needs a defined
     * capacity, so this avoids a silent 0.0 that would make waiting there always infeasible).
     */
    private static final double DEFAULT_NODE_CAPACITY_PERSONS_PER_HOUR = 500.0;

    private final RoadNodeRepository roadNodeRepository;
    private final RoadEdgeRepository roadEdgeRepository;
    private final ShelterRepository shelterRepository;
    private final NearestNodeLocator nearestNodeLocator;

    /** One directed CSR slot, before it is placed into the contiguous CSR arrays. */
    private record SlotDescriptor(int fromIndex, int toIndex, long edgeDbId, double distanceKm,
                                  double timeMin, double capacityPersonsPerHour, RoadStatus status) {
    }

    /**
     * Loads the graph from the database and compiles the immutable snapshot.
     *
     * <p>Read-only and transactional so the LAZY {@code sourceNode}/{@code destinationNode}
     * associations stay resolvable while the arrays are built.
     *
     * @return the compiled, immutable graph snapshot
     */
    @Transactional(readOnly = true)
    public GraphSnapshot build() {
        // 1-2. Nodes → dense arrays, in ascending nodeId order for deterministic indices.
        List<RoadNode> nodes = new ArrayList<>(roadNodeRepository.findAll());
        nodes.sort(Comparator.comparing(RoadNode::getNodeId));

        int nodeCount = nodes.size();
        long[] dbNodeId = new long[nodeCount];
        String[] nodeName = new String[nodeCount];
        double[] nodeLat = new double[nodeCount];
        double[] nodeLon = new double[nodeCount];
        NodeType[] nodeType = new NodeType[nodeCount];
        boolean[] nodeActive = new boolean[nodeCount];
        Map<Long, Integer> nodeIdToIndex = new HashMap<>(nodeCount * 2);

        for (int i = 0; i < nodeCount; i++) {
            RoadNode node = nodes.get(i);
            dbNodeId[i] = node.getNodeId();
            nodeName[i] = node.getNodeName();
            nodeLat[i] = node.getLatitude();
            nodeLon[i] = node.getLongitude();
            nodeType[i] = node.getNodeType();
            nodeActive[i] = Boolean.TRUE.equals(node.getActive());
            nodeIdToIndex.put(node.getNodeId(), i);
        }
        Map<Long, Integer> nodeIndexView = Collections.unmodifiableMap(nodeIdToIndex);

        // 3-4. Edges → directed slot descriptors; bidirectional edges contribute a reverse slot.
        List<RoadEdge> edges = roadEdgeRepository.findAllForGraph();
        List<SlotDescriptor> slots = new ArrayList<>(edges.size() * 2);
        int skippedEdges = 0;

        for (RoadEdge edge : edges) {
            Long fromDbId = edge.getSourceNode().getNodeId();
            Long toDbId = edge.getDestinationNode().getNodeId();
            Integer fromIndex = nodeIdToIndex.get(fromDbId);
            Integer toIndex = nodeIdToIndex.get(toDbId);

            if (fromIndex == null || toIndex == null) {
                log.warn("Skipping edge {}: endpoint not in node set (source={}, destination={})",
                        edge.getEdgeId(), fromDbId, toDbId);
                skippedEdges++;
                continue;
            }

            long edgeDbId = edge.getEdgeId();
            double distanceKm = edge.getDistanceKm();
            double timeMin = edge.getEstimatedTravelTimeMinutes();
            double capacityPersonsPerHour = edge.getCapacityPersonsPerHour();
            RoadStatus status = edge.getRoadStatus();

            slots.add(new SlotDescriptor(
                    fromIndex, toIndex, edgeDbId, distanceKm, timeMin, capacityPersonsPerHour, status));
            if (Boolean.TRUE.equals(edge.getBidirectional())) {
                slots.add(new SlotDescriptor(
                        toIndex, fromIndex, edgeDbId, distanceKm, timeMin, capacityPersonsPerHour, status));
            }
        }

        // 5. Two-pass CSR: prefix-sum out-degrees into edgeHead, then place slots via a cursor.
        int slotCount = slots.size();
        int[] edgeHead = new int[nodeCount + 1];
        for (SlotDescriptor slot : slots) {
            edgeHead[slot.fromIndex() + 1]++;
        }
        for (int i = 0; i < nodeCount; i++) {
            edgeHead[i + 1] += edgeHead[i];
        }

        int[] cursor = Arrays.copyOf(edgeHead, nodeCount); // per-node write position
        int[] edgeTo = new int[slotCount];
        long[] edgeDbId = new long[slotCount];
        double[] edgeDistanceKm = new double[slotCount];
        double[] edgeTimeMin = new double[slotCount];
        double[] edgeCapacityPersonsPerHour = new double[slotCount];
        RoadStatus[] edgeBaseStatus = new RoadStatus[slotCount];

        for (SlotDescriptor slot : slots) {
            int pos = cursor[slot.fromIndex()]++;
            edgeTo[pos] = slot.toIndex();
            edgeDbId[pos] = slot.edgeDbId();
            edgeDistanceKm[pos] = slot.distanceKm();
            edgeTimeMin[pos] = slot.timeMin();
            edgeCapacityPersonsPerHour[pos] = slot.capacityPersonsPerHour();
            edgeBaseStatus[pos] = slot.status();
        }

        // 6. Node waiting capacity: the max capacity among each node's incident edges (both endpoints
        // of every slot, so a one-way edge's capacity still reaches the node it only ends at, not
        // just the node it starts from — the CSR's own outgoing-only adjacency would miss that).
        // OSM has no tag for "how many people can occupy this junction"; bounding it by the largest
        // connected approach is a defensible derived estimate, not a claimed measurement.
        double[] nodeCapacityPersonsPerHour = new double[nodeCount];
        Arrays.fill(nodeCapacityPersonsPerHour, DEFAULT_NODE_CAPACITY_PERSONS_PER_HOUR);
        for (SlotDescriptor slot : slots) {
            double capacity = slot.capacityPersonsPerHour();
            nodeCapacityPersonsPerHour[slot.fromIndex()] =
                    Math.max(nodeCapacityPersonsPerHour[slot.fromIndex()], capacity);
            nodeCapacityPersonsPerHour[slot.toIndex()] =
                    Math.max(nodeCapacityPersonsPerHour[slot.toIndex()], capacity);
        }

        // 7. Intermediate snapshot (no shelters) — only so shelters have something to snap against.
        GraphSnapshot intermediate = new GraphSnapshot(
                dbNodeId, nodeName, nodeLat, nodeLon, nodeType, nodeActive, nodeCapacityPersonsPerHour,
                nodeIndexView, edgeHead, edgeTo, edgeDbId, edgeDistanceKm, edgeTimeMin,
                edgeCapacityPersonsPerHour, edgeBaseStatus, List.of(), 0L, LocalDateTime.now());

        // 8. Snap each shelter to its nearest node within the cap; skip (warn) the unreachable ones.
        List<Shelter> shelters = shelterRepository.findAll();
        List<GraphSnapshot.ShelterRef> shelterRefs = new ArrayList<>(shelters.size());
        int skippedShelters = 0;

        for (Shelter shelter : shelters) {
            Optional<NearestNodeLocator.NearestNodeResult> nearest = nearestNodeLocator.findNearest(
                    shelter.getLatitude(), shelter.getLongitude(), intermediate, MAX_SNAP_DISTANCE_KM);

            if (nearest.isEmpty()) {
                log.warn("Shelter '{}' (id={}) has no routable node within {} km; excluding from routing",
                        shelter.getShelterName(), shelter.getShelterId(), MAX_SNAP_DISTANCE_KM);
                skippedShelters++;
                continue;
            }

            shelterRefs.add(new GraphSnapshot.ShelterRef(
                    shelter.getShelterId(),
                    nearest.get().nodeIndex(),
                    shelter.getShelterName(),
                    shelter.getShelterStatus(),
                    shelter.getAvailableCapacity(),
                    Boolean.TRUE.equals(shelter.getMedicalFacility()),
                    shelter.getLatitude(),
                    shelter.getLongitude()));
        }

        // 9. Final snapshot with the resolved shelters and a real, monotonic version.
        GraphSnapshot snapshot = new GraphSnapshot(
                dbNodeId, nodeName, nodeLat, nodeLon, nodeType, nodeActive, nodeCapacityPersonsPerHour,
                nodeIndexView, edgeHead, edgeTo, edgeDbId, edgeDistanceKm, edgeTimeMin,
                edgeCapacityPersonsPerHour, edgeBaseStatus, Collections.unmodifiableList(shelterRefs),
                System.currentTimeMillis(), LocalDateTime.now());

        log.info("Built graph snapshot: {} nodes, {} edge slots, {} shelters snapped, {} shelters skipped{}",
                nodeCount, slotCount, shelterRefs.size(), skippedShelters,
                skippedEdges > 0 ? " (" + skippedEdges + " edges skipped)" : "");

        return snapshot;
    }
}