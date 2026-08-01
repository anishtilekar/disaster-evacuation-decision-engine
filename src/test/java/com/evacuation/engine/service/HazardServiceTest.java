package com.evacuation.engine.service;

import com.evacuation.engine.dispatch.ActivePlan;
import com.evacuation.engine.dispatch.RepairService;
import com.evacuation.engine.dto.hazard.response.HazardEventResponse;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimelineCache;
import com.evacuation.engine.graph.time.TimeModel;
import com.evacuation.engine.loader.GraphCache;
import com.evacuation.engine.mapper.hazard.HazardEventMapper;
import com.evacuation.engine.model.entity.HazardEvent;
import com.evacuation.engine.model.enums.DisasterType;
import com.evacuation.engine.model.enums.NodeType;
import com.evacuation.engine.model.enums.RoadStatus;
import com.evacuation.engine.repository.graph.HazardEventRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link HazardService#resolveHazard}, the capacity-restoring counterpart to
 * {@link HazardService#createHazardEvent}: a front that has receded or been contained deactivates,
 * republishes against the right epoch, and — the specific claim its own Javadoc makes — never calls
 * {@link RepairService}, since adding capacity/safety back invalidates nothing already committed.
 * Mirrors {@code ShelterServiceTest}'s style for the identical shape of claim.
 */
class HazardServiceTest {

    private static final long GRAPH_VERSION = 7L;
    private static final long HAZARD_ID = 42L;

    private GraphSnapshot minimalSnapshot() {
        return new GraphSnapshot(new long[0], new String[0], new double[0], new double[0],
                new NodeType[0], new boolean[0], new double[0], Map.of(), new int[]{0}, new int[0],
                new long[0], new double[0], new double[0], new double[0], new RoadStatus[0],
                List.of(), GRAPH_VERSION, LocalDateTime.now());
    }

    private HazardEvent buildActiveHazard() {
        return HazardEvent.builder()
                .hazardEventId(HAZARD_ID)
                .hazardType(DisasterType.FLOOD)
                .originLatitude(18.5)
                .originLongitude(73.8)
                .initialRadiusMeters(50.0)
                .growthRateMetersPerMinute(40.0)
                .leadingRiskBufferMeters(0.0)
                .riskFactor(0.0)
                .eventStartTime(LocalDateTime.now())
                .active(true)
                .build();
    }

    private record Harness(HazardEventRepository hazardEventRepository, HazardEventMapper hazardEventMapper,
                           GraphCache graphCache, HazardTimelineCache hazardTimelineCache,
                           ActivePlan activePlan, RepairService repairService, TimeModel timeModel,
                           HazardService hazardService) {
    }

    private Harness buildHarness() {
        HazardEventRepository hazardEventRepository = mock(HazardEventRepository.class);
        HazardEventMapper hazardEventMapper = mock(HazardEventMapper.class);
        GraphCache graphCache = mock(GraphCache.class);
        HazardTimelineCache hazardTimelineCache = mock(HazardTimelineCache.class);
        ActivePlan activePlan = mock(ActivePlan.class);
        RepairService repairService = mock(RepairService.class);
        TimeModel timeModel = mock(TimeModel.class);

        HazardService hazardService = new HazardService(hazardEventRepository, hazardEventMapper,
                graphCache, hazardTimelineCache, activePlan, repairService, timeModel);

        return new Harness(hazardEventRepository, hazardEventMapper, graphCache, hazardTimelineCache,
                activePlan, repairService, timeModel, hazardService);
    }

    @Test
    @DisplayName("Resolving a hazard deactivates it, recompiles against the live session's fixed epoch, and never calls repair")
    void resolveHazardDeactivatesAndRepublishesWithoutRepair() {
        Harness h = buildHarness();
        HazardEvent existing = buildActiveHazard();
        GraphSnapshot snapshot = minimalSnapshot();
        LocalDateTime sessionEpoch = LocalDateTime.now().minusMinutes(5);

        when(h.hazardEventRepository().findById(HAZARD_ID)).thenReturn(Optional.of(existing));
        when(h.hazardEventRepository().save(existing)).thenReturn(existing);
        when(h.graphCache().get()).thenReturn(snapshot);
        when(h.activePlan().isActive()).thenReturn(true);
        when(h.activePlan().sessionEpoch()).thenReturn(sessionEpoch);
        when(h.hazardEventMapper().toResponse(existing)).thenReturn(
                HazardEventResponse.builder().hazardEventId(HAZARD_ID).active(false).build());

        HazardEventResponse response = h.hazardService().resolveHazard(HAZARD_ID);

        assertEquals(HAZARD_ID, response.getHazardEventId());
        assertFalse(existing.getActive(), "the entity itself must be deactivated before save()");
        verify(h.hazardTimelineCache()).reload(snapshot, sessionEpoch);
        // The claim under test: resolving a hazard must never trigger a repair pass.
        verifyNoInteractions(h.repairService());
    }

    @Test
    @DisplayName("Resolving with no active session anchors the recompile to now(), not sessionEpoch()")
    void resolveHazardUsesNowWhenNoSessionIsActive() {
        Harness h = buildHarness();
        HazardEvent existing = buildActiveHazard();
        GraphSnapshot snapshot = minimalSnapshot();

        when(h.hazardEventRepository().findById(HAZARD_ID)).thenReturn(Optional.of(existing));
        when(h.hazardEventRepository().save(existing)).thenReturn(existing);
        when(h.graphCache().get()).thenReturn(snapshot);
        when(h.activePlan().isActive()).thenReturn(false);
        when(h.hazardEventMapper().toResponse(existing))
                .thenReturn(HazardEventResponse.builder().hazardEventId(HAZARD_ID).build());

        LocalDateTime before = LocalDateTime.now();
        h.hazardService().resolveHazard(HAZARD_ID);
        LocalDateTime after = LocalDateTime.now();

        var epochCaptor = forClass(LocalDateTime.class);
        verify(h.hazardTimelineCache()).reload(eq(snapshot), epochCaptor.capture());
        LocalDateTime usedEpoch = epochCaptor.getValue();

        assertTrue(!usedEpoch.isBefore(before) && !usedEpoch.isAfter(after),
                "expected an epoch taken from now(), got " + usedEpoch);
        verify(h.activePlan(), never()).sessionEpoch();
    }

    @Test
    @DisplayName("Resolving a nonexistent hazard throws rather than silently doing nothing")
    void resolveHazardThrowsWhenNotFound() {
        Harness h = buildHarness();
        when(h.hazardEventRepository().findById(HAZARD_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> h.hazardService().resolveHazard(HAZARD_ID));

        verify(h.graphCache(), never()).get();
        verifyNoInteractions(h.repairService());
    }

    @Test
    @DisplayName("listActiveHazards maps every active hazard event")
    void listActiveHazardsMapsEveryActiveEvent() {
        Harness h = buildHarness();
        HazardEvent active = buildActiveHazard();
        when(h.hazardEventRepository().findByActive(true)).thenReturn(List.of(active));
        when(h.hazardEventMapper().toResponse(active))
                .thenReturn(HazardEventResponse.builder().hazardEventId(HAZARD_ID).active(true).build());

        List<HazardEventResponse> result = h.hazardService().listActiveHazards();

        assertEquals(1, result.size());
        assertEquals(HAZARD_ID, result.get(0).getHazardEventId());
    }
}
