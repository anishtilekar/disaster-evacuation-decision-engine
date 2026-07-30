package com.evacuation.engine.web;

import com.evacuation.engine.dto.common.ApiResponse;
import com.evacuation.engine.dto.evacuation.request.EvacuationRequestDTO;
import com.evacuation.engine.dto.evacuation.response.EvacuationRequestResponse;
import com.evacuation.engine.model.enums.EvacuationStatus;
import com.evacuation.engine.service.EvacuationRequestService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}
