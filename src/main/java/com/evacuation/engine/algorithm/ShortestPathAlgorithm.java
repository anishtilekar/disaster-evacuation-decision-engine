package com.evacuation.engine.algorithm;

import com.evacuation.engine.graph.overlay.TraversalPolicy;
import com.evacuation.engine.graph.structure.GraphSnapshot;

import java.util.List;

/**
 * Strategy contract for every "instant, right now" single-source shortest-path search over a
 * {@link GraphSnapshot} — the point-query answer that {@code DijkstraShortestPath} and
 * {@code AStarShortestPath} each implement.
 *
 * <p>Implementations read topology from the snapshot's dense arrays and CSR adjacency, and consult
 * {@link TraversalPolicy} (its instantaneous, bucket-0 overloads) on every expansion, so hazard state
 * stays decoupled from both the topology and the algorithm. A caller such as {@code RoutingService}
 * therefore picks an implementation without knowing anything about its internals; so does the
 * free-flow probe that STRIDE's dispatch ordering uses to estimate constrainedness, which gets exactly
 * the same {@link PathResult} shape back as an ordinary point query.
 *
 * <p>The contract is deliberately single-target. Multi-target shelter search <em>composes</em> an
 * implementation of this interface via the virtual-super-sink technique rather than extending it, so
 * that this one-source-one-target signature stays simple enough for every plain algorithm to honour.
 *
 * <p>"No path" is a normal outcome here, never an error: implementations return an unreachable
 * {@link PathResult} instead of throwing or returning {@code null}.
 */
public interface ShortestPathAlgorithm {

    /**
     * The outcome of one point query.
     *
     * <p>{@code nodePath} is the ordered dense node indices from source to target inclusive, and
     * {@code edgeSlotPath} the ordered CSR slots traversed between consecutive entries of it, so
     * {@code edgeSlotPath.size() == nodePath.size() - 1} on any reachable result. Slots are recorded
     * rather than {@code edgeDbId}s because a bidirectional road contributes two slots sharing one id;
     * the slot identifies the direction actually travelled, and the database id is one
     * {@code snapshot.edgeDbId(slot)} lookup away.
     *
     * <p>Unreachable convention: {@code reachable} is {@code false}, {@code totalCost} is
     * {@link Double#POSITIVE_INFINITY}, and both lists are empty — <strong>never {@code null}</strong>,
     * so callers may iterate a result without a null check. {@code totalDistanceKm} carries no meaning
     * in that case; implementations report {@code 0.0} so an unreachable result is fully determined by
     * the flag alone. {@code targetNodeIndex} echoes the target the search was asked for either way.
     *
     * @param reachable        whether a path from source to target was found
     * @param targetNodeIndex  dense index of the target this result is about
     * @param totalCost        the algorithm's cost of the path, or {@code POSITIVE_INFINITY} if unreachable
     * @param totalDistanceKm  summed {@code edgeDistanceKm} over {@code edgeSlotPath}
     * @param nodePath         ordered dense node indices, source to target inclusive; empty if unreachable
     * @param edgeSlotPath     ordered CSR slots traversed; empty if unreachable
     */
    record PathResult(boolean reachable, int targetNodeIndex, double totalCost,
                      double totalDistanceKm, List<Integer> nodePath,
                      List<Integer> edgeSlotPath) {
    }

    /**
     * Searches for the cheapest path from one node to another as the world stands right now.
     *
     * <p>A source or target that the policy reports unusable, and a target no traversable sequence of
     * slots reaches, are both unreachable results rather than exceptions — the same convention as any
     * other "no path".
     *
     * @param snapshot        the immutable base topology to search
     * @param policy          the traversability gate consulted on every expansion
     * @param sourceNodeIndex dense index of the origin node
     * @param targetNodeIndex dense index of the destination node
     * @return the path found, or an unreachable {@link PathResult}; never {@code null}
     */
    PathResult findPath(GraphSnapshot snapshot, TraversalPolicy policy,
                        int sourceNodeIndex, int targetNodeIndex);
}