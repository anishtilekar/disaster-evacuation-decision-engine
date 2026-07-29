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
 * <p><strong>Shelter capacity is charged at assignment, not on arrival.</strong> Remaining room per
 * shelter is derived fresh each call from the snapshot's static capacity minus everyone the plan
 * book already has committed there, rather than tracked as separate mutable state that could drift
 * out of sync with the book. The eligibility filter handed to the search reads that live figure, so
 * a shelter filled by an earlier platoon — in this call or a previous one — is already excluded.
 * This closes a real race in a per-request design, where two concurrent requests can both see the
 * same free space, both be sent, and only discover the oversubscription when they arrive.
 *
 * <p><strong>Calls accumulate onto one retained session, they do not replan from scratch.</strong>
 * The ledger and plan book live in {@link ActivePlan} and persist across every {@code plan(...)}
 * call — the first call starts the session, and every later call adds its parties to the same
 * board rather than resetting it, which is what lets {@code RepairService} find and modify
 * reservations a previous call made. That persistence is also why bucket 0 cannot be re-anchored to
 * "now" on every call: it is fixed once, at the session's start, and every departure bucket after
 * that is computed relative to that fixed epoch — see {@link #ensureSession} and
 * {@link #departureBucketFor}.
 *
 * <p><strong>Scope.</strong> Convoy coherence (keeping a party's waves on a shared spatial route) is
 * deferred with the other soft cost terms; waves are separated here by departure stagger alone.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DispatchService {

    private final GraphCache graphCache;
    private final HazardTimelineCache hazardTimelineCache;
    private final ActivePlan activePlan;
    private final TimeExpandedDijkstra timeExpandedDijkstra;
    private final MultiTargetShelterSearch multiTargetShelterSearch;
    private final TimeModel timeModel;
    private final GraphEngineProperties properties;

    /** Platoon ids are unique for the life of the service, so a ledger never confuses two waves. */
    private final AtomicLong platoonIdSequence = new AtomicLong();

    /**
     * Plans every party in one pass against the current session's retained ledger and plan book,
     * returning what this call committed and what fell short.
     *
     * <p><strong>Synchronized on {@code activePlan}, not on this service.</strong> A planning pass
     * reads and mutates the session's shared ledger and plan book, and so does a repair pass in
     * {@code RepairService} — two different classes reading and mutating the exact same
     * {@link ActivePlan}. Locking on {@code this} would only ever serialise calls against <em>this
     * one instance</em>; it does nothing to stop a dispatch pass and a repair pass, running on two
     * different threads through two different service beans, from interleaving against that shared
     * state at the same time. The lock has to be the thing actually being shared, so both this method
     * and {@code RepairService.onEvent(...)} synchronize on the same {@code activePlan} instance —
     * the one object both classes were handed by Spring as the same singleton.
     *
     * @param parties the parties to route; planned in the order this method chooses, not the order given
     * @param now     the wall-clock instant this call is happening at — starts the session's epoch
     *                if none is active yet, otherwise just marks how much of the fixed epoch has
     *                elapsed; see {@link #ensureSession}
     * @return every platoon this call committed and every party it could not fully place
     */
    public InstructionSet plan(List<Party> parties, LocalDateTime now) {
        synchronized (activePlan) {
            GraphSnapshot snapshot = graphCache.get();
            LocalDateTime sessionEpoch = ensureSession(snapshot, now);

            TraversalPolicy policy = new TraversalPolicy(snapshot, currentTimelineFor(snapshot, sessionEpoch));
            ReservationLedger ledger = activePlan.ledger();
            PlanBook planBook = activePlan.planBook();
            Map<Long, Integer> shelterRemaining = computeShelterRemaining(snapshot, planBook);

            List<Party> ordered = orderForDispatch(parties, snapshot, policy, now);

            List<DispatchResult> committed = new ArrayList<>();
            List<InstructionSet.Shortfall> shortfalls = new ArrayList<>();

            for (Party party : ordered) {
                planParty(party, sessionEpoch, now, snapshot, policy, ledger, planBook,
                        shelterRemaining, committed, shortfalls);
            }

            int placedPeople = committed.stream().mapToInt(DispatchResult::size).sum();
            int unplacedPeople = shortfalls.stream()
                    .mapToInt(InstructionSet.Shortfall::unroutedSize).sum();
            log.info("Dispatched {} parties: {} platoons committed ({} people), {} parties short ({} people)",
                    parties.size(), committed.size(), placedPeople, shortfalls.size(), unplacedPeople);

            return new InstructionSet(List.copyOf(committed), List.copyOf(shortfalls));
        }
    }

    /**
     * Makes sure a session is active and returns its fixed epoch, starting one if necessary.
     *
     * <p>Three cases: no session has ever started (start one, epoch = {@code now}); a session is
     * active but was built against a graph of a different shape (the ledger's array sizes can no
     * longer match the current snapshot's slot/node counts — a rare, manual re-import, but one that
     * would otherwise index out of bounds rather than fail cleanly, so it is treated as a forced
     * fresh start rather than silently trusted); or a session is active and still matches (return
     * its already-fixed epoch unchanged, whatever {@code now} is this call).
     *
     * <p>Starting a session also anchors the hazard timeline to that same epoch, so the very first
     * compile of this session and every later repair-triggered recompile agree on what bucket 0 means.
     */
    private LocalDateTime ensureSession(GraphSnapshot snapshot, LocalDateTime now) {
        if (activePlan.isActive()) {
            ReservationLedger current = activePlan.ledger();
            if (current.edgeSlotCount() == snapshot.edgeSlotCount()
                    && current.nodeCount() == snapshot.nodeCount()) {
                return activePlan.sessionEpoch();
            }
            log.warn("Active session's ledger no longer matches the current graph shape "
                            + "(edge slots {} vs {}, nodes {} vs {}); starting a new session and "
                            + "discarding its reservations",
                    current.edgeSlotCount(), snapshot.edgeSlotCount(),
                    current.nodeCount(), snapshot.nodeCount());
        }

        activePlan.reset(snapshot, timeModel, properties, now);
        hazardTimelineCache.reload(snapshot, now);
        return now;
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
    private void planParty(Party party, LocalDateTime sessionEpoch, LocalDateTime now,
                           GraphSnapshot snapshot, TraversalPolicy policy, ReservationLedger ledger,
                           PlanBook planBook, Map<Long, Integer> shelterRemaining,
                           List<DispatchResult> committed,
                           List<InstructionSet.Shortfall> shortfalls) {

        List<Platoon> platoons = splitIntoPlatoons(party);
        int earliestDeparture = departureBucketFor(party, sessionEpoch, now);
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

            if (!result.feasible() || !reserveWalk(result.walk(), party.partyId(), platoon.platoonId(),
                    platoon.size(), ledger)) {
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

            DispatchResult dispatchResult = new DispatchResult(
                    party.partyId(), platoon.platoonId(), platoon.waveIndex(), platoon.size(),
                    party.priority(), platoon.medicalPreferred(), result);
            committed.add(dispatchResult);
            planBook.commit(dispatchResult);
        }
    }

    /**
     * Sorts by effective priority, then most-constrained-first, then request time.
     *
     * <p><strong>Effective priority carries the anti-starvation elevation rule.</strong> The raw
     * priority ordinal is a step function, and a queue ordered by it alone lets a nominally-low party
     * be passed over indefinitely: every fresh batch can contain someone nominally more urgent, and
     * "nominally more urgent" wins every time regardless of how long the loser has already been
     * waiting. Elevation makes the first sort key continuous — an ordinal plus one class per
     * {@code starvationElevationMinutesPerLevel} minutes waited — so waiting is itself a claim on
     * capacity. A LOW party that has waited long enough must eventually outrank a CRITICAL party that
     * arrived a moment ago, which is the whole point: nobody is perpetually deferred purely because
     * someone else is always nominally more urgent at the instant dispatch runs.
     *
     * <p>Both the constrainedness probe and the elevation score are computed once per party and cached
     * into the sort key rather than recomputed per comparison. For constrainedness that is a cost
     * argument — a comparator running a graph search on every call would turn an n-log-n sort into
     * something far worse. For elevation it is also a correctness one: the score is a function of
     * elapsed time, and recomputing it inside the comparator would evaluate a moving quantity at
     * slightly different instants across comparisons, giving the sort an ordering that is not
     * internally consistent.
     */
    private List<Party> orderForDispatch(List<Party> parties, GraphSnapshot snapshot,
                                         TraversalPolicy policy, LocalDateTime now) {
        Map<Long, Double> constrainedness = new HashMap<>();
        Map<Long, Double> effectivePriority = new HashMap<>();
        for (Party party : parties) {
            constrainedness.put(party.partyId(), estimateConstrainedness(party, snapshot, policy));
            effectivePriority.put(party.partyId(), effectivePriority(party, now));
        }

        List<Party> ordered = new ArrayList<>(parties);
        ordered.sort(Comparator
                .comparingDouble((Party party) -> effectivePriority.get(party.partyId())).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (Party party) -> constrainedness.get(party.partyId())).reversed())
                .thenComparing(Party::requestedAt)
                .thenComparingLong(Party::partyId));
        return ordered;
    }

    /**
     * A party's priority ordinal plus one class per configured minutes of waiting.
     *
     * <p>Waiting time is floored at zero: a future-dated request has not come due yet, so it has not
     * been waiting at all, let alone long enough to have earned elevation. Without the floor its
     * negative wait would <em>demote</em> it below its own nominal class.
     */
    private double effectivePriority(Party party, LocalDateTime now) {
        double minutesWaited = Math.max(0.0,
                Duration.between(party.requestedAt(), now).toSeconds() / 60.0);
        return party.priority().ordinal()
                + minutesWaited / properties.getDispatch().getStarvationElevationMinutesPerLevel();
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
     * The bucket a party's first wave may leave at, relative to the session's fixed epoch — never
     * relative to {@code now} directly, since bucket 0 does not move once a session has started.
     *
     * <p>A party cannot depart before this call is actually happening (so {@code now} is always a
     * floor), and a future-dated request cannot depart before it is made (so
     * {@code party.requestedAt()} is the other floor). The later of the two, converted to a bucket
     * offset from the session epoch, is the earliest honest departure.
     */
    private int departureBucketFor(Party party, LocalDateTime sessionEpoch, LocalDateTime now) {
        LocalDateTime earliestPossibleDeparture =
                now.isAfter(party.requestedAt()) ? now : party.requestedAt();
        long secondsSinceEpoch = Duration.between(sessionEpoch, earliestPossibleDeparture).toSeconds();
        return secondsSinceEpoch <= 0 ? 0 : timeModel.bucketsForSeconds(secondsSinceEpoch);
    }

    /**
     * Only open shelters with room for this whole platoon are targets — the live tally, not the
     * snapshot's static figure, so a shelter filled earlier (this call or a previous one) is already
     * excluded.
     */
    private Predicate<GraphSnapshot.ShelterRef> eligibilityFor(int platoonSize,
                                                               Map<Long, Integer> shelterRemaining) {
        return shelter -> shelter.status() == ShelterStatus.AVAILABLE
                && shelterRemaining.getOrDefault(shelter.shelterId(), 0) >= platoonSize;
    }

    /**
     * Derives each shelter's currently-remaining room from the snapshot's static capacity minus
     * everyone the plan book already has committed there — computed fresh rather than tracked as its
     * own mutable field, so it can never drift out of sync with what is actually committed across an
     * accumulating session.
     */
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

    /**
     * Commits a chosen walk's cells to the ledger, all or nothing.
     *
     * <p>The search only ever proposes walks it checked against this same ledger, so a refusal here
     * should not happen. It is still handled rather than assumed away: a partially reserved platoon
     * would corrupt every subsequent party's view of the board, so anything less than complete
     * success releases the whole platoon and reports it as unplaceable.
     *
     * <p>Package-private and {@code static} rather than an instance method of one caller: both this
     * class's own dispatch loop and {@code RepairService}'s replan-and-recommit step need to commit a
     * walk the exact same way, and a second copy of this all-or-nothing loop would be a real place for
     * a future fix to land in only one of the two. Takes the three raw identity fields a caller
     * already has on hand — {@code partyId}, {@code platoonId}, {@code size} — rather than a
     * {@link Platoon}, since a repair anchored on an already-committed {@link DispatchResult} has
     * those directly and no reason to reconstruct a {@code Platoon} just to call this.
     *
     * <p>On failure, the cleanup releases only from {@code walk.origin().bucket()} onward — never the
     * whole platoon unconditionally. For this class's own fresh-dispatch caller that is equivalent to
     * a full release, since a platoon being reserved for the first time has no holdings before its own
     * departure bucket to begin with. But {@code RepairService} calls this after already giving back
     * only a platoon's <em>future</em> holdings, deliberately preserving its already-elapsed
     * reservation history — a full release here would silently erase that genuine history on this
     * defensive path. Rolling back from the walk's own origin is correct in both callers without
     * either one needing to say so.
     */
    static boolean reserveWalk(TimedWalk walk, long partyId, long platoonId, int size,
                               ReservationLedger ledger) {
        for (TimedWalk.Step step : walk.steps()) {
            boolean reserved;
            if (step.isWait()) {
                reserved = ledger.tryReserveNode(
                        step.to().nodeIndex(), step.to().bucket(), size, platoonId);
            } else {
                int tau = step.to().bucket() - step.from().bucket();
                reserved = ledger.tryReserveEdge(
                        step.edgeSlot(), step.from().bucket(), tau, size, platoonId);
            }

            if (!reserved) {
                log.warn("Platoon {} (party {}) failed to reserve a cell the search had accepted; "
                                + "releasing from its walk's origin bucket {} onward",
                        platoonId, partyId, walk.origin().bucket());
                ledger.release(platoonId, walk.origin().bucket());
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
     * The hazard timeline for this snapshot, recompiled against {@code sessionEpoch} if the cache
     * holds nothing or holds one built against a different graph version — {@link TraversalPolicy}
     * refuses a mismatched pair outright, so reconciling here is what keeps a graph reload from
     * breaking the next plan.
     *
     * <p>Any recompile this triggers uses the session's fixed epoch, never a fresh {@code now()}, so
     * bucket 0 keeps meaning what it has meant for the whole session.
     */
    private HazardTimeline currentTimelineFor(GraphSnapshot snapshot, LocalDateTime sessionEpoch) {
        if (hazardTimelineCache.isLoaded()) {
            HazardTimeline timeline = hazardTimelineCache.get();
            if (timeline.graphVersion() == snapshot.graphVersion()) {
                return timeline;
            }
            log.info("Hazard timeline was compiled against graph v{} but the current graph is v{}; "
                            + "recompiling before dispatch",
                    timeline.graphVersion(), snapshot.graphVersion());
        }
        return hazardTimelineCache.reload(snapshot, sessionEpoch);
    }
}