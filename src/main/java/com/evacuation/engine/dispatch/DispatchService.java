package com.evacuation.engine.dispatch;

import com.evacuation.engine.algorithm.MultiTargetShelterSearch;
import com.evacuation.engine.algorithm.spacetime.SearchResult;
import com.evacuation.engine.algorithm.spacetime.TimeExpandedDijkstra;
import com.evacuation.engine.algorithm.spacetime.TimedWalk;
import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.graph.overlay.TraversalPolicy;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimeline;
import com.evacuation.engine.graph.time.HazardTimelineCache;
import com.evacuation.engine.graph.time.TimeModel;
import com.evacuation.engine.loader.GraphCache;
import com.evacuation.engine.model.enums.ShelterStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * The design's §3.3 dispatch loop: routes a whole batch of parties one at a time, in a deliberate
 * order, each one reserving the space-time cells it will occupy so that every later party plans
 * around what earlier parties have already taken.
 *
 * <p>This is the step that makes the engine's answers collectively true rather than only
 * individually true. Twelve independent searches over the same graph all return the same shortest
 * path and all promise the same travel time — an answer that is correct for any one party and
 * fiction for the group. Here, the first parties fill a corridor's early buckets, the ledger then
 * reports those cells as full, and later parties are routed onto staggered waves or a different
 * crossing entirely. No party is told a time the road cannot actually deliver.
 *
 * <p><strong>Order matters, and it is chosen rather than incidental.</strong> Parties are sorted by
 * priority class first (a critical party picks before a low one), then <em>most-constrained-first</em>
 * within a class, then by request time. The middle term is the interesting one: a party with the
 * fewest good options should choose before a party with many, for the same reason a
 * most-constrained-variable heuristic works in constraint solving — scarce options get first claim
 * on scarce capacity. Constrainedness is estimated with a free-flow probe, which is exactly what
 * the pre-existing capacity-blind shelter search already is: it answers "how far is help, ignoring
 * congestion", and a party whose nearest shelter is distant or unreachable has the least slack.
 * That is a proxy for the design's "count of feasible shelter options", not a literal count, and is
 * documented as such rather than overclaimed.
 *
 * <p><strong>Shelter capacity is charged at assignment, not on arrival.</strong> Each committed
 * platoon immediately decrements its shelter's remaining room, and the eligibility filter handed to
 * the search consults that live figure. This closes a real race in a per-request design, where two
 * concurrent requests can both see the same free space, both be sent, and only discover the
 * oversubscription when they arrive.
 *
 * <p><strong>Scope.</strong> Each {@code plan(...)} call is a fresh planning pass over a fresh
 * ledger — the batch is planned from an empty board. Targeted repair of an existing plan after a
 * hazard event is a different entry point, arriving with the repair phase, and will reuse a
 * retained ledger rather than resetting one. Convoy coherence (keeping a party's waves on a shared
 * spatial route) is likewise deferred with the other soft cost terms; waves are separated here by
 * departure stagger alone.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DispatchService {

    private final GraphCache graphCache;
    private final HazardTimelineCache hazardTimelineCache;
    private final TimeExpandedDijkstra timeExpandedDijkstra;
    private final MultiTargetShelterSearch multiTargetShelterSearch;
    private final TimeModel timeModel;
    private final GraphEngineProperties properties;

    /** Platoon ids are unique for the life of the service, so a ledger never confuses two waves. */
    private final AtomicLong platoonIdSequence = new AtomicLong();

    /**
     * Plans every party in one pass and returns what was committed and what fell short.
     *
     * <p>Synchronized because a planning pass owns a mutable ledger and a mutable shelter tally for
     * its duration; two overlapping passes would interleave reservations and produce a plan neither
     * of them actually checked. The design's dispatch is sequential by construction, so serialising
     * whole passes costs nothing it was relying on.
     *
     * @param parties the parties to route; planned in the order this method chooses, not the order given
     * @param now     the wall-clock instant bucket 0 represents for this pass
     * @return every committed platoon and every party that could not be fully placed
     */
    public synchronized InstructionSet plan(List<Party> parties, LocalDateTime now) {
        GraphSnapshot snapshot = graphCache.get();
        TraversalPolicy policy = new TraversalPolicy(snapshot, currentTimelineFor(snapshot));

        // A fresh board: nothing reserved, every shelter at its snapshot capacity.
        ReservationLedger ledger = new ReservationLedger(snapshot, timeModel, properties);
        Map<Long, Integer> shelterRemaining = new HashMap<>();
        for (GraphSnapshot.ShelterRef shelter : snapshot.shelters()) {
            shelterRemaining.put(shelter.shelterId(), shelter.availableCapacity());
        }

        List<Party> ordered = orderForDispatch(parties, snapshot, policy);

        List<DispatchResult> committed = new ArrayList<>();
        List<InstructionSet.Shortfall> shortfalls = new ArrayList<>();

        for (Party party : ordered) {
            planParty(party, now, snapshot, policy, ledger, shelterRemaining, committed, shortfalls);
        }

        int placedPeople = committed.stream().mapToInt(DispatchResult::size).sum();
        int unplacedPeople = shortfalls.stream()
                .mapToInt(InstructionSet.Shortfall::unroutedSize).sum();
        log.info("Dispatched {} parties: {} platoons committed ({} people), {} parties short ({} people)",
                parties.size(), committed.size(), placedPeople, shortfalls.size(), unplacedPeople);

        return new InstructionSet(List.copyOf(committed), List.copyOf(shortfalls));
    }

    /**
     * Routes one party's waves in order, stopping at the first that cannot be placed.
     *
     * <p>Stopping rather than skipping ahead is deliberate: waves depart in sequence, and if the
     * network cannot take wave {@code j}, the waves behind it are not going to fare better on a
     * board that only gets fuller. Everyone from the failed wave onward is reported as a shortfall
     * together, so an operator sees one honest number per party rather than a scatter of partial
     * failures.
     */
    private void planParty(Party party, LocalDateTime now, GraphSnapshot snapshot,
                           TraversalPolicy policy, ReservationLedger ledger,
                           Map<Long, Integer> shelterRemaining,
                           List<DispatchResult> committed,
                           List<InstructionSet.Shortfall> shortfalls) {

        List<Platoon> platoons = splitIntoPlatoons(party);
        int earliestDeparture = departureBucketFor(party, now);
        int stagger = properties.getDispatch().getConvoyStaggerBuckets();

        for (Platoon platoon : platoons) {
            int departureBucket = earliestDeparture + platoon.waveIndex() * stagger;

            SearchResult result = timeExpandedDijkstra.searchSpaceTime(
                    policy,
                    platoon.originNodeIndex(),
                    departureBucket,
                    eligibilityFor(platoon.size(), shelterRemaining),
                    platoon.medicalPreferred(),
                    ledger,
                    platoon.size());

            if (!result.feasible() || !reserveWalk(result.walk(), platoon, ledger)) {
                shortfalls.add(new InstructionSet.Shortfall(
                        party.partyId(),
                        unroutedFrom(platoons, platoon.waveIndex()),
                        platoon.waveIndex()));
                log.debug("Party {} wave {} could not be placed; {} people short",
                        party.partyId(), platoon.waveIndex(),
                        unroutedFrom(platoons, platoon.waveIndex()));
                return;
            }

            // Charge the shelter now, so the next platoon's eligibility filter already sees it.
            shelterRemaining.merge(result.shelter().shelterId(), -platoon.size(), Integer::sum);

            committed.add(new DispatchResult(
                    party.partyId(), platoon.platoonId(), platoon.waveIndex(), platoon.size(), result));
        }
    }

    /**
     * Sorts by priority class, then most-constrained-first within a class, then request time.
     *
     * <p>The constrainedness probe runs once per party and is cached into the sort key rather than
     * recomputed per comparison — a comparator that runs a graph search on every call would turn an
     * n-log-n sort into something far worse.
     */
    private List<Party> orderForDispatch(List<Party> parties, GraphSnapshot snapshot,
                                         TraversalPolicy policy) {
        Map<Long, Double> constrainedness = new HashMap<>();
        for (Party party : parties) {
            constrainedness.put(party.partyId(), estimateConstrainedness(party, snapshot, policy));
        }

        List<Party> ordered = new ArrayList<>(parties);
        ordered.sort(Comparator
                .comparingInt((Party party) -> party.priority().ordinal()).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (Party party) -> constrainedness.get(party.partyId())).reversed())
                .thenComparing(Party::requestedAt)
                .thenComparingLong(Party::partyId));
        return ordered;
    }

    /**
     * How little room a party has to manoeuvre, as free-flow cost to its nearest shelter — larger is
     * more constrained, and an unreachable party is maximally so.
     *
     * <p>The probe deliberately ignores capacity: "free-flow" means exactly that, and the existing
     * shelter search is capacity-blind by construction, so it is already the right instrument. This
     * is a proxy for the design's "fewest feasible shelter options", not a count of them — a party
     * whose nearest help is far away has the least slack, which is the property the ordering
     * actually wants.
     */
    private double estimateConstrainedness(Party party, GraphSnapshot snapshot,
                                           TraversalPolicy policy) {
        MultiTargetShelterSearch.ShelterPathResult probe =
                multiTargetShelterSearch.findNearestEligibleShelter(
                        snapshot, policy, party.originNodeIndex(),
                        shelter -> shelter.status() == ShelterStatus.AVAILABLE);

        return probe.reachable() ? probe.totalCost() : Double.POSITIVE_INFINITY;
    }

    /**
     * Splits a party into waves of at most the configured platoon size.
     *
     * <p>Sizes sum to exactly the party's group size — nobody is invented and nobody is dropped in
     * the split itself. A party that cannot be fully routed loses people to a recorded shortfall,
     * never to arithmetic here.
     */
    private List<Platoon> splitIntoPlatoons(Party party) {
        int maxSize = properties.getDispatch().getMaxPlatoonSize();
        if (maxSize <= 0) {
            throw new IllegalStateException(
                    "graph.dispatch.max-platoon-size must be > 0, got " + maxSize);
        }

        List<Platoon> platoons = new ArrayList<>();
        int remaining = party.numberOfPeople();
        int waveIndex = 0;

        while (remaining > 0) {
            int size = Math.min(remaining, maxSize);
            platoons.add(new Platoon(
                    platoonIdSequence.incrementAndGet(),
                    party.partyId(),
                    waveIndex,
                    size,
                    party.originNodeIndex(),
                    party.medicalAssistanceRequired()));
            remaining -= size;
            waveIndex++;
        }
        return platoons;
    }

    /**
     * The bucket a party's first wave may leave at: now if its request has already come due, or the
     * bucket its future-dated request falls in.
     */
    private int departureBucketFor(Party party, LocalDateTime now) {
        long secondsUntilRequest = Duration.between(now, party.requestedAt()).toSeconds();
        return secondsUntilRequest <= 0 ? 0 : timeModel.bucketsForSeconds(secondsUntilRequest);
    }

    /**
     * Only open shelters with room for this whole platoon are targets — the live tally, not the
     * snapshot's static figure, so a shelter filled earlier in this same pass is already excluded.
     */
    private Predicate<GraphSnapshot.ShelterRef> eligibilityFor(int platoonSize,
                                                               Map<Long, Integer> shelterRemaining) {
        return shelter -> shelter.status() == ShelterStatus.AVAILABLE
                && shelterRemaining.getOrDefault(shelter.shelterId(), 0) >= platoonSize;
    }

    /**
     * Commits a chosen walk's cells to the ledger, all or nothing.
     *
     * <p>The search only ever proposes walks it checked against this same ledger, so a refusal here
     * should not happen. It is still handled rather than assumed away: a partially reserved platoon
     * would corrupt every subsequent party's view of the board, so anything less than complete
     * success releases the whole platoon and reports it as unplaceable.
     */
    private boolean reserveWalk(TimedWalk walk, Platoon platoon, ReservationLedger ledger) {
        for (TimedWalk.Step step : walk.steps()) {
            boolean reserved;
            if (step.isWait()) {
                reserved = ledger.tryReserveNode(
                        step.to().nodeIndex(), step.to().bucket(), platoon.size(), platoon.platoonId());
            } else {
                int tau = step.to().bucket() - step.from().bucket();
                reserved = ledger.tryReserveEdge(
                        step.edgeSlot(), step.from().bucket(), tau, platoon.size(), platoon.platoonId());
            }

            if (!reserved) {
                log.warn("Platoon {} (party {}) failed to reserve a cell the search had accepted; "
                                + "releasing the whole platoon",
                        platoon.platoonId(), platoon.partyId());
                ledger.release(platoon.platoonId());
                return false;
            }
        }
        return true;
    }

    /** People in the wave that failed plus every wave behind it, none of which will be attempted. */
    private int unroutedFrom(List<Platoon> platoons, int failedWaveIndex) {
        int unrouted = 0;
        for (Platoon platoon : platoons) {
            if (platoon.waveIndex() >= failedWaveIndex) {
                unrouted += platoon.size();
            }
        }
        return unrouted;
    }

    /**
     * The hazard timeline for this snapshot, recompiled if the cache holds nothing or holds one
     * built against a different graph version — {@link TraversalPolicy} refuses a mismatched pair
     * outright, so reconciling here is what keeps a graph reload from breaking the next plan.
     */
    private HazardTimeline currentTimelineFor(GraphSnapshot snapshot) {
        if (hazardTimelineCache.isLoaded()) {
            HazardTimeline timeline = hazardTimelineCache.get();
            if (timeline.graphVersion() == snapshot.graphVersion()) {
                return timeline;
            }
            log.info("Hazard timeline was compiled against graph v{} but the current graph is v{}; "
                            + "recompiling before dispatch",
                    timeline.graphVersion(), snapshot.graphVersion());
        }
        return hazardTimelineCache.reload(snapshot);
    }
}
