package com.evacuation.engine.web;

import com.evacuation.engine.dto.common.ApiResponse;
import com.evacuation.engine.dto.graph.response.GraphMapResponse;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.loader.GraphCache;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * The map frontend's single read of the routing graph's current shape — nodes, directed edge slots,
 * and shelters, dense-indexed exactly as every routing/dispatch algorithm in this project already
 * addresses them. See {@link GraphMapResponse} for why this reads the live
 * {@link com.evacuation.engine.graph.structure.GraphSnapshot} rather than the database entities.
 */
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class GraphController {

    private final GraphCache graphCache;

    /**
     * @return {@code 200 OK} with the current snapshot's full topology and shelters
     * @throws IllegalStateException if no graph has been built yet
     */
    @GetMapping
    public ResponseEntity<ApiResponse<GraphMapResponse>> getGraph() {
        GraphSnapshot snapshot = graphCache.get();

        List<GraphMapResponse.MapNode> nodes = new ArrayList<>(snapshot.nodeCount());
        for (int nodeIndex = 0; nodeIndex < snapshot.nodeCount(); nodeIndex++) {
            nodes.add(new GraphMapResponse.MapNode(
                    nodeIndex, snapshot.dbNodeId(nodeIndex), snapshot.nodeName(nodeIndex),
                    snapshot.nodeLat(nodeIndex), snapshot.nodeLon(nodeIndex),
                    snapshot.nodeType(nodeIndex).name(), snapshot.nodeActive(nodeIndex)));
        }

        List<GraphMapResponse.MapEdge> edges = new ArrayList<>(snapshot.edgeSlotCount());
        for (int slot = 0; slot < snapshot.edgeSlotCount(); slot++) {
            int fromNodeIndex = fromNodeIndexOf(snapshot, slot);
            edges.add(new GraphMapResponse.MapEdge(
                    slot, snapshot.edgeDbId(slot), fromNodeIndex, snapshot.edgeTo(slot),
                    snapshot.edgeDistanceKm(slot), snapshot.edgeTimeMin(slot),
                    snapshot.edgeCapacityPersonsPerHour(slot)));
        }

        List<GraphMapResponse.MapShelter> shelters = new ArrayList<>(snapshot.shelters().size());
        for (GraphSnapshot.ShelterRef shelter : snapshot.shelters()) {
            shelters.add(new GraphMapResponse.MapShelter(
                    shelter.shelterId(), shelter.nodeIndex(), shelter.shelterName(),
                    shelter.status().name(), shelter.availableCapacity(), shelter.medicalFacility(),
                    shelter.lat(), shelter.lon()));
        }

        GraphMapResponse response = new GraphMapResponse(
                snapshot.graphVersion(), nodes, edges, shelters);

        return ResponseEntity.ok(ApiResponse.success("Graph retrieved", response));
    }

    /**
     * The snapshot's CSR exposes a slot's owning node only implicitly, via each node's
     * {@code slotsStart}/{@code slotsEnd} range, rather than a direct {@code edgeFrom(slot)}
     * accessor. {@code edgeHead} is a prefix sum over ascending node index, so the ranges are sorted
     * and contiguous — exactly the shape a binary search over node index wants, giving each lookup
     * {@code O(log nodeCount)} instead of a linear scan per slot.
     */
    private int fromNodeIndexOf(GraphSnapshot snapshot, int slot) {
        int low = 0;
        int high = snapshot.nodeCount() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (slot < snapshot.slotsStart(mid)) {
                high = mid - 1;
            } else if (slot >= snapshot.slotsEnd(mid)) {
                low = mid + 1;
            } else {
                return mid;
            }
        }
        throw new IllegalStateException("Edge slot " + slot + " is not owned by any node in the CSR");
    }
}
