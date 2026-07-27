package com.evacuation.engine.algorithm;

import com.evacuation.engine.graph.overlay.TraversalPolicy;
import com.evacuation.engine.graph.structure.GraphSnapshot;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Goal-directed point-to-point search — {@link DijkstraShortestPath}'s answer, reached after far
 * fewer expansions, by ordering the frontier on {@code f = g + h} instead of {@code g} alone.
 *
 * <p>Everything that determines <em>which</em> path is optimal is identical to the Dijkstra
 * implementation: the same instantaneous {@link TraversalPolicy} gate, the same non-negative
 * free-flow {@code edgeTimeMin} arc cost, and no risk pricing or capacity term (those need a time
 * index on the state and belong to the time-expanded search of the later phases). Only the priority
 * ordering differs, so the two are interchangeable behind {@link ShortestPathAlgorithm} and any
 * disagreement between them on the same snapshot is a bug in one of them, not a modelling choice.
 *
 * <p>The {@code dist} array still holds true g-scores — accumulated travel time from the source — and
 * the heuristic appears only in the queue key. That separation is what keeps the result exact: adding
 * {@code h} to the priority reorders <em>when</em> nodes are expanded, never <em>what</em> a path
 * costs.
 *
 * <p>Early termination on popping the target is the whole point of A*, and it is sound here precisely
 * because {@link HaversineHeuristic} is admissible and expressed in the same unit as the cost. When
 * the target is popped with key {@code f = g(target) + 0}, every remaining queue entry has
 * {@code f = g + h <= g + true remaining time}, so no unexplored path could still reach the target
 * for less than {@code g(target)}; the target's g-score is therefore already final and the queue can
 * be abandoned unexamined. A heuristic in kilometres, or one that overestimated by even a little,
 * would break exactly that inequality and let the search stop on a suboptimal path — which is why the
 * admissibility argument lives in {@code HaversineHeuristic}'s configuration contract rather than
 * being assumed here.
 *
 * <p>Stateless apart from the injected heuristic; all scratch is per call, so one bean serves all
 * threads.
 */
@Component
@RequiredArgsConstructor
public class AStarShortestPath implements ShortestPathAlgorithm {

    /** Sentinel for "no parent node" / "no parent slot" in the predecessor arrays. */
    private static final int UNSET = -1;

    /**
     * One priority-queue entry: a node, the g-score it was pushed with (checked against {@code dist}
     * for staleness), and the {@code f = g + h} key the heap orders on.
     */
    private record Entry(int nodeIndex, double gScore, double fScore) {
    }

    private final HaversineHeuristic heuristic;

    /**
     * Finds the quickest currently-traversable path between two nodes, guided toward the target.
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

        // 3. Per-search scratch, indexed by dense node index; dist holds g-scores, not f-scores.
        int nodeCount = snapshot.nodeCount();
        double[] dist = new double[nodeCount];
        int[] parentNode = new int[nodeCount];
        int[] parentSlot = new int[nodeCount];
        boolean[] settled = new boolean[nodeCount];

        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(parentNode, UNSET);
        Arrays.fill(parentSlot, UNSET);
        dist[sourceNodeIndex] = 0.0;

        // 4. Min-heap on f; no decrease-key, stale entries are dropped on pop.
        PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator.comparingDouble(Entry::fScore));
        queue.add(new Entry(sourceNodeIndex, 0.0,
                heuristic.estimateTimeMinutes(snapshot, sourceNodeIndex, targetNodeIndex)));

        while (!queue.isEmpty()) {
            Entry entry = queue.poll();
            int node = entry.nodeIndex();
            double gScore = entry.gScore();

            // Lazy deletion: already settled, or superseded by a cheaper push of the same node.
            if (settled[node] || gScore != dist[node]) {
                continue;
            }
            settled[node] = true;

            // 5. Admissible heuristic ⇒ the popped target's g-score is already final.
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
                double candidate = gScore + snapshot.edgeTimeMin(slot);
                if (candidate < dist[to]) {
                    dist[to] = candidate;
                    parentNode[to] = node;
                    parentSlot[to] = slot;
                    // h is evaluated for the node being pushed, never carried over from its parent.
                    double estimateToTarget =
                            heuristic.estimateTimeMinutes(snapshot, to, targetNodeIndex);
                    queue.add(new Entry(to, candidate, candidate + estimateToTarget));
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