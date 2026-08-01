package com.evacuation.engine.service;

import com.evacuation.engine.dispatch.ActivePlan;
import com.evacuation.engine.dto.evacuation.request.ShelterRequestDTO;
import com.evacuation.engine.dto.evacuation.request.ShelterUpdateRequestDTO;
import com.evacuation.engine.dto.evacuation.response.ShelterResponse;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.HazardTimelineCache;
import com.evacuation.engine.loader.GraphCache;
import com.evacuation.engine.mapper.evacuation.ShelterMapper;
import com.evacuation.engine.model.entity.Shelter;
import com.evacuation.engine.model.enums.NodeType;
import com.evacuation.engine.model.enums.RoadStatus;
import com.evacuation.engine.model.enums.ShelterStatus;
import com.evacuation.engine.repository.evacuation.ShelterRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the sequencing {@link ShelterService}'s class Javadoc claims: a write always republishes
 * the graph (reload, then recompile the timeline against it), the recompile is anchored to the live
 * session's fixed epoch when one exists and to {@code now()} otherwise, and — the specific claim the
 * Part 3 plan singles out — a shelter write never touches {@link ActivePlan#reset}, so an active
 * session's reservation ledger is never discarded by it.
 */
class ShelterServiceTest {

    private static final long GRAPH_VERSION = 7L;
    private static final long SHELTER_ID = 42L;

    /** A minimal, structurally valid snapshot -- ShelterService never reads its contents. */
    private GraphSnapshot minimalSnapshot() {
        return new GraphSnapshot(new long[0], new String[0], new double[0], new double[0],
                new NodeType[0], new boolean[0], new double[0], Map.of(), new int[]{0}, new int[0],
                new long[0], new double[0], new double[0], new double[0], new RoadStatus[0],
                List.of(), GRAPH_VERSION, LocalDateTime.now());
    }

    private ShelterRequestDTO buildRequest() {
        return ShelterRequestDTO.builder()
                .shelterName("Test Shelter")
                .location("Somewhere")
                .capacity(200)
                .contactNumber("9876543210")
                .build();
    }

    private Shelter buildSavedShelter(Integer capacity, ShelterStatus status) {
        return Shelter.builder()
                .shelterId(SHELTER_ID)
                .shelterName("Test Shelter")
                .location("Somewhere")
                .latitude(18.52)
                .longitude(73.85)
                .capacity(capacity)
                .currentOccupancy(0)
                .medicalFacility(false)
                .foodSupply(false)
                .waterSupply(false)
                .contactNumber("9876543210")
                .shelterStatus(status)
                .build();
    }

    private record Harness(ShelterRepository shelterRepository, ShelterMapper shelterMapper,
                           GraphCache graphCache, HazardTimelineCache hazardTimelineCache,
                           ActivePlan activePlan, ShelterService shelterService) {
    }

    private Harness buildHarness() {
        ShelterRepository shelterRepository = mock(ShelterRepository.class);
        ShelterMapper shelterMapper = mock(ShelterMapper.class);
        GraphCache graphCache = mock(GraphCache.class);
        HazardTimelineCache hazardTimelineCache = mock(HazardTimelineCache.class);
        ActivePlan activePlan = mock(ActivePlan.class);

        ShelterService shelterService = new ShelterService(
                shelterRepository, shelterMapper, graphCache, hazardTimelineCache, activePlan);

        return new Harness(shelterRepository, shelterMapper, graphCache, hazardTimelineCache,
                activePlan, shelterService);
    }

    @Test
    @DisplayName("Creating a shelter reloads the graph and recompiles the timeline against the live session's fixed epoch")
    void createShelterRepublishesGraphAgainstLiveSessionEpoch() {
        Harness harness = buildHarness();
        ShelterRequestDTO request = buildRequest();
        Shelter mapped = Shelter.builder().shelterName("Test Shelter").build();
        Shelter saved = buildSavedShelter(200, ShelterStatus.AVAILABLE);
        GraphSnapshot reloadedSnapshot = minimalSnapshot();
        LocalDateTime sessionEpoch = LocalDateTime.now().minusMinutes(10);

        when(harness.shelterMapper().toEntity(request)).thenReturn(mapped);
        when(harness.shelterRepository().save(mapped)).thenReturn(saved);
        when(harness.graphCache().reload()).thenReturn(reloadedSnapshot);
        when(harness.activePlan().isActive()).thenReturn(true);
        when(harness.activePlan().sessionEpoch()).thenReturn(sessionEpoch);
        when(harness.shelterMapper().toResponse(saved))
                .thenReturn(ShelterResponse.builder().shelterId(SHELTER_ID).build());

        ShelterResponse response = harness.shelterService().createShelter(request);

        assertEquals(SHELTER_ID, response.getShelterId());
        verify(harness.graphCache()).reload();
        verify(harness.hazardTimelineCache()).reload(reloadedSnapshot, sessionEpoch);
        // The claim under test: a shelter write must never discard the live session's ledger.
        verify(harness.activePlan(), never()).reset(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Creating a shelter with no active session anchors the recompile to now(), not sessionEpoch()")
    void createShelterUsesNowWhenNoSessionIsActive() {
        Harness harness = buildHarness();
        ShelterRequestDTO request = buildRequest();
        Shelter mapped = Shelter.builder().shelterName("Test Shelter").build();
        Shelter saved = buildSavedShelter(200, ShelterStatus.AVAILABLE);
        GraphSnapshot reloadedSnapshot = minimalSnapshot();

        when(harness.shelterMapper().toEntity(request)).thenReturn(mapped);
        when(harness.shelterRepository().save(mapped)).thenReturn(saved);
        when(harness.graphCache().reload()).thenReturn(reloadedSnapshot);
        when(harness.activePlan().isActive()).thenReturn(false);
        when(harness.shelterMapper().toResponse(saved)).thenReturn(ShelterResponse.builder().build());

        LocalDateTime before = LocalDateTime.now();
        harness.shelterService().createShelter(request);
        LocalDateTime after = LocalDateTime.now();

        var epochCaptor = forClass(LocalDateTime.class);
        verify(harness.hazardTimelineCache()).reload(eq(reloadedSnapshot), epochCaptor.capture());
        LocalDateTime usedEpoch = epochCaptor.getValue();

        assertTrue(!usedEpoch.isBefore(before) && !usedEpoch.isAfter(after),
                "expected an epoch taken from now(), got " + usedEpoch);
        // sessionEpoch() throws when no session is active -- never called is what proves the
        // isActive() branch, not sessionEpoch() itself, decided this.
        verify(harness.activePlan(), never()).sessionEpoch();
    }

    @Test
    @DisplayName("Updating a shelter's capacity and status persists both changes and republishes the graph")
    void updateShelterAppliesPartialChangesAndRepublishesGraph() {
        Harness harness = buildHarness();
        Shelter existing = buildSavedShelter(200, ShelterStatus.AVAILABLE);
        Shelter afterSave = buildSavedShelter(50, ShelterStatus.TEMPORARILY_CLOSED);
        GraphSnapshot reloadedSnapshot = minimalSnapshot();

        when(harness.shelterRepository().findById(SHELTER_ID)).thenReturn(Optional.of(existing));
        when(harness.shelterRepository().save(existing)).thenReturn(afterSave);
        when(harness.graphCache().reload()).thenReturn(reloadedSnapshot);
        when(harness.activePlan().isActive()).thenReturn(false);
        when(harness.shelterMapper().toResponse(afterSave)).thenReturn(
                ShelterResponse.builder().shelterId(SHELTER_ID).capacity(50)
                        .shelterStatus(ShelterStatus.TEMPORARILY_CLOSED).build());

        ShelterUpdateRequestDTO update = ShelterUpdateRequestDTO.builder()
                .capacity(50).shelterStatus(ShelterStatus.TEMPORARILY_CLOSED).build();

        ShelterResponse response = harness.shelterService().updateShelter(SHELTER_ID, update);

        assertEquals(50, response.getCapacity());
        assertEquals(ShelterStatus.TEMPORARILY_CLOSED, response.getShelterStatus());
        assertEquals(50, existing.getCapacity(), "the entity itself must carry the new capacity into save()");
        assertEquals(ShelterStatus.TEMPORARILY_CLOSED, existing.getShelterStatus());
        verify(harness.graphCache()).reload();
        verify(harness.activePlan(), never()).reset(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Updating a nonexistent shelter throws rather than silently doing nothing")
    void updateShelterThrowsWhenNotFound() {
        Harness harness = buildHarness();
        when(harness.shelterRepository().findById(SHELTER_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> harness.shelterService()
                .updateShelter(SHELTER_ID, ShelterUpdateRequestDTO.builder().capacity(10).build()));

        verify(harness.graphCache(), never()).reload();
    }
}
