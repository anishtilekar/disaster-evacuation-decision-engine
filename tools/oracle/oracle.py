"""STRIDE's optimality-gap oracle.

Builds small (<=50-node), hand-crafted time-expanded instances, solves each one exactly via the
min-cost-flow solver in mcmf.py, and separately runs a faithful (but deliberately simplified) port
of STRIDE's own sequential, capacity-aware dispatch loop against the identical instance. Reporting
the two side by side is the whole point: STRIDE is a greedy heuristic and is not claimed to be
globally optimal (the design document says so explicitly -- sequential dispatch is greedy, and the
underlying problem contains NP-complete integral multicommodity flow). This tool turns that
acknowledged gap into a measured number instead of leaving it as an assertion.

Deliberately simplified relative to the real engine, stated plainly rather than silently dropped:
  - No priority classes or anti-starvation elevation. Parties are dispatched largest-first, a
    single, arbitrary, documented tie-break -- the comparison is about capacity-aware routing
    quality, not about reproducing the real dispatch order.
  - No exposure pricing (RISKY cells, the lambda*risk term). Every arc is either usable or
    LETHAL-and-therefore-absent from the network; there is no continuous risk cost to price.
  - No beta safety margin after a hazard-adjacent arc. LETHAL is a hard "occupying this cell at
    this bucket is forbidden", with no trailing buffer required beyond that bucket.
  - No capacity headroom (eta). Both the oracle and the greedy port plan against each arc's and
    node's full nominal capacity.
  - All parties are eligible for every open shelter. The real engine supports per-party
    eligibility filters; modelling that in a single-commodity min-cost-flow would require a
    multi-commodity formulation this tool does not need for the scenarios it is built to compare.

Every simplification above applies identically to BOTH the oracle and the greedy port, so the gap
measured between them is not an artefact of one side being modelled more richly than the other --
it isolates exactly what the plan asks for: how far a sequential, greedy dispatch heuristic falls
short of the true optimum on the same instance.
"""

from dataclasses import dataclass, field
import heapq

from mcmf import MinCostFlow

LARGE_CAPACITY = 10**9


@dataclass(frozen=True)
class Edge:
    id: str
    from_node: str
    to_node: str
    tau: int  # buckets to traverse
    capacity: float  # persons per bucket
    lethal_from: int | None = None  # first bucket at which this edge becomes and stays LETHAL


@dataclass(frozen=True)
class Shelter:
    node: str
    capacity: int


@dataclass(frozen=True)
class Party:
    id: str
    origin: str
    size: int
    departure_bucket: int = 0


@dataclass(frozen=True)
class Instance:
    name: str
    nodes: list[str]
    edges: list[Edge]
    shelters: list[Shelter]
    parties: list[Party]
    horizon: int
    node_capacity: dict[str, float] = field(default_factory=dict)
    node_lethal_from: dict[str, int] = field(default_factory=dict)

    def total_demand(self) -> int:
        return sum(p.size for p in self.parties)


# --- The oracle: exact min-cost-flow over the time-expanded network ---

def _state_id(node_index, horizon, node, bucket):
    return node_index[node] * horizon + bucket


def solve_oracle(instance: Instance):
    """The exact answer: maximum people placed, and the minimum total person-bucket cost of
    placing that many -- both properties of a single min-cost-max-flow run to completion.
    """
    horizon = instance.horizon
    node_index = {n: i for i, n in enumerate(instance.nodes)}
    state_count = len(instance.nodes) * horizon

    source = state_count
    sink = state_count + 1
    collector_of = {}
    next_id = state_count + 2
    for shelter in instance.shelters:
        collector_of[shelter.node] = next_id
        next_id += 1
    total_node_count = next_id

    flow = MinCostFlow(total_node_count)

    for edge in instance.edges:
        for departure in range(horizon - edge.tau + 1):
            arrival = departure + edge.tau
            if edge.lethal_from is not None and arrival > edge.lethal_from:
                continue
            u = _state_id(node_index, horizon, edge.from_node, departure)
            v = _state_id(node_index, horizon, edge.to_node, arrival)
            flow.add_edge(u, v, edge.capacity, edge.tau)

    for node in instance.nodes:
        capacity = instance.node_capacity.get(node, LARGE_CAPACITY)
        lethal_from = instance.node_lethal_from.get(node)
        for bucket in range(horizon - 1):
            if lethal_from is not None and bucket + 1 >= lethal_from:
                continue
            u = _state_id(node_index, horizon, node, bucket)
            v = _state_id(node_index, horizon, node, bucket + 1)
            flow.add_edge(u, v, capacity, 1)

    for party in instance.parties:
        v = _state_id(node_index, horizon, party.origin, party.departure_bucket)
        flow.add_edge(source, v, party.size, 0)

    for shelter in instance.shelters:
        collector = collector_of[shelter.node]
        for bucket in range(horizon):
            u = _state_id(node_index, horizon, shelter.node, bucket)
            flow.add_edge(u, collector, LARGE_CAPACITY, 0)
        flow.add_edge(collector, sink, shelter.capacity, 0)

    placed, total_cost = flow.min_cost_max_flow(source, sink)
    return {"placed": placed, "total_cost": total_cost}


# --- The greedy port: a faithful, simplified reproduction of STRIDE's own dispatch loop ---

def _edges_from(instance: Instance):
    by_node: dict[str, list[Edge]] = {n: [] for n in instance.nodes}
    for edge in instance.edges:
        by_node[edge.from_node].append(edge)
    return by_node


def _time_expanded_dijkstra(instance, edges_from, party, occupancy_edge, occupancy_node,
                            shelter_remaining):
    """One party's cheapest feasible walk against the CURRENT shared occupancy -- the same
    (v, b) virtual-state search TimeExpandedDijkstra.java runs, minus exposure pricing and the
    beta margin (see the module docstring).
    """
    horizon = instance.horizon
    shelter_by_node = {s.node: s for s in instance.shelters}

    start = (party.origin, party.departure_bucket)
    dist = {start: 0}
    prev = {}
    queue = [(0, start)]

    best_sink_cost = float("inf")
    best_sink_state = None
    best_shelter = None

    while queue:
        cost, state = heapq.heappop(queue)
        if cost > dist.get(state, float("inf")):
            continue
        if cost >= best_sink_cost:
            break
        node, bucket = state

        shelter = shelter_by_node.get(node)
        if shelter is not None and shelter_remaining[shelter.node] >= party.size:
            if cost < best_sink_cost:
                best_sink_cost = cost
                best_sink_state = state
                best_shelter = shelter

        if bucket + 1 < horizon:
            lethal_from = instance.node_lethal_from.get(node)
            if lethal_from is None or bucket + 1 < lethal_from:
                capacity = instance.node_capacity.get(node, LARGE_CAPACITY)
                if occupancy_node.get((node, bucket + 1), 0) + party.size <= capacity:
                    next_state = (node, bucket + 1)
                    next_cost = cost + 1
                    if next_cost < dist.get(next_state, float("inf")):
                        dist[next_state] = next_cost
                        prev[next_state] = (state, None)
                        heapq.heappush(queue, (next_cost, next_state))

        for edge in edges_from[node]:
            arrival = bucket + edge.tau
            if arrival > horizon:
                continue
            if edge.lethal_from is not None and arrival > edge.lethal_from:
                continue
            feasible = all(
                occupancy_edge.get((edge.id, bb), 0) + party.size <= edge.capacity
                for bb in range(bucket, arrival)
            )
            if not feasible:
                continue
            next_state = (edge.to_node, arrival)
            next_cost = cost + edge.tau
            if next_cost < dist.get(next_state, float("inf")):
                dist[next_state] = next_cost
                prev[next_state] = (state, edge)
                heapq.heappush(queue, (next_cost, next_state))

    if best_sink_state is None:
        return None

    steps = []
    state = best_sink_state
    while state != start:
        from_state, edge = prev[state]
        steps.append((from_state, state, edge))
        state = from_state
    steps.reverse()
    return {"cost": best_sink_cost, "steps": steps, "shelter": best_shelter}


def solve_greedy(instance: Instance):
    """STRIDE-greedy's own answer on the identical instance: sequential, capacity-respecting,
    largest-party-first (see the module docstring for why that tie-break and no other).
    """
    edges_from = _edges_from(instance)
    occupancy_edge: dict[tuple[str, int], float] = {}
    occupancy_node: dict[tuple[str, int], float] = {}
    shelter_remaining = {s.node: s.capacity for s in instance.shelters}

    placed = 0
    total_cost = 0.0
    stranded = []

    for party in sorted(instance.parties, key=lambda p: -p.size):
        result = _time_expanded_dijkstra(
            instance, edges_from, party, occupancy_edge, occupancy_node, shelter_remaining)
        if result is None:
            stranded.append(party)
            continue

        for from_state, to_state, edge in result["steps"]:
            if edge is None:
                node, bucket = to_state
                key = (node, bucket)
                occupancy_node[key] = occupancy_node.get(key, 0) + party.size
            else:
                for bucket in range(from_state[1], to_state[1]):
                    key = (edge.id, bucket)
                    occupancy_edge[key] = occupancy_edge.get(key, 0) + party.size

        shelter_remaining[result["shelter"].node] -= party.size
        placed += party.size
        total_cost += result["cost"] * party.size

    return {"placed": placed, "total_cost": total_cost, "stranded": [p.id for p in stranded]}


# --- Instances ---

def bridge_funnel_instance() -> Instance:
    """A narrow, fast crossing and a wide, slow detour -- the same shape as the Java engine's own
    bridge-funnel exit test, expressed as a from-scratch instance here rather than imported, since
    this tool has no access to the Java app at all.
    """
    edges = [
        Edge(id="narrow", from_node="origin", to_node="shelter_node", tau=2, capacity=25),
        Edge(id="wide", from_node="origin", to_node="shelter_node", tau=8, capacity=100),
    ]
    parties = [Party(id=f"party-{i}", origin="origin", size=25) for i in range(12)]
    return Instance(
        name="bridge-funnel",
        nodes=["origin", "shelter_node"],
        edges=edges,
        shelters=[Shelter(node="shelter_node", capacity=sum(p.size for p in parties))],
        parties=parties,
        horizon=40,
    )


def hazard_detour_instance() -> Instance:
    """A cheap direct route that a hazard makes LETHAL from bucket 3 onward, and a costlier
    detour that stays open -- checks that both solvers correctly route around a predicted hazard
    rather than merely around congestion.
    """
    edges = [
        Edge(id="direct", from_node="origin", to_node="shelter_node", tau=2, capacity=50,
             lethal_from=3),
        Edge(id="to_detour", from_node="origin", to_node="detour", tau=1, capacity=50),
        Edge(id="from_detour", from_node="detour", to_node="shelter_node", tau=4, capacity=50),
    ]
    parties = [Party(id=f"party-{i}", origin="origin", size=10) for i in range(5)]
    return Instance(
        name="hazard-detour",
        nodes=["origin", "shelter_node", "detour"],
        edges=edges,
        shelters=[Shelter(node="shelter_node", capacity=sum(p.size for p in parties))],
        parties=parties,
        horizon=30,
    )


def tight_shelter_instance() -> Instance:
    """Room enough on the roads for everyone, but not at the one shelter -- isolates the
    feasible-count comparison from the routing-cost comparison.
    """
    edges = [Edge(id="only_road", from_node="origin", to_node="shelter_node", tau=1, capacity=1000)]
    parties = [Party(id=f"party-{i}", origin="origin", size=10) for i in range(6)]
    return Instance(
        name="tight-shelter",
        nodes=["origin", "shelter_node"],
        edges=edges,
        shelters=[Shelter(node="shelter_node", capacity=40)],
        parties=parties,
        horizon=20,
    )


# --- Reporting ---

def report(instance: Instance):
    oracle = solve_oracle(instance)
    greedy = solve_greedy(instance)
    demand = instance.total_demand()

    # Self-verifying, not just self-reporting: the oracle is provably the cheapest way to place
    # its own placed-count, so a greedy result placing the same count can never cost less, and
    # greedy can never place more than the true maximum. Either failing would mean a bug in one
    # of the two solvers, not a surprising-but-valid outcome -- so this is an assertion, not a
    # printed comparison a reader has to eyeball.
    assert greedy["placed"] <= oracle["placed"], (
        f"{instance.name}: greedy placed {greedy['placed']} > oracle's maximum {oracle['placed']}")
    if greedy["placed"] == oracle["placed"]:
        assert greedy["total_cost"] >= oracle["total_cost"] - 1e-9, (
            f"{instance.name}: greedy cost {greedy['total_cost']} undercuts the oracle's proven "
            f"minimum {oracle['total_cost']} at equal placement count")

    gap_percent = (
        100.0 * (greedy["total_cost"] - oracle["total_cost"]) / oracle["total_cost"]
        if oracle["total_cost"] > 0 else 0.0
    )

    print(f"=== {instance.name} ===")
    print(f"  demand:            {demand}")
    print(f"  oracle placed:     {oracle['placed']}")
    print(f"  greedy placed:     {greedy['placed']}"
          + (f"  (stranded: {greedy['stranded']})" if greedy["stranded"] else ""))
    print(f"  oracle cost:       {oracle['total_cost']:.1f} person-buckets")
    print(f"  greedy cost:       {greedy['total_cost']:.1f} person-buckets")
    print(f"  optimality gap:    {gap_percent:.1f}%")
    print()


def main():
    for instance in (bridge_funnel_instance(), hazard_detour_instance(), tight_shelter_instance()):
        report(instance)


if __name__ == "__main__":
    main()
