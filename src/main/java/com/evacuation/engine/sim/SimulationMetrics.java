package com.evacuation.engine.sim;

import com.evacuation.engine.algorithm.spacetime.TimedWalk;
import com.evacuation.engine.dispatch.DispatchResult;
import com.evacuation.engine.dispatch.InstructionSet;
import com.evacuation.engine.graph.overlay.TraversalPolicy;
import com.evacuation.engine.graph.time.TimeModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Phase 5 measurement layer: two independent static computations over a plan snapshot — the
 * per-plan quality figures, and the churn diff between two snapshots. (Replan latency is timed by
 * whoever calls {@code RepairService} and has no place here.)
 *
 * <p><strong>Deliberately not a single "build the report" method.</strong> What a full comparison
 * report should contain depends on the scenario: a one-shot A/B across three strategies wants the
 * core figures three times and no churn at all; a before/after perturbation wants churn as the
 * headline. Bundling those into one entry point would force every caller to accept some other
 * scenario's shape, so this class computes the two pieces and leaves the assembly to the harness.
 *
 * <p><strong>Why exposure is recomputed here instead of read off {@link TimedWalk#totalCost()}.</strong>
 * {@code TimeExpandedDijkstra} prices {@code λ · exposure} into an arc's cost at the moment it relaxes
 * it — the same fact {@code LexicographicObjective} relies on to avoid double-counting at its
 * weighted-person-minutes level. That pricing is one-way: once risk is folded into a scalar total
 * alongside travel seconds, exposure alone cannot be recovered from it. The cleaner fix would be to
 * carry exposure as a second running total through the search and out on {@code TimedWalk}, but that
 * means editing the core search's already-verified internals for a metrics-only need — not a trade
 * worth making. So the exposure formula ({@code riskAt(slot, bucket) · Δ}, summed over each movement
 * step's occupied buckets) is duplicated here: a deliberate, small, contained copy whose entire
 * purpose is leaving the production search path untouched. If the search ever does start tracking
 * exposure natively, this is the code that should be deleted in favour of it.
 */
public final class SimulationMetrics {

    private SimulationMetrics() {
    }

    /**
     * @param makespanBucket           the latest arrival bucket in the plan; 0 if nothing is committed
     * @param meanWeightedPersonMinutes  mean over committed platoons of {@code totalCost · size / 60}
     * @param p95WeightedPersonMinutes the same distribution's 95th percentile, nearest-rank
     * @param totalExposureSeconds     summed person-independent exposure across every movement step
     * @param strandedCount            how many parties came up short
     * @param strandedPeople           how many people those shortfalls account for
     */
    public record CoreMetrics(int makespanBucket, double meanWeightedPersonMinutes,
                              double p95WeightedPersonMinutes, double totalExposureSeconds,
                              int strandedCount, int strandedPeople) {
    }

    /**
     * Scores one plan.
     *
     * <p>P95 is the <em>nearest-rank</em> percentile over the ascending-sorted per-platoon values:
     * {@code index = ceil(0.95 · n) - 1}, clamped into {@code [0, n-1]}. "Percentile" has no single
     * universal definition, so this project fixes one and states it rather than leaving a reader to
     * infer which convention a number came from.
     *
     * <p>An empty committed list yields zero makespan and zero distribution stats — vacuously true,
     * matching {@code LexicographicObjective}'s own empty-plan convention. Shortfall figures are
     * computed regardless, since a plan that committed nothing is exactly the case where they matter.
     */
    public static CoreMetrics computeCore(InstructionSet instructions, TraversalPolicy policy,
                                          TimeModel timeModel) {
        int strandedCount = instructions.shortfalls().size();
        int strandedPeople = instructions.shortfalls().stream()
                .mapToInt(InstructionSet.Shortfall::unroutedSize).sum();

        List<DispatchResult> committed = instructions.committed();
        if (committed.isEmpty()) {
            return new CoreMetrics(0, 0.0, 0.0, 0.0, strandedCount, strandedPeople);
        }

        int deltaSeconds = timeModel.deltaSeconds();
        int makespanBucket = 0;
        double totalExposureSeconds = 0.0;
        List<Double> weightedPersonMinutes = new ArrayList<>(committed.size());

        for (DispatchResult result : committed) {
            TimedWalk walk = result.searchResult().walk();
            makespanBucket = Math.max(makespanBucket, walk.arrival().bucket());
            weightedPersonMinutes.add(walk.totalCost() * result.size() / 60.0);

            for (TimedWalk.Step step : walk.steps()) {
                if (step.isWait()) {
                    continue;
                }
                for (int b = step.from().bucket(); b < step.to().bucket(); b++) {
                    totalExposureSeconds += policy.riskAt(step.edgeSlot(), b) * deltaSeconds;
                }
            }
        }

        weightedPersonMinutes.sort(null);
        int n = weightedPersonMinutes.size();
        double mean = weightedPersonMinutes.stream().mapToDouble(Double::doubleValue).sum() / n;
        int p95Index = Math.min(n - 1, Math.max(0, (int) Math.ceil(0.95 * n) - 1));

        return new CoreMetrics(makespanBucket, mean, weightedPersonMinutes.get(p95Index),
                totalExposureSeconds, strandedCount, strandedPeople);
    }

    /**
     * How many platoons' instructions changed between two <em>full</em> plan snapshots — e.g. two
     * {@code PlanBook.all()} calls straddling an event.
     *
     * <p>Full snapshots, not the {@link InstructionSet} a repair returns: that set is already scoped
     * to exactly what the repair touched, so diffing it against anything would report "everything
     * that changed, changed" and measure nothing. Churn is only meaningful as a fraction of the whole
     * plan, which requires seeing the whole plan on both sides.
     *
     * <p>A platoon churns if it is present in only one snapshot (appeared or disappeared), or present
     * in both with an unequal {@link DispatchResult} — record equality reaches structurally all the
     * way down through the walk, and every other field of a repaired result is preserved by design,
     * so an inequality is precisely a changed route.
     *
     * @return the number of platoon ids, over the union of both snapshots, that churned
     */
    public static int instructionChurn(List<DispatchResult> before, List<DispatchResult> after) {
        Map<Long, DispatchResult> beforeById = indexByPlatoon(before);
        Map<Long, DispatchResult> afterById = indexByPlatoon(after);

        Set<Long> allIds = new HashSet<>(beforeById.keySet());
        allIds.addAll(afterById.keySet());

        int churned = 0;
        for (Long platoonId : allIds) {
            if (!Objects.equals(beforeById.get(platoonId), afterById.get(platoonId))) {
                churned++;
            }
        }
        return churned;
    }

    private static Map<Long, DispatchResult> indexByPlatoon(List<DispatchResult> results) {
        Map<Long, DispatchResult> byId = new HashMap<>(results.size() * 2);
        for (DispatchResult result : results) {
            byId.put(result.platoonId(), result);
        }
        return byId;
    }
}