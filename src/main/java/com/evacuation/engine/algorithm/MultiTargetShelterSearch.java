package com.evacuation.engine.algorithm;

import com.evacuation.engine.graph.overlay.TraversalPolicy;
import com.evacuation.engine.graph.structure.GraphSnapshot;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * "Which eligible shelter can this node reach soonest?" — answered by one search, not one search per
 * candidate shelter.
 *
 * <p>The textbook construction is a virtual zero-cost super-sink with an arc from every eligible
 * shelter node, so a single source-to-sink shortest path picks the best shelter implicitly. Adding
 * that arc would mean mutating the CSR, and {@link GraphSnapshot} is immutable by design. The
 * equivalent without touching the graph is a stopping rule: run an ordinary Dijkstra from the source
 * and stop the instant the <em>first</em> eligible shelter node is settled. Dijkstra settles nodes in
 * non-decreasing order of their true shortest distance, so the first eligible node settled is the
 * nearest reachable eligible shelter — the same answer the super-sink would give, for the same
 * reason, with zero graph mutation. Anything settled afterwards is by definition no closer.
 *
 * <p>This is why the search cannot simply delegate to {@link ShortestPathAlgorithm#findPath}: that
 * contract needs a target chosen up front, and here the target is precisely what is being determined.
 * The loop below is otherwise {@link DijkstraShortestPath}'s, discipline for discipline — dense
 * primitive arrays, lazy-deletion queue, the same instantaneous {@link TraversalPolicy} gate, the same
 * free-flow {@code edgeTimeMin} arc cost — differing only in when it stops.
 *
 * <p><strong>Eligibility is entirely the caller's business.</strong> Open status, remaining capacity,
 * medical fit, vehicle vs pedestrian access: all of it arrives as a {@link Predicate} over
 * {@link GraphSnapshot.ShelterRef}, and this class never inspects a shelter beyond asking that
 * predicate and reading the identity of the one it reached. It knows reachability and travel time,
 * not shelter-selection policy. Preference scoring that trades those off — the medical-facility
 * bonus, crowding penalties, capacity-aware assignment — is a later phase's scoring layer sitting
 * above this, and deliberately not smuggled in here, where it would quietly stop being a
 * shortest-path result.
 *
 * <p>Stateless; all scratch is per call, so one bean serves all threads.
 */
@Component
public class MultiTargetShelterSearch {

    /** Sentinel for "no parent node" / "no parent slot" in the predecessor arrays. */
    private static final int UNSET = -1;

    /** One priority-queue entry: a node and the tentative distance it was pushed with. */
    private record Entry(int nodeIndex, double distance) {
    }

    /**
     * The nearest reachable eligible shelter and the path to it.
     *
     * <p>{@code nodePath} runs source-to-shelter inclusive and {@code edgeSlotPath} holds the CSR
     * slots between consecutive entries, so {@code edgeSlotPath.size() == nodePath.size() - 1} on a
     * reachable result. {@code totalCost} is travel time in minutes; {@code totalDistanceKm} is
     * kilometres — separate accumulations, never interchanged.
     *
     * <p>When no eligible shelter is reachable, {@code reachable} is {@code false},
     * {@code shelterId} is {@code -1}, {@code shelterNodeIndex} is {@code -1},
     * {@code shelterName} is {@code null}, {@code totalCost} is {@link Double#POSITIVE_INFINITY},
     * and both lists are empty — <strong>never {@code null}</strong>.
     *
     * @param reachable        whether an eligible shelter was reached
     * @param shelterId        database id of the shelter reached, or {@code -1}
     * @param shelterNodeIndex dense index of the shelter's node, or {@code -1}
     * @param shelterName      name of the shelter reached, or {@code null}
     * @param totalCost        travel time to the shelter in minutes, or {@code POSITIVE_INFINITY}
     * @param totalDistanceKm  summed {@code edgeDistanceKm} over {@code edgeSlotPath}
     * @param nodePath         ordered dense node indices, source to shelter inclusive; empty if unreachable
     * @param edgeSlotPath     ordered CSR slots traversed; empty if unreachable
     */
    public record ShelterPathResult(boolean reachable, long shelterId, int shelterNodeIndex,
                                    String shelterName, double totalCost, double totalDistanceKm,
                                    List<Integer> nodePath, List<Integer> edgeSlotPath) {
    }

    /**
     * Finds the nearest currently-reachable shelter accepted by {@code eligibility}.
     *
     * @param snapshot        the immutable base topology, whose {@code shelters()} are the candidates
     * @param policy          the instantaneous traversability gate consulted on every expansion
     * @param sourceNodeIndex dense index of the node to search from
     * @param eligibility     the caller's shelter filter; only shelters it accepts can be returned
     * @return the nearest eligible shelter and its path, or an unreachable result; never {@code null}
     */
    public ShelterPathResult findNearestEligibleShelter(GraphSnapshot snapshot,
                                                        TraversalPolicy policy,
                                                        int sourceNodeIndex,
                                                        Predicate<GraphSnapshot.ShelterRef> eligibility) {

        // 1. Index the eligible shelters by node, so a settled node is an O(1) "is this a target?".
        //    Two eligible shelters on one node is a ward-scale curiosity; first one wins, and either
        //    answer is equally correct as a nearest-shelter result.
        Map<Integer, GraphSnapshot.ShelterRef> eligibleByNodeIndex = new HashMap<>();
        for (GraphSnapshot.ShelterRef shelter : snapshot.shelters()) {
            if (eligibility.test(shelter)) {
                eligibleByNodeIndex.putIfAbsent(shelter.nodeIndex(), shelter);
            }
        }

        // 2. Nothing to search from, or nothing to search for.
        if (!policy.isNodeUsable(sourceNodeIndex) || eligibleByNodeIndex.isEmpty()) {
            return unreachable();
        }

        // 3. Degenerate query: the source node hosts an eligible shelter.
        GraphSnapshot.ShelterRef shelterAtSource = eligibleByNodeIndex.get(sourceNodeIndex);
        if (shelterAtSource != null) {
            return new ShelterPathResult(true, shelterAtSource.shelterId(), sourceNodeIndex,
                    shelterAtSource.shelterName(), 0.0, 0.0,
                    List.of(sourceNodeIndex), List.of());
        }

        // 4. Per-search scratch, indexed by dense node index.
        int nodeCount = snapshot.nodeCount();
        double[] dist = new double[nodeCount];
        int[] parentNode = new int[nodeCount];
        int[] parentSlot = new int[nodeCount];
        boolean[] settled = new boolean[nodeCount];

        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(parentNode, UNSET);
        Arrays.fill(parentSlot, UNSET);
        dist[sourceNodeIndex] = 0.0;

        PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator.comparingDouble(Entry::distance));
        queue.add(new Entry(sourceNodeIndex, 0.0));

        while (!queue.isEmpty()) {
            Entry entry = queue.poll();
            int node = entry.nodeIndex();
            double distanceToNode = entry.distance();

            // Lazy deletion: already settled, or superseded by a cheaper push of the same node.
            if (settled[node] || distanceToNode != dist[node]) {
                continue;
            }
            settled[node] = true;

            // The stopping rule: settle order is distance order, so the first eligible shelter node
            // to settle is the nearest one — no further expansion can improve on it.
            GraphSnapshot.ShelterRef reached = eligibleByNodeIndex.get(node);
            if (reached != null) {
                return reconstruct(snapshot, reached, dist[node], parentNode, parentSlot);
            }

            // 5. Otherwise expand exactly as the plain point-query Dijkstra does.
            int slotsEnd = snapshot.slotsEnd(node);
            for (int slot = snapshot.slotsStart(node); slot < slotsEnd; slot++) {
                if (!policy.isTraversable(slot)) {
                    continue;
                }
                int to = snapshot.edgeTo(slot);
                if (!policy.isNodeUsable(to)) {
                    continue;
                }
                double candidate = distanceToNode + snapshot.edgeTimeMin(slot);
                if (candidate < dist[to]) {
                    dist[to] = candidate;
                    parentNode[to] = node;
                    parentSlot[to] = slot;
                    queue.add(new Entry(to, candidate));
                }
            }
        }

        // Queue drained with no eligible shelter settled: none is reachable under the current hazards.
        return unreachable();
    }

    /**
     * Walks the predecessors back from the settled shelter node to the source, reversing into
     * source-to-shelter order and summing kilometres alongside the minutes already in {@code dist}.
     */
    private static ShelterPathResult reconstruct(GraphSnapshot snapshot,
                                                 GraphSnapshot.ShelterRef shelter,
                                                 double totalCost,
                                                 int[] parentNode,
                                                 int[] parentSlot) {
        List<Integer> nodePath = new ArrayList<>();
        List<Integer> edgeSlotPath = new ArrayList<>();
        double totalDistanceKm = 0.0;

        for (int node = shelter.nodeIndex(); node != UNSET; node = parentNode[node]) {
            nodePath.add(node);
            int slot = parentSlot[node];
            if (slot != UNSET) {
                edgeSlotPath.add(slot);
                totalDistanceKm += snapshot.edgeDistanceKm(slot);
            }
        }
        Collections.reverse(nodePath);
        Collections.reverse(edgeSlotPath);

        return new ShelterPathResult(true, shelter.shelterId(), shelter.nodeIndex(),
                shelter.shelterName(), totalCost, totalDistanceKm,
                List.copyOf(nodePath), List.copyOf(edgeSlotPath));
    }

    /** The "no eligible shelter reachable" outcome: infinite cost, empty (never {@code null}) paths. */
    private static ShelterPathResult unreachable() {
        return new ShelterPathResult(false, -1L, UNSET, null,
                Double.POSITIVE_INFINITY, 0.0, List.of(), List.of());
    }
}