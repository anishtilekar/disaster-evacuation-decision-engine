package com.evacuation.engine.sim;

import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.dispatch.ActivePlan;
import com.evacuation.engine.dispatch.DispatchService;
import com.evacuation.engine.dispatch.ImprovementLoop;
import com.evacuation.engine.dispatch.InstructionSet;
import com.evacuation.engine.dispatch.Party;
import com.evacuation.engine.graph.overlay.TraversalPolicy;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimelineCache;
import com.evacuation.engine.graph.time.TimeModel;
import com.evacuation.engine.loader.GraphCache;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 5's A/B harness: one batch of demand, three strategies, the same metrics for each.
 *
 * <p>The comparison is only worth anything if the three runs differ in <em>strategy alone</em>. One
 * demand batch is generated and handed to all three, and the hazard timeline is pinned before any of
 * them runs.
 *
 * <p><strong>The timeline sequencing is load-bearing, and easy to break silently.</strong>
 * {@code DispatchService.ensureSession} recompiles the hazard timeline when it has to start a session
 * itself — so if the STRIDE runs were allowed to trigger that, they would search against a timeline
 * compiled at a different moment than the one independent-Dijkstra saw, and the three sets of numbers
 * would not be measuring the same world. Hence the order in {@link #runComparison}: ensure a timeline
 * exists <em>first</em>, run independent-Dijkstra against it, then call {@code activePlan.reset(...)}
 * before touching {@code DispatchService}. Because that reset leaves a session already active whose
 * ledger matches the current snapshot, {@code ensureSession} takes its reuse branch and never
 * recompiles. Reorder these and nothing throws — the comparison just quietly becomes unfair.
 *
 * <p><strong>Scope: this one comparison only.</strong> Running a perturbation is already a direct
 * composition of {@link DemandGenerator}, {@code DispatchService}, {@link PerturbationScenarios}, and
 * {@link SimulationMetrics}, and its three perturbation methods have three deliberately different
 * signatures. A fourth wrapper forcing them into one shape would add a layer and lose information.
 *
 * <p>Plain constructed research tooling, not a Spring bean — same boundary as the rest of this package.
 */
public final class Simulator {

    private final GraphCache graphCache;
    private final HazardTimelineCache hazardTimelineCache;
    private final ActivePlan activePlan;
    private final DemandGenerator demandGenerator;
    private final IndependentDijkstraStrategy independentDijkstraStrategy;
    private final DispatchService dispatchService;
    private final ImprovementLoop improvementLoop;
    private final TimeModel timeModel;
    private final GraphEngineProperties properties;

    public Simulator(GraphCache graphCache, HazardTimelineCache hazardTimelineCache,
                     ActivePlan activePlan, DemandGenerator demandGenerator,
                     IndependentDijkstraStrategy independentDijkstraStrategy,
                     DispatchService dispatchService, ImprovementLoop improvementLoop,
                     TimeModel timeModel, GraphEngineProperties properties) {
        this.graphCache = graphCache;
        this.hazardTimelineCache = hazardTimelineCache;
        this.activePlan = activePlan;
        this.demandGenerator = demandGenerator;
        this.independentDijkstraStrategy = independentDijkstraStrategy;
        this.dispatchService = dispatchService;
        this.improvementLoop = improvementLoop;
        this.timeModel = timeModel;
        this.properties = properties;
    }

    /**
     * @param independentDijkstra the capacity-blind baseline
     * @param strideGreedy        the dispatch pass alone
     * @param strideWithLns       the <em>same</em> session {@code strideGreedy} was measured from,
     *                            improved further — not a second independent dispatch of the same
     *                            demand. That is deliberate: it answers "what does spending more
     *                            compute after dispatch buy", which is what LNS's anytime framing is
     *                            for, rather than "does some other arrangement of this demand happen
     *                            to score better", which it is not
     * @param lnsResult           what the improvement pass actually ran and accepted
     */
    public record ComparisonResult(SimulationMetrics.CoreMetrics independentDijkstra,
                                   SimulationMetrics.CoreMetrics strideGreedy,
                                   SimulationMetrics.CoreMetrics strideWithLns,
                                   ImprovementLoop.Result lnsResult) {
    }

    /**
     * Generates one demand batch and scores all three strategies against it.
     *
     * @param partyCount         how many parties to generate
     * @param meanGroupSize      Poisson mean for group size
     * @param lnsIterationBudget iterations handed to the improvement pass
     * @param now                the instant the run is anchored at — session epoch, request time, and
     *                           timeline epoch if one must be compiled
     * @return the three strategies' metrics and the LNS pass's own outcome
     */
    public ComparisonResult runComparison(int partyCount, double meanGroupSize,
                                          int lnsIterationBudget, LocalDateTime now) {
        GraphSnapshot snapshot = graphCache.get();

        if (!hazardTimelineCache.isLoaded()) {
            hazardTimelineCache.reload(snapshot, now);
        }
        TraversalPolicy policy = new TraversalPolicy(snapshot, hazardTimelineCache.get());

        List<Party> demand = demandGenerator.generate(snapshot, partyCount, meanGroupSize, now);

        InstructionSet independentResult = independentDijkstraStrategy.plan(demand, snapshot, policy);
        SimulationMetrics.CoreMetrics independentMetrics =
                SimulationMetrics.computeCore(independentResult, policy, timeModel);

        activePlan.reset(snapshot, timeModel, properties, now);

        InstructionSet greedyResult = dispatchService.plan(demand, now);
        SimulationMetrics.CoreMetrics greedyMetrics =
                SimulationMetrics.computeCore(greedyResult, policy, timeModel);

        ImprovementLoop.Result lnsRunResult = improvementLoop.improve(lnsIterationBudget);

        // LNS only ever re-routes already-committed platoons; it never retries a stranded one, an
        // established scope limit of ImprovementLoop itself. So the greedy pass's shortfall list is
        // still exactly accurate here, while the committed side must be re-read from the book.
        InstructionSet lnsResult =
                new InstructionSet(activePlan.planBook().all(), greedyResult.shortfalls());
        SimulationMetrics.CoreMetrics lnsMetrics =
                SimulationMetrics.computeCore(lnsResult, policy, timeModel);

        return new ComparisonResult(independentMetrics, greedyMetrics, lnsMetrics, lnsRunResult);
    }
}