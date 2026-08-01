package com.evacuation.engine.web;

import com.evacuation.engine.dto.common.ApiResponse;
import com.evacuation.engine.dto.dispatch.response.InstructionSetResponse;
import com.evacuation.engine.dto.evacuation.request.EvacuationRequestDTO;
import com.evacuation.engine.dto.evacuation.response.EvacuationRequestResponse;
import com.evacuation.engine.model.enums.EvacuationStatus;
import com.evacuation.engine.service.DispatchOrchestrationService;
import com.evacuation.engine.service.EvacuationRequestService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The evacuation-request intake surface: where a party's need to evacuate becomes visible to the
 * next dispatch pass. Thin by intent, matching {@link HazardController} and
 * {@link GraphAdminController} — validates the body, delegates, chooses the status code.
 */
@RestController
@RequestMapping("/api/evacuation-requests")
@RequiredArgsConstructor
public class EvacuationRequestController {

    private final EvacuationRequestService evacuationRequestService;
    private final DispatchOrchestrationService dispatchOrchestrationService;

    /**
     * Records a new evacuation request as {@link EvacuationStatus#PENDING}. It becomes eligible for
     * routing on the next {@code POST /api/dispatch/plan} call.
     *
     * @param request the requester's input
     * @return {@code 201 Created} with the persisted request
     */
    @PostMapping
    public ResponseEntity<ApiResponse<EvacuationRequestResponse>> createRequest(
            @Valid @RequestBody EvacuationRequestDTO request) {

        EvacuationRequestResponse response = evacuationRequestService.createRequest(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Evacuation request created", response));
    }

    /**
     * Lists requests by status — most useful with {@code PENDING}, to see the queue a dispatch pass
     * has not yet consumed.
     *
     * @param status the status to filter by, defaulting to {@code PENDING}
     * @return {@code 200 OK} with the matching requests
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<EvacuationRequestResponse>>> listRequests(
            @RequestParam(defaultValue = "PENDING") EvacuationStatus status) {

        List<EvacuationRequestResponse> responses = evacuationRequestService.findByStatus(status);

        return ResponseEntity.ok(ApiResponse.success("Evacuation requests retrieved", responses));
    }

    /**
     * The authenticated caller's own submissions, across every status — the USER-facing view; a USER
     * cannot see anyone else's requests, and {@link #listRequests} (the whole queue) is admin-only.
     *
     * @return {@code 200 OK} with the current principal's requests
     */
    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<List<EvacuationRequestResponse>>> listMine() {
        List<EvacuationRequestResponse> responses = evacuationRequestService.findMine();
        return ResponseEntity.ok(ApiResponse.success("Your evacuation requests retrieved", responses));
    }

    /**
     * Routes exactly this request, immediately, against the live session — a USER's self-service
     * counterpart to the admin-only {@code POST /api/dispatch/plan} batch sweep. Only the request's
     * own submitter or an admin may call this for a given id.
     *
     * @param id the request to route
     * @return {@code 200 OK} with what this call committed and what fell short
     */
    @PostMapping("/{id}/route")
    public ResponseEntity<ApiResponse<InstructionSetResponse>> routeOne(@PathVariable Long id) {
        InstructionSetResponse response = dispatchOrchestrationService.planOne(id, LocalDateTime.now());
        return ResponseEntity.ok(ApiResponse.success("Request routed", response));
    }
}
