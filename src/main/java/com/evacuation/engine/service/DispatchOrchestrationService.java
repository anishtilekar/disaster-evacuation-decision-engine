package com.evacuation.engine.service;

import com.evacuation.engine.algorithm.spacetime.SpaceTimeState;
import com.evacuation.engine.algorithm.spacetime.TimedWalk;
import com.evacuation.engine.dispatch.ActivePlan;
import com.evacuation.engine.dispatch.DispatchResult;
import com.evacuation.engine.dispatch.DispatchService;
import com.evacuation.engine.dispatch.ImprovementLoop;
import com.evacuation.engine.dispatch.InstructionSet;
import com.evacuation.engine.dispatch.LexicographicObjective;
import com.evacuation.engine.dispatch.Party;
import com.evacuation.engine.dto.dispatch.response.ImproveResponse;
import com.evacuation.engine.dto.dispatch.response.InstructionSetResponse;
import com.evacuation.engine.dto.dispatch.response.PlatoonPlanResponse;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.TimeModel;
import com.evacuation.engine.loader.GraphCache;
import com.evacuation.engine.model.entity.EvacuationRequest;
import com.evacuation.engine.model.enums.EvacuationStatus;
import com.evacuation.engine.repository.evacuation.EvacuationRequestRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The persistence-facing seam around the pure dispatch engine: translates
 * {@link EvacuationRequest} rows into {@link Party} objects the engine understands, calls
 * {@link DispatchService}/{@link ImprovementLoop}, and translates the engine's dense-index results
 * back into coordinate-resolved responses a frontend can render directly.
 *
 * <p><strong>Why this exists as its own service rather than living in the engine package.</strong>
 * {@code DispatchService} and {@code ImprovementLoop} deal exclusively in {@code Party}/
 * {@code GraphSnapshot}/{@code InstructionSet} — no JPA, no repositories, no entities. That
 * separation is deliberate throughout this project (see {@code Party}'s own Javadoc on why it exists
 * rather than the engine touching {@code EvacuationRequest} directly), and giving the engine a
 * repository dependency just to serve one REST endpoint would break it. This class is where the two
 * worlds meet instead — the same role {@link GraphAdminService} and {@link HazardService} play for
 * their own endpoints.
 *
 * <p><strong>Status bookkeeping is intentionally coarse.</strong> {@link EvacuationRequest} has one
 * {@link EvacuationStatus} field, not a per-wave one, so a party that had some but not all of its
 * waves committed is marked {@link EvacuationStatus#ASSIGNED} the same as a party fully placed — the
 * response's own {@code shortfalls} list is what carries the precise partial-placement detail. A
 * party with zero platoons committed is left {@code PENDING}, so the next planning pass retries it
 * rather than losing it silently.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DispatchOrchestrationService {

    private final EvacuationRequestRepository evacuationRequestRepository;
    private final GraphCache graphCache;
    private final ActivePlan activePlan;
    private final DispatchService dispatchService;
    private final ImprovementLoop improvementLoop;
    private final TimeModel timeModel;

    /**
     * Plans every currently-{@link EvacuationStatus#PENDING} request in one pass, against the
     * session's retained ledger — a fresh call adds to what is already committed, exactly as
     * {@link DispatchService#plan} itself documents.
     *
     * @param now the wall-clock instant this call is happening at
     * @return the resolved routes this call committed and the parties it could not fully place
     */
    @Transactional
    public InstructionSetResponse planPending(LocalDateTime now) {
        GraphSnapshot snapshot = graphCache.get();
        List<EvacuationRequest> pending =
                evacuationRequestRepository.findByStatus(EvacuationStatus.PENDING);

        if (pending.isEmpty()) {
            log.info("Dispatch plan run: no pending requests");
            return toResponse(new InstructionSet(List.of(), List.of()), snapshot);
        }

        List<Party> parties = pending.stream()
                .map(request -> Party.fromEvacuationRequest(request, snapshot))
                .toList();

        InstructionSet instructionSet = dispatchService.plan(parties, now);

        Set<Long> committedPartyIds = instructionSet.committed().stream()
                .map(DispatchResult::partyId)
                .collect(Collectors.toSet());

        List<EvacuationRequest> assigned = pending.stream()
                .filter(request -> committedPartyIds.contains(request.getEvacuationRequestId()))
                .toList();
        assigned.forEach(request -> request.setStatus(EvacuationStatus.ASSIGNED));
        evacuationRequestRepository.saveAll(assigned);

        log.info("Dispatch plan run: {} pending requests considered, {} assigned, {} platoons committed",
                pending.size(), assigned.size(), instructionSet.committed().size());

        return toResponse(instructionSet, snapshot);
    }

    /**
     * The current session's standing plan — every platoon presently committed. Carries no
     * shortfalls: a shortfall is a property of one specific planning attempt, not of the plan book's
     * standing state, which holds only what is actually committed.
     *
     * @return the resolved current plan, or an empty one if no session is active yet
     */
    @Transactional(readOnly = true)
    public InstructionSetResponse currentPlan() {
        GraphSnapshot snapshot = graphCache.get();
        List<DispatchResult> committed = activePlan.isActive()
                ? activePlan.planBook().all()
                : List.of();
        return toResponse(new InstructionSet(committed, List.of()), snapshot);
    }

    /**
     * Runs one anytime-LNS pass against the current session.
     *
     * @param maxIterations the iteration budget
     * @return the pass's own report; callers re-fetch {@link #currentPlan()} for the routes
     */
    public ImproveResponse improve(int maxIterations) {
        ImprovementLoop.Result result = improvementLoop.improve(maxIterations);
        LexicographicObjective.Score score = result.finalScore();
        return new ImproveResponse(result.iterationsRun(), result.accepted(),
                score.totalPeoplePlaced(), score.weightedPersonMinutes(),
                score.makespanBucket(), score.minSlackBuckets());
    }

    private InstructionSetResponse toResponse(InstructionSet instructionSet, GraphSnapshot snapshot) {
        List<PlatoonPlanResponse> committed = instructionSet.committed().stream()
                .map(result -> toPlatoonPlanResponse(result, snapshot))
                .toList();
        List<InstructionSetResponse.ShortfallResponse> shortfalls = instructionSet.shortfalls().stream()
                .map(s -> new InstructionSetResponse.ShortfallResponse(
                        s.partyId(), s.unroutedSize(), s.failedAtWaveIndex()))
                .toList();

        boolean sessionActive = activePlan.isActive();
        LocalDateTime sessionEpoch = sessionActive ? activePlan.sessionEpoch() : null;

        return new InstructionSetResponse(committed, shortfalls, sessionActive, sessionEpoch,
                timeModel.deltaSeconds(), timeModel.horizonBuckets());
    }

    private PlatoonPlanResponse toPlatoonPlanResponse(DispatchResult result, GraphSnapshot snapshot) {
        TimedWalk walk = result.searchResult().walk();
        GraphSnapshot.ShelterRef shelter = result.searchResult().shelter();

        List<PlatoonPlanResponse.RouteWaypoint> waypoints = new ArrayList<>(walk.steps().size() + 1);
        waypoints.add(waypointOf(walk.origin(), false, snapshot));
        for (TimedWalk.Step step : walk.steps()) {
            waypoints.add(waypointOf(step.to(), step.isWait(), snapshot));
        }

        return new PlatoonPlanResponse(
                result.partyId(), result.platoonId(), result.waveIndex(), result.size(),
                result.priority().name(), result.medicalPreferred(),
                shelter.shelterId(), shelter.shelterName(), shelter.lat(), shelter.lon(),
                waypoints, walk.origin().bucket(), walk.arrival().bucket(),
                walk.totalCost(), walk.totalDistanceKm());
    }

    private PlatoonPlanResponse.RouteWaypoint waypointOf(SpaceTimeState state, boolean isWait,
                                                          GraphSnapshot snapshot) {
        return new PlatoonPlanResponse.RouteWaypoint(
                state.nodeIndex(), snapshot.nodeLat(state.nodeIndex()),
                snapshot.nodeLon(state.nodeIndex()), state.bucket(), isWait);
    }
}
