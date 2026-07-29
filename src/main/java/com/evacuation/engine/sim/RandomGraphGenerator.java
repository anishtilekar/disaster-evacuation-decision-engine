package com.evacuation.engine.sim;

import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.model.enums.NodeType;
import com.evacuation.engine.model.enums.RoadStatus;
import com.evacuation.engine.model.enums.ShelterStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates small, random, structurally-valid {@link GraphSnapshot}s for the property-test suite.
 *
 * <p>Seeded for the same reproducibility reason {@link DemandGenerator} is: one seed must always
 * produce the exact same graph, so a property-test failure can be reproduced and turned into a
 * fixed regression case rather than an unrepeatable flake.
 *
 * <p><strong>CSR construction mirrors {@code GraphBuilder}'s own two-pass technique exactly</strong>
 * — collect every directed slot first, then prefix-sum out-degrees into {@code edgeHead}, then place
 * each slot at its node's cursor position. Getting this wrong (slots not grouped by source node,
 * offsets not a true prefix sum) would produce a snapshot that silently corrupts every algorithm
 * built on top of it rather than failing loudly, so this class does not improvise a shortcut here.
 *
 * <p>Connectivity is deliberately not guaranteed. A random graph can legitimately strand some or
 * all demand, and a property test asserting invariants must hold under that outcome too — a
 * generator that guaranteed routability would be testing a narrower, easier world than the real one.
 */
public final class RandomGraphGenerator {

    private static final long GRAPH_VERSION = 1L;

    /** Generous enough that capacity is rarely the reason a property test's demand goes unplaced. */
    private static final double MIN_CAPACITY_PERSONS_PER_HOUR = 500.0;
    private static final double MAX_CAPACITY_PERSONS_PER_HOUR = 5_000.0;
    private static final double MIN_DISTANCE_KM = 0.1;
    private static final double MAX_DISTANCE_KM = 2.0;
    private static final double MIN_TIME_MIN = 0.25;
    private static final double MAX_TIME_MIN = 3.0;
    private static final int MIN_SHELTER_CAPACITY = 20;
    private static final int MAX_SHELTER_CAPACITY = 200;

    /** One directed slot, before CSR placement — the same shape GraphBuilder's own pass uses. */
    private record SlotDescriptor(int fromIndex, int toIndex, long edgeDbId, double distanceKm,
                                  double timeMin, double capacityPersonsPerHour) {
    }

    private final Random random;

    public RandomGraphGenerator(long seed) {
        this.random = new Random(seed);
    }

    /**
     * @param nodeCount       how many nodes the graph has; must be at least 2
     * @param edgeProbability the independent probability, per ordered pair of distinct nodes, that
     *                        a directed edge exists between them; each such edge is independently
     *                        bidirectional with probability 0.5
     * @return a structurally-valid random snapshot with exactly one shelter
     * @throws IllegalArgumentException if {@code nodeCount < 2} or {@code edgeProbability} is not
     *                                  in {@code [0, 1]}
     */
    public GraphSnapshot generate(int nodeCount, double edgeProbability) {
        if (nodeCount < 2) {
            throw new IllegalArgumentException("nodeCount must be >= 2, got " + nodeCount);
        }
        if (edgeProbability < 0.0 || edgeProbability > 1.0) {
            throw new IllegalArgumentException(
                    "edgeProbability must be in [0, 1], got " + edgeProbability);
        }

        long[] dbNodeId = new long[nodeCount];
        String[] nodeName = new String[nodeCount];
        double[] nodeLat = new double[nodeCount];
        double[] nodeLon = new double[nodeCount];
        NodeType[] nodeType = new NodeType[nodeCount];
        boolean[] nodeActive = new boolean[nodeCount];
        double[] nodeCapacityPersonsPerHour = new double[nodeCount];
        Map<Long, Integer> nodeIdToIndex = new HashMap<>(nodeCount * 2);

        for (int i = 0; i < nodeCount; i++) {
            dbNodeId[i] = i + 1L;
            nodeName[i] = "Node " + i;
            // A small bounding box is enough — property tests never read real geography, only
            // topology and capacity.
            nodeLat[i] = 18.50 + random.nextDouble() * 0.05;
            nodeLon[i] = 73.85 + random.nextDouble() * 0.05;
            nodeType[i] = NodeType.INTERSECTION;
            nodeActive[i] = true;
            nodeCapacityPersonsPerHour[i] = randomInRange(MIN_CAPACITY_PERSONS_PER_HOUR,
                    MAX_CAPACITY_PERSONS_PER_HOUR);
            nodeIdToIndex.put(dbNodeId[i], i);
        }

        List<SlotDescriptor> slots = new ArrayList<>();
        long nextEdgeDbId = 1L;
        for (int from = 0; from < nodeCount; from++) {
            for (int to = 0; to < nodeCount; to++) {
                if (from == to || random.nextDouble() >= edgeProbability) {
                    continue;
                }
                long edgeDbId = nextEdgeDbId++;
                double distanceKm = randomInRange(MIN_DISTANCE_KM, MAX_DISTANCE_KM);
                double timeMin = randomInRange(MIN_TIME_MIN, MAX_TIME_MIN);
                double capacity = randomInRange(MIN_CAPACITY_PERSONS_PER_HOUR,
                        MAX_CAPACITY_PERSONS_PER_HOUR);

                slots.add(new SlotDescriptor(from, to, edgeDbId, distanceKm, timeMin, capacity));
                if (random.nextBoolean()) {
                    slots.add(new SlotDescriptor(to, from, edgeDbId, distanceKm, timeMin, capacity));
                }
            }
        }

        // Two-pass CSR, mirroring GraphBuilder exactly: prefix-sum out-degrees, then place slots
        // via a per-node cursor. Slots are generated in ascending fromIndex order already (the
        // nested loop above), so no separate sort is needed before the prefix sum.
        int slotCount = slots.size();
        int[] edgeHead = new int[nodeCount + 1];
        for (SlotDescriptor slot : slots) {
            edgeHead[slot.fromIndex() + 1]++;
        }
        for (int i = 0; i < nodeCount; i++) {
            edgeHead[i + 1] += edgeHead[i];
        }

        int[] cursor = new int[nodeCount];
        System.arraycopy(edgeHead, 0, cursor, 0, nodeCount);
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
            edgeBaseStatus[pos] = RoadStatus.OPEN;
        }

        int shelterNodeIndex = random.nextInt(nodeCount);
        int shelterCapacity = MIN_SHELTER_CAPACITY
                + random.nextInt(MAX_SHELTER_CAPACITY - MIN_SHELTER_CAPACITY + 1);
        GraphSnapshot.ShelterRef shelter = new GraphSnapshot.ShelterRef(
                1L, shelterNodeIndex, "Random Shelter", ShelterStatus.AVAILABLE,
                shelterCapacity, false, nodeLat[shelterNodeIndex], nodeLon[shelterNodeIndex]);

        return new GraphSnapshot(
                dbNodeId, nodeName, nodeLat, nodeLon, nodeType, nodeActive, nodeCapacityPersonsPerHour,
                nodeIdToIndex, edgeHead, edgeTo, edgeDbId, edgeDistanceKm, edgeTimeMin,
                edgeCapacityPersonsPerHour, edgeBaseStatus, List.of(shelter), GRAPH_VERSION,
                LocalDateTime.now());
    }

    private double randomInRange(double min, double max) {
        return min + random.nextDouble() * (max - min);
    }
}
