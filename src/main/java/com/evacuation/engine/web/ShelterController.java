package com.evacuation.engine.web;

import com.evacuation.engine.dto.common.ApiResponse;
import com.evacuation.engine.dto.evacuation.request.ShelterRequestDTO;
import com.evacuation.engine.dto.evacuation.request.ShelterUpdateRequestDTO;
import com.evacuation.engine.dto.evacuation.response.ShelterResponse;
import com.evacuation.engine.service.ShelterService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The shelter administration surface: add a shelter, open/close one, adjust its capacity, list the
 * full inventory. Admin-only for the writes (see {@code SecurityConfig}); the list read is open to
 * either role, the same tier {@code GET /api/graph} sits in, since a USER's map needs to show
 * shelters too. Thin by intent, matching every other controller in this package —
 * {@link ShelterService} does the work, including the graph republish a shelter write requires to
 * take effect.
 */
@RestController
@RequestMapping("/api/shelters")
@RequiredArgsConstructor
public class ShelterController {

    private final ShelterService shelterService;

    /**
     * Adds a new shelter. It is reachable by routing as soon as this returns.
     *
     * @param request the operator's input
     * @return {@code 201 Created} with the persisted shelter
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ShelterResponse>> createShelter(
            @Valid @RequestBody ShelterRequestDTO request) {

        ShelterResponse response = shelterService.createShelter(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Shelter created", response));
    }

    /**
     * Opens/closes a shelter and/or adjusts its capacity.
     *
     * @param id      the shelter to update
     * @param request the fields to change; either may be omitted to leave it as-is
     * @return {@code 200 OK} with the updated shelter
     */
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<ShelterResponse>> updateShelter(
            @PathVariable Long id, @Valid @RequestBody ShelterUpdateRequestDTO request) {

        ShelterResponse response = shelterService.updateShelter(id, request);

        return ResponseEntity.ok(ApiResponse.success("Shelter updated", response));
    }

    /**
     * Lists every shelter, regardless of status — the admin console's full inventory.
     *
     * @return {@code 200 OK} with every persisted shelter
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ShelterResponse>>> listShelters() {
        List<ShelterResponse> responses = shelterService.listShelters();
        return ResponseEntity.ok(ApiResponse.success("Shelters retrieved", responses));
    }
}
