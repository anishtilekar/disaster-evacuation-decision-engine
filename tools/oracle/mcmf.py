"""A standalone, dependency-free minimum-cost maximum-flow solver.

Standard library only, by design: this tool exists specifically to measure STRIDE's optimality
gap without pulling in an LP or graph library, keeping the same "no new dependencies" discipline
the rest of this project follows even though this script lives entirely outside the Java app and
nothing here could ever affect it.

Implements successive shortest augmenting paths with SPFA (a queue-based Bellman-Ford) as the
shortest-path subroutine. Dijkstra is not an option on its own: residual edges carry negative cost
(the reverse of every positive-cost forward edge), which plain Dijkstra does not handle correctly.
SPFA does, and at the instance sizes this tool is meant for (at most a few thousand time-expanded
states) its worst-case cost is a non-issue. Run to completion, successive shortest augmenting paths
finds a flow that is simultaneously maximum and, among all maximum flows, minimum-cost -- exactly
the two-part answer "how many people can be placed, at what minimum total cost" this tool needs,
with no separate maximize-then-minimize phase required.
"""

from collections import deque


class MinCostFlow:
    """A directed graph over dense integer node ids, built up via add_edge before solving.

    Edges are stored in forward/backward pairs at indices (2k, 2k+1): edge i's reverse is always
    edge i XOR 1. This is the standard trick that lets residual-graph bookkeeping stay a single
    array mutation per augmentation, with no separate reverse-lookup structure.
    """

    def __init__(self, node_count):
        self.node_count = node_count
        self.adjacency = [[] for _ in range(node_count)]
        # Each edge: [to, remaining_capacity, cost]. The reverse edge starts at 0 capacity and
        # negative cost, and only ever gains capacity as flow is pushed along the forward edge.
        self.edges = []

    def add_edge(self, u, v, capacity, cost):
        if capacity < 0:
            raise ValueError(f"capacity must be >= 0, got {capacity}")
        self.adjacency[u].append(len(self.edges))
        self.edges.append([v, capacity, cost])
        self.adjacency[v].append(len(self.edges))
        self.edges.append([u, 0, -cost])

    def _shortest_path(self, source, sink):
        """SPFA from source; returns (dist, prev_edge) or None if sink is unreachable."""
        inf = float("inf")
        dist = [inf] * self.node_count
        prev_edge = [-1] * self.node_count
        in_queue = [False] * self.node_count

        dist[source] = 0
        queue = deque([source])
        in_queue[source] = True

        while queue:
            u = queue.popleft()
            in_queue[u] = False
            du = dist[u]
            for edge_id in self.adjacency[u]:
                v, capacity, cost = self.edges[edge_id]
                if capacity > 0 and du + cost < dist[v]:
                    dist[v] = du + cost
                    prev_edge[v] = edge_id
                    if not in_queue[v]:
                        queue.append(v)
                        in_queue[v] = True

        if dist[sink] == inf:
            return None
        return dist, prev_edge

    def min_cost_max_flow(self, source, sink):
        """Runs successive shortest augmenting paths to completion.

        @return (total_flow, total_cost) -- the maximum flow achievable from source to sink, and
                the minimum cost among all flows of that value.
        """
        total_flow = 0
        total_cost = 0

        while True:
            result = self._shortest_path(source, sink)
            if result is None:
                break
            dist, prev_edge = result

            # Bottleneck capacity along the discovered path.
            push = float("inf")
            v = sink
            while v != source:
                edge_id = prev_edge[v]
                push = min(push, self.edges[edge_id][1])
                v = self.edges[edge_id ^ 1][0]

            v = sink
            while v != source:
                edge_id = prev_edge[v]
                self.edges[edge_id][1] -= push
                self.edges[edge_id ^ 1][1] += push
                v = self.edges[edge_id ^ 1][0]

            total_flow += push
            total_cost += push * dist[sink]

        return total_flow, total_cost
