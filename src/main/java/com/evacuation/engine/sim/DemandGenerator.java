package com.evacuation.engine.sim;

import com.evacuation.engine.dispatch.Party;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.model.enums.EvacuationPriority;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds a batch of synthetic {@link Party} requests for the simulation harness — the demand side of
 * the Phase 5 A/B comparison.
 *
 * <p><strong>Determinism is the whole point.</strong> The generator is seeded from a caller-supplied
 * {@code long} and the party count is caller-specified rather than itself random, so one seed always
 * yields one exact batch. That is what lets the harness push the <em>identical</em> demand through
 * independent-Dijkstra, STRIDE-greedy, and STRIDE+LNS and attribute every difference in outcome to
 * the planner rather than to luck of the draw.
 *
 * <p><strong>Documented simplifications</strong>, stated as such rather than dressed up as fidelity:
 * <ul>
 *   <li><strong>Origins are uniform-random over active nodes only.</strong> The design's
 *       "population-weighted Poisson demand" is deliberately not implemented: this project holds no
 *       population-density data for the ward, so weighting would be an invented distribution
 *       masquerading as a measured one. Restricting to {@code nodeActive} nodes is not arbitrary,
 *       though — an inactive node's every cell is LETHAL across the whole compiled horizon by
 *       construction, so demand generated there would manufacture a guaranteed shortfall that tests
 *       nothing about the planner being compared.</li>
 *   <li><strong>Priority is uniform across all four {@link EvacuationPriority} levels</strong> — an
 *       honest arbitrary choice, with no real distribution to calibrate against.</li>
 *   <li><strong>Medical assistance is a flat 10% draw</strong>, arbitrary in the same way.</li>
 *   <li><strong>Group size is Poisson</strong> about a caller-supplied mean, floored at 1, drawn by
 *       Knuth's algorithm so no statistics dependency is added.</li>
 * </ul>
 *
 * <p>Plain constructed research tooling, not a Spring bean — same non-production boundary as
 * {@link IndependentDijkstraStrategy}.
 */
public final class DemandGenerator {

    private static final double MEDICAL_ASSISTANCE_PROBABILITY = 0.10;

    private final Random random;

    public DemandGenerator(long seed) {
        this.random = new Random(seed);
    }

    /**
     * @param snapshot      the graph whose active nodes origins are drawn from
     * @param partyCount    how many parties to generate; fixed, never random
     * @param meanGroupSize the Poisson mean for group size
     * @param requestedAt   shared by every generated party — this baseline demand arrives all at once
     * @return the generated parties, with ids {@code 1..partyCount}
     * @throws IllegalStateException if the snapshot has no active nodes to draw origins from
     */
    public List<Party> generate(GraphSnapshot snapshot, int partyCount, double meanGroupSize,
                                LocalDateTime requestedAt) {
        if (partyCount <= 0) {
            throw new IllegalArgumentException("partyCount must be > 0, got " + partyCount);
        }
        if (meanGroupSize <= 0) {
            throw new IllegalArgumentException("meanGroupSize must be > 0, got " + meanGroupSize);
        }

        List<Integer> activeNodes = new ArrayList<>();
        for (int i = 0; i < snapshot.nodeCount(); i++) {
            if (snapshot.nodeActive(i)) {
                activeNodes.add(i);
            }
        }
        if (activeNodes.isEmpty()) {
            throw new IllegalStateException(
                    "Snapshot has no active nodes — no meaningful demand can be generated from it");
        }

        EvacuationPriority[] priorities = EvacuationPriority.values();
        List<Party> parties = new ArrayList<>(partyCount);

        for (int i = 0; i < partyCount; i++) {
            parties.add(new Party(
                    i + 1L,
                    activeNodes.get(random.nextInt(activeNodes.size())),
                    Math.max(1, nextPoisson(meanGroupSize)),
                    priorities[random.nextInt(priorities.length)],
                    random.nextDouble() < MEDICAL_ASSISTANCE_PROBABILITY,
                    requestedAt));
        }
        return List.copyOf(parties);
    }

    /**
     * Knuth's method: multiply uniform draws until their product falls below {@code e^-mean}; the
     * number of draws less one is Poisson(mean). Exact and dependency-free at the means this harness
     * uses — it degrades (the threshold underflows to zero, and the loop never terminates) only for
     * means far larger than any plausible evacuation group size.
     */
    private int nextPoisson(double mean) {
        double threshold = Math.exp(-mean);
        double product = 1.0;
        int count = 0;
        do {
            count++;
            product *= random.nextDouble();
        } while (product > threshold);
        return count - 1;
    }
}