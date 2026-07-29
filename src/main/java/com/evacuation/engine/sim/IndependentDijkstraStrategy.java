package com.evacuation.engine.sim;

import com.evacuation.engine.algorithm.spacetime.SearchResult;
import com.evacuation.engine.algorithm.spacetime.TimeExpandedDijkstra;
import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.dispatch.DispatchResult;
import com.evacuation.engine.dispatch.InstructionSet;
import com.evacuation.engine.dispatch.Party;
import com.evacuation.engine.dispatch.ReservationLedger;
import com.evacuation.engine.graph.overlay.TraversalPolicy;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.TimeModel;
import com.evacuation.engine.model.enums.ShelterStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * The pre-STRIDE baseline for the Phase 5 A/B comparison: every party is routed as if it were the
 * only party in the city. This is the failure mode the whole engine exists to fix, reproduced
 * faithfully rather than strawmanned — a comparison is only meaningful if the thing being beaten is
 * the real historical model, so this class is deliberately, exactly as naive as that model was.
 *
 * <p><strong>How the blindness is implemented.</strong> Each party searches against its own
 * single-use {@link ReservationLedger}, discarded the moment its search returns and never seen by
 * any other party. The ledger is not omitted entirely because a real independent-Dijkstra planner
 * still respected an arc's own physical capacity for the party it was routing; what it could not
 * see was <em>everyone else</em>. A private ledger reproduces precisely that: a party is still
 * rejected from an arc or wait it cannot fit through on its own, and still sees zero contention.
 * Shelter eligibility is blind in the matching way — {@link ShelterStatus#AVAILABLE} and nothing
 * else, with no remaining-capacity accounting, so shelters can be and routinely are oversubscribed.
 * Both halves of the plan are capacity-blind for the same reason, and neither is an oversight.
 *
 * <p><strong>Deliberately out of scope</strong>, because each is itself a STRIDE improvement the
 * baseline predates: no platoon/wave splitting (a party is one indivisible search, however large —
 * the point at which a group becomes flow-over-time is exactly what STRIDE added); no
 * priority-based dispatch ordering (parties are routed in the order the input list gives them);
 * every party departs at bucket 0 (there is no session, epoch, or request-time offsetting concept
 * here at all).
 *
 * <p>A plain constructed object, not a Spring bean: this is research tooling for the harness and
 * must never become reachable as a production routing path. Its residence outside {@code dispatch/}
 * makes that boundary structural rather than merely documented.
 */
public final class IndependentDijkstraStrategy {

    private final TimeExpandedDijkstra timeExpandedDijkstra;
    private final GraphEngineProperties properties;
    private final TimeModel timeModel;

    public IndependentDijkstraStrategy(TimeExpandedDijkstra timeExpandedDijkstra,
                                       GraphEngineProperties properties, TimeModel timeModel) {
        this.timeExpandedDijkstra = timeExpandedDijkstra;
        this.properties = properties;
        this.timeModel = timeModel;
    }

    /**
     * Routes every party independently and assembles the same {@link InstructionSet} shape a real
     * dispatch pass produces, so the harness can score both planners through one code path.
     *
     * @param parties  the parties to route, in the order they will be routed
     * @param snapshot the graph every private ledger is sized against
     * @param policy   the hazard-aware traversal policy each search runs under
     * @return one committed result per feasible party and one shortfall per infeasible one
     */
    public InstructionSet plan(List<Party> parties, GraphSnapshot snapshot, TraversalPolicy policy) {
        List<DispatchResult> committed = new ArrayList<>();
        List<InstructionSet.Shortfall> shortfalls = new ArrayList<>();
        long platoonId = 1L;

        for (Party party : parties) {
            ReservationLedger privateLedger = new ReservationLedger(snapshot, timeModel, properties);

            SearchResult result = timeExpandedDijkstra.searchSpaceTime(
                    policy, party.originNodeIndex(), 0,
                    shelter -> shelter.status() == ShelterStatus.AVAILABLE,
                    party.medicalAssistanceRequired(), privateLedger, party.numberOfPeople());

            if (result.feasible()) {
                committed.add(new DispatchResult(party.partyId(), platoonId++, 0,
                        party.numberOfPeople(), party.priority(),
                        party.medicalAssistanceRequired(), result));
            } else {
                shortfalls.add(new InstructionSet.Shortfall(
                        party.partyId(), party.numberOfPeople(), 0));
            }
        }

        return new InstructionSet(List.copyOf(committed), List.copyOf(shortfalls));
    }
}