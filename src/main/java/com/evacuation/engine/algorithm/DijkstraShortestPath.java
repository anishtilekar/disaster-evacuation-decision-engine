package com.evacuation.engine.algorithm;

import com.evacuation.engine.graph.overlay.TraversalPolicy;
import com.evacuation.engine.graph.structure.GraphSnapshot;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Ordinary (non-time-expanded) Dijkstra over the CSR snapshot — the correctness baseline and the
 * "instant, right now" point-query implementation of {@link ShortestPathAlgorithm}.
 *
 * <p>Edge cost here is plain free-flow travel time ({@code edgeTimeMin}), with no risk pricing and no
 * capacity term. That is deliberate rather than unfinished: {@link TraversalPolicy} already gates
 * every expansion on instantaneous traversability, so LETHAL cells are excluded from the search
 * outright and what remains is an all-open graph on which shortest path <em>is</em> shortest time.
 * Pricing RISKY exposure, spreading load across a BPR-shaped congestion cost, and honouring
 * reservations all need a time index on the state — they belong to the time-expanded search of the
 * later phases, not here, where a single scalar per arc keeps Dijkstra's optimality argument trivial.
 *
 * <p>Hence the dual role STRIDE assigns this class: the standalone answer for a simple
 * "route me from A to B as things stand" query, and later the <em>free-flow probe</em> the dispatch
 * ordering runs to estimate how constrained a party is (how few shelters it can still reach) before
 * committing anyone to space-time cells. Both want the same thing — a fast, hazard-feasible,
 * congestion-blind lower bound.
 *
 * <p>The search itself follows the house dense-array discipline: primitive {@code dist}/parent arrays
 * indexed by dense node index, a lazy-deletion priority queue (stale entries are discarded on pop
 * instead of being decrease-keyed), and early termination the moment the target is settled. The
 * instance holds no state, so a single bean is safely shared across threads; all scratch is per call.
 */
@Component
public class DijkstraShortestPath implements ShortestPathAlgorithm {

    /** Sentinel for "no parent node" / "no parent slot" in the predecessor arrays. */
    private static final int UNSET = -1;

    /** One priority-queue entry: a node and the tentative distance it was pushed with. */
    private record Entry(int nodeIndex, double distance) {
    }

    /**
     * Finds the quickest currently-traversable path between two nodes.
     *
     * @param snapshot        the immutable base topology to search
     * @param policy          the instantaneous traversability gate consulted on every expansion
     * @param sourceNodeIndex dense index of the origin node
     * @param targetNodeIndex dense index of the destination node
     * @return the path found, or an unreachable {@link PathResult}; never {@code null}
     */
    @Override
    public PathResult findPath(GraphSnapshot snapshot, TraversalPolicy policy,
                               int sourceNodeIndex, int targetNodeIndex) {

        // 1. A source or target that is not usable right now can never yield a path.
        if (!policy.isNodeUsable(sourceNodeIndex) || !policy.isNodeUsable(targetNodeIndex)) {
            return unreachable(targetNodeIndex);
        }

        // 2. Degenerate query: already standing on the target.
        if (sourceNodeIndex == targetNodeIndex) {
            return new PathResult(true, targetNodeIndex, 0.0, 0.0,
                    List.of(sourceNodeIndex), List.of());
        }

        // 3. Per-search scratch, indexed by dense node index.
        int nodeCount = snapshot.nodeCount();
        double[] dist = new double[nodeCount];
        int[] parentNode = new int[nodeCount];
        int[] parentSlot = new int[nodeCount];
        boolean[] settled = new boolean[nodeCount];

        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(parentNode, UNSET);
        Arrays.fill(parentSlot, UNSET);
        dist[sourceNodeIndex] = 0.0;

        // 4. Min-heap on tentative distance; no decrease-key, stale entries are dropped on pop.
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

            // 5. Target settled ⇒ its distance is final; draining the rest of the queue is wasted work.
            if (node == targetNodeIndex) {
                break;
            }

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

        // 6. Never relaxed ⇒ no open route exists under the current hazard state.
        if (Double.isInfinite(dist[targetNodeIndex])) {
            return unreachable(targetNodeIndex);
        }

        // 7. Walk the predecessors back from the target, accumulating kilometres as we go. This is a
        //    separate sum from dist[], which is in minutes — the two units are never interchanged.
        List<Integer> nodePath = new ArrayList<>();
        List<Integer> edgeSlotPath = new ArrayList<>();
        double totalDistanceKm = 0.0;

        for (int node = targetNodeIndex; node != UNSET; node = parentNode[node]) {
            nodePath.add(node);
            int slot = parentSlot[node];
            if (slot != UNSET) {
                edgeSlotPath.add(slot);
                totalDistanceKm += snapshot.edgeDistanceKm(slot);
            }
        }
        Collections.reverse(nodePath);
        Collections.reverse(edgeSlotPath);

        // 8. Source-to-target order, with cost in minutes and distance in kilometres.
        return new PathResult(true, targetNodeIndex, dist[targetNodeIndex], totalDistanceKm,
                List.copyOf(nodePath), List.copyOf(edgeSlotPath));
    }

    /** The interface's "no path" outcome: infinite cost, empty (never {@code null}) paths. */
    private static PathResult unreachable(int targetNodeIndex) {
        return new PathResult(false, targetNodeIndex, Double.POSITIVE_INFINITY, 0.0,
                List.of(), List.of());
    }
}