package com.evacuation.engine.sim;

import com.evacuation.engine.algorithm.spacetime.SearchResult;
import com.evacuation.engine.algorithm.spacetime.SpaceTimeState;
import com.evacuation.engine.algorithm.spacetime.TimeExpandedDijkstra;
import com.evacuation.engine.algorithm.spacetime.TimedWalk;
import com.evacuation.engine.dispatch.ActivePlan;
import com.evacuation.engine.dispatch.DispatchResult;
import com.evacuation.engine.dispatch.DispatchService;
import com.evacuation.engine.dispatch.InstructionSet;
import com.evacuation.engine.dispatch.PlanBook;
import com.evacuation.engine.dispatch.RepairService;
import com.evacuation.engine.dispatch.ReservationLedger;
import com.evacuation.engine.graph.overlay.TraversalPolicy;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimeline;
import com.evacuation.engine.graph.time.HazardTimelineCache;
import com.evacuation.engine.graph.time.TimeModel;
import com.evacuation.engine.model.enums.ShelterStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Phase 5's perturbation replay: applies one named disruption to the <em>current</em> dispatch
 * session and reports what repairing it cost. The A/B comparison measures plans; this
 * measures the engine's dynamic behaviour — the claim that STRIDE repairs locally rather than
 * replanning the world.
 *
 * <p><strong>What "hazard arrives early" means here, and where the boundary sits.</strong> This
 * class neither constructs nor persists an adjusted {@code HazardEvent}. Arranging for the hazard
 * data to already reflect the earlier onset is the caller's job — done through whatever
 * {@code HazardEventRepository} the injected {@link HazardTimelineCache}'s compiler reads from,
 * typically a mock returning the adjusted row, the pattern every test in this project already uses.
 * This method picks up from there: capture the timeline as it stood, recompile (which is the moment
 * the caller's adjusted data takes effect), diff old against new, and hand {@link RepairService}
 * exactly the cells that became lethal.
 *
 * <p><strong>Why the affected set is diffed rather than derived.</strong> A hazard's footprint could
 * in principle be recomputed from its geometry, but that would mean reimplementing the compiler's
 * distance-from-front reasoning here and hoping the two agree — a second source of truth for the one
 * question that decides which platoons get replanned. Diffing two full compiled states instead asks
 * the timeline what actually changed, the same discipline
 * {@link SimulationMetrics#instructionChurn(List, List)} documents for churn.
 *
 * <p>All three of the plan's perturbations are implemented here: hazard-arrives-early,
 * shelter-closing, and slow-platoons.
 */
public final class PerturbationScenarios {

    private final ActivePlan activePlan;
    private final HazardTimelineCache hazardTimelineCache;
    private final RepairService repairService;
    private final TimeModel timeModel;
    private final TimeExpandedDijkstra timeExpandedDijkstra;

    public PerturbationScenarios(ActivePlan activePlan, HazardTimelineCache hazardTimelineCache,
                                 RepairService repairService, TimeModel timeModel,
                                 TimeExpandedDijkstra timeExpandedDijkstra) {
        this.activePlan = activePlan;
        this.hazardTimelineCache = hazardTimelineCache;
        this.repairService = repairService;
        this.timeModel = timeModel;
        this.timeExpandedDijkstra = timeExpandedDijkstra;
    }

    /**
     * @param repairResult       what the repair pass replanned and whom it could not place
     * @param replanLatencyNanos wall-clock time of the {@code onEvent} call alone — the recompile and
     *                           the diff are this harness's own setup cost, not the engine's reaction
     *                           time, and folding them in would overstate the metric the plan names
     * @param instructionChurn   how many platoons' instructions changed across the whole plan
     */
    public record PerturbationResult(InstructionSet repairResult, long replanLatencyNanos,
                                     int instructionChurn) {
    }

    /**
     * @param conflictRepair   what happened to whichever <em>other</em> platoons the slowdown
     *                         displaced; an empty instruction set with zero latency when nothing was
     *                         displaced, since no repair call is made at all in that case
     * @param slowdownAbsorbed whether the slowed platoon's own stretched walk was committed, as
     *                         opposed to abandoned in favour of its original route
     */
    public record SlowPlatoonResult(PerturbationResult conflictRepair, boolean slowdownAbsorbed) {
    }

    /**
     * Recompiles the hazard timeline, repairs against whatever became lethal, and measures the pass.
     *
     * @param snapshot the graph the timeline is recompiled against
     * @param now      the instant the event is being handled at
     * @return the repair outcome, its latency, and the resulting churn
     */
    public PerturbationResult applyHazardArrivesEarly(GraphSnapshot snapshot, LocalDateTime now) {
        // The session's monitor, same as RepairService and ImprovementLoop take: this reads and
        // mutates the shared ledger and plan book. Reentrant, so the nested onEvent below reacquires
        // it without deadlocking.
        synchronized (activePlan) {
            List<DispatchResult> before = activePlan.planBook().all();

            HazardTimeline oldTimeline = hazardTimelineCache.get();
            HazardTimeline newTimeline =
                    hazardTimelineCache.reload(snapshot, activePlan.sessionEpoch());

            LocalDateTime sessionEpoch = activePlan.sessionEpoch();
            long secondsSinceEpoch = Duration.between(sessionEpoch, now).toSeconds();
            int nowBucket = secondsSinceEpoch <= 0 ? 0 : timeModel.bucketsForSeconds(secondsSinceEpoch);

            int horizon = newTimeline.horizonBuckets();
            Set<Integer> affectedEdgeSlots = new HashSet<>();
            Set<Integer> affectedNodeIndices = new HashSet<>();

            // Past buckets are settled history the repair cannot act on, so the scan starts at nowBucket.
            for (int slot = 0; slot < snapshot.edgeSlotCount(); slot++) {
                for (int bucket = nowBucket; bucket < horizon; bucket++) {
                    if (newTimeline.isEdgeLethal(slot, bucket)
                            && !oldTimeline.isEdgeLethal(slot, bucket)) {
                        affectedEdgeSlots.add(slot);
                        break;
                    }
                }
            }
            for (int nodeIndex = 0; nodeIndex < snapshot.nodeCount(); nodeIndex++) {
                for (int bucket = nowBucket; bucket < horizon; bucket++) {
                    if (newTimeline.isNodeLethal(nodeIndex, bucket)
                            && !oldTimeline.isNodeLethal(nodeIndex, bucket)) {
                        affectedNodeIndices.add(nodeIndex);
                        break;
                    }
                }
            }

            long start = System.nanoTime();
            InstructionSet repairResult =
                    repairService.onEvent(affectedEdgeSlots, affectedNodeIndices, now);
            long latencyNanos = System.nanoTime() - start;

            List<DispatchResult> after = activePlan.planBook().all();
            return new PerturbationResult(repairResult, latencyNanos,
                    SimulationMetrics.instructionChurn(before, after));
        }
    }

    /**
     * Closes one shelter mid-session and replans everyone who was assigned to it.
     *
     * <p><strong>Why this does not go through {@link RepairService}.</strong> That service's entry
     * point takes compromised <em>cells</em> — edge slots and node indices — and finds affected
     * platoons by reservation intersection. A closed shelter compromises no cell: the roads leading
     * to it are as passable as ever, and the platoons that must be replanned are exactly those whose
     * assignment happens to name it. There is no honest set of cells to pass, so this is simulator-only
     * tooling built directly on the same low-level primitives {@code RepairService.repairOne} uses,
     * rather than a contortion of its arc/node-shaped API.
     *
     * <p>The body below therefore mirrors {@code RepairService.repairOne} step for step — anchor
     * before releasing, release the future portion only so elapsed history stands, remove from the
     * book (which is itself the shelter restore, since room is derived and never tracked), search from
     * the anchor, commit or strand. That branching is reproduced rather than reused only because
     * {@code repairOne} is private and not exposed as a callable unit; if it ever is, this should call
     * it instead.
     *
     * @param closedShelterId the shelter to close — never offered again to any replan here
     * @param snapshot        the graph the search and the shelter tally read from
     * @param policy          the traversal policy each replan searches under
     * @param now             the instant the closure is being handled at
     * @return the replans and strandings, the repair loop's latency, and the resulting churn
     */
    public PerturbationResult applyShelterCloses(long closedShelterId, GraphSnapshot snapshot,
                                                 TraversalPolicy policy, LocalDateTime now) {
        synchronized (activePlan) {
            List<DispatchResult> before = activePlan.planBook().all();

            LocalDateTime sessionEpoch = activePlan.sessionEpoch();
            long secondsSinceEpoch = Duration.between(sessionEpoch, now).toSeconds();
            int nowBucket = secondsSinceEpoch <= 0 ? 0 : timeModel.bucketsForSeconds(secondsSinceEpoch);

            List<DispatchResult> affected = new ArrayList<>();
            for (DispatchResult result : before) {
                if (result.searchResult().shelter().shelterId() == closedShelterId) {
                    affected.add(result);
                }
            }
            affected.sort(
                    Comparator.comparingInt((DispatchResult r) -> r.priority().ordinal()).reversed());

            ReservationLedger ledger = activePlan.ledger();
            PlanBook planBook = activePlan.planBook();
            List<DispatchResult> committed = new ArrayList<>();
            List<InstructionSet.Shortfall> shortfalls = new ArrayList<>();

            long start = System.nanoTime();
            for (DispatchResult original : affected) {
                SpaceTimeState anchor = planBook.projectedPosition(original.platoonId(), nowBucket);

                ledger.release(original.platoonId(), nowBucket);
                planBook.remove(original.platoonId());

                Map<Long, Integer> shelterRemaining = computeShelterRemaining(snapshot, planBook);
                Predicate<GraphSnapshot.ShelterRef> eligibility = shelter ->
                        shelter.status() == ShelterStatus.AVAILABLE
                                && shelter.shelterId() != closedShelterId
                                && shelterRemaining.getOrDefault(shelter.shelterId(), 0) >= original.size();

                SearchResult replanned = timeExpandedDijkstra.searchSpaceTime(
                        policy, anchor.nodeIndex(), anchor.bucket(), eligibility,
                        original.medicalPreferred(), ledger, original.size());

                if (!replanned.feasible()
                        || !DispatchService.reserveWalk(replanned.walk(), original.partyId(),
                                original.platoonId(), original.size(), ledger)) {
                    shortfalls.add(new InstructionSet.Shortfall(
                            original.partyId(), original.size(), original.waveIndex()));
                    continue;
                }

                DispatchResult repaired = new DispatchResult(
                        original.partyId(), original.platoonId(), original.waveIndex(), original.size(),
                        original.priority(), original.medicalPreferred(), replanned);
                planBook.commit(repaired);
                committed.add(repaired);
            }
            long latencyNanos = System.nanoTime() - start;

            List<DispatchResult> after = activePlan.planBook().all();
            return new PerturbationResult(
                    new InstructionSet(List.copyOf(committed), List.copyOf(shortfalls)),
                    latencyNanos,
                    SimulationMetrics.instructionChurn(before, after));
        }
    }

    /**
     * Asks what happens if one platoon moves slower than planned for its whole walk.
     *
     * <p><strong>The mechanic, and why the ledger needed no extension.</strong> A slowed platoon does
     * not need a new route — it needs its existing route to occupy later cells. So: release it
     * entirely, stretch its own walk in place, and then use the ledger's <em>read-only</em>
     * feasibility checks to ask which of the stretched cells other platoons have already claimed. The
     * platoon's own reservation is gone by then, so an infeasible answer is unambiguously somebody
     * else's claim rather than its own former self. Those slots and nodes are exactly the shape of
     * fact {@link RepairService} already acts on, so the displaced others are relocated by the
     * production repair path, not by anything invented here.
     *
     * <p>This is a full release rather than {@code release(platoonId, nowBucket)}: the question is
     * "what if this platoon had been slow from departure", a counterfactual over the whole walk, not
     * an event arriving mid-route. Which is also why the failure branch can restore the platoon
     * exactly — the journal taken before the release makes abandoning the hypothetical total, leaving
     * the ledger byte-for-byte as it was rather than in a stranded or partly-stretched state.
     *
     * <p><strong>Displacement is a genuine best effort, not a guarantee — and that is an honest
     * limit, not a bug.</strong> Repair relocates the displaced platoon(s) <em>before</em> this
     * platoon's stretched walk is ever reserved, so their search cannot see the pending need it is
     * about to create. If the displaced platoon's own cheapest option is exactly the cell this
     * platoon is about to want, repair will — correctly, given what it can see — send it right back
     * there, and this platoon's own reservation attempt will conflict a second time. That case ends
     * in {@link #applySlowPlatoon} returning {@code slowdownAbsorbed = false} with the ledger fully
     * rolled back, never in a forced reservation or a corrupted state. Giving repair foresight of a
     * not-yet-reserved need would require either a capacity-violating "hold" primitive this project
     * deliberately does not have, or a way to exclude specific cells from one repair call, which does
     * not exist either. Both remain open extensions if reconvergence turns out to matter in practice.
     *
     * <p><strong>Horizon overrun is checked before any feasibility call, not after.</strong>
     * {@code edgeFeasible}/{@code nodeFeasible} throw on an out-of-horizon bucket rather than
     * returning {@code false} — reasonably, since every other caller only ever asks about buckets a
     * search itself produced, which are always in range by construction. A stretched walk breaks that
     * assumption: multiplying real durations can push its arrival past the compiled horizon, and by
     * the time the feasibility loop would discover that, the platoon is already released and removed
     * from the book. An uncaught exception there would leave the session with this platoon holding no
     * reservation and no plan-book entry — worse than the ordinary "could not absorb the slowdown"
     * outcome this method already has a clean path for. So the arrival bucket is checked once, up
     * front; an overrun is treated exactly like any other failure to absorb the slowdown, via the same
     * rollback.
     *
     * @param platoonId      the committed platoon to slow
     * @param slowdownFactor multiplier on every movement step's duration; must exceed 1
     * @param snapshot       unused by the stretch itself, carried for signature parity with the other
     *                       perturbations and for any future capacity-aware refinement
     * @param policy         likewise
     * @param now            the instant the conflict repair is handled at
     * @return what the displaced platoons did, and whether the slowdown was ultimately absorbed
     * @throws IllegalArgumentException if {@code slowdownFactor <= 1.0}
     * @throws IllegalStateException    if the platoon holds no committed route
     */
    public SlowPlatoonResult applySlowPlatoon(long platoonId, double slowdownFactor,
                                              GraphSnapshot snapshot, TraversalPolicy policy,
                                              LocalDateTime now) {
        if (slowdownFactor <= 1.0) {
            throw new IllegalArgumentException(
                    "slowdownFactor must be > 1.0, got " + slowdownFactor);
        }

        synchronized (activePlan) {
            PlanBook planBook = activePlan.planBook();
            ReservationLedger ledger = activePlan.ledger();

            // Captured before anything is touched, so the churn diff sees this platoon's own
            // before-state rather than the gap left by its removal.
            List<DispatchResult> before = planBook.all();

            DispatchResult original = planBook.get(platoonId).orElseThrow(() ->
                    new IllegalStateException("Platoon " + platoonId + " is not currently committed"));

            ReservationLedger.Journal journal = ledger.journal(platoonId);
            ledger.release(platoonId);
            planBook.remove(platoonId);

            TimedWalk stretched = stretch(original.searchResult().walk(), slowdownFactor);

            // Checked before any feasibility call — see the method Javadoc for why an out-of-horizon
            // bucket cannot be allowed to reach edgeFeasible/nodeFeasible from here.
            if (stretched.arrival().bucket() >= ledger.horizonBuckets()) {
                ledger.rollback(platoonId, journal);
                planBook.commit(original);
                List<DispatchResult> overrunAfter = planBook.all();
                return new SlowPlatoonResult(
                        new PerturbationResult(new InstructionSet(List.of(), List.of()), 0L,
                                SimulationMetrics.instructionChurn(before, overrunAfter)),
                        false);
            }

            Set<Integer> affectedEdgeSlots = new HashSet<>();
            Set<Integer> affectedNodeIndices = new HashSet<>();
            for (TimedWalk.Step step : stretched.steps()) {
                if (step.isWait()) {
                    if (!ledger.nodeFeasible(
                            step.to().nodeIndex(), step.to().bucket(), original.size())) {
                        affectedNodeIndices.add(step.to().nodeIndex());
                    }
                } else {
                    int tau = step.to().bucket() - step.from().bucket();
                    if (!ledger.edgeFeasible(
                            step.edgeSlot(), step.from().bucket(), tau, original.size())) {
                        affectedEdgeSlots.add(step.edgeSlot());
                    }
                }
            }

            // No conflicts means no repair call — an empty result, not a call that returns nothing.
            InstructionSet repairResult = new InstructionSet(List.of(), List.of());
            long latencyNanos = 0L;
            if (!affectedEdgeSlots.isEmpty() || !affectedNodeIndices.isEmpty()) {
                long start = System.nanoTime();
                repairResult = repairService.onEvent(affectedEdgeSlots, affectedNodeIndices, now);
                latencyNanos = System.nanoTime() - start;
            }

            boolean slowdownAbsorbed = DispatchService.reserveWalk(
                    stretched, original.partyId(), platoonId, original.size(), ledger);

            if (slowdownAbsorbed) {
                planBook.commit(new DispatchResult(
                        original.partyId(), platoonId, original.waveIndex(), original.size(),
                        original.priority(), original.medicalPreferred(),
                        new SearchResult(true, stretched, original.searchResult().shelter())));
            } else {
                ledger.rollback(platoonId, journal);
                planBook.commit(original);
            }

            List<DispatchResult> after = planBook.all();
            return new SlowPlatoonResult(
                    new PerturbationResult(repairResult, latencyNanos,
                            SimulationMetrics.instructionChurn(before, after)),
                    slowdownAbsorbed);
        }
    }

    /**
     * The same physical route, taking longer: identical origin and identical ordered sequence of
     * slots and nodes, with every movement step's duration multiplied and the buckets recomputed
     * cumulatively from the original departure. Waits stay one bucket — a wait is a unit of clock,
     * not of movement, so slowing the walker does not lengthen it. Movement durations round up, so a
     * slowdown can never be truncated into looking faster than it is.
     *
     * <p><strong>{@code totalCost} is scaled proportionally, and that is an approximation.</strong>
     * This walk was never re-searched, so its cost cannot reflect a genuine re-pricing of exposure
     * against the new, later buckets each cell now falls in — a cell that was SAFE at bucket 12 may
     * be RISKY at bucket 15, and only a real search would know. Proportional scaling is the honest,
     * simple stand-in; anything more elaborate would imply a precision this path does not have.
     * Distance is copied unchanged, since the route is physically identical.
     */
    private TimedWalk stretch(TimedWalk original, double slowdownFactor) {
        int bucket = original.origin().bucket();
        List<TimedWalk.Step> steps = new ArrayList<>(original.steps().size());

        for (TimedWalk.Step step : original.steps()) {
            int originalTau = step.to().bucket() - step.from().bucket();
            int tau = step.isWait() ? originalTau
                    : Math.max(1, (int) Math.ceil(originalTau * slowdownFactor));

            steps.add(new TimedWalk.Step(
                    new SpaceTimeState(step.from().nodeIndex(), bucket),
                    new SpaceTimeState(step.to().nodeIndex(), bucket + tau),
                    step.isWait(), step.edgeSlot()));
            bucket += tau;
        }

        return new TimedWalk(original.origin(), List.copyOf(steps),
                original.totalCost() * slowdownFactor, original.totalDistanceKm());
    }

    /** Same derive-don't-track shelter accounting as {@code DispatchService}/{@code RepairService}. */
    private Map<Long, Integer> computeShelterRemaining(GraphSnapshot snapshot, PlanBook planBook) {
        Map<Long, Integer> remaining = new HashMap<>();
        for (GraphSnapshot.ShelterRef shelter : snapshot.shelters()) {
            remaining.put(shelter.shelterId(), shelter.availableCapacity());
        }
        for (DispatchResult result : planBook.all()) {
            remaining.merge(result.searchResult().shelter().shelterId(), -result.size(), Integer::sum);
        }
        return remaining;
    }
}