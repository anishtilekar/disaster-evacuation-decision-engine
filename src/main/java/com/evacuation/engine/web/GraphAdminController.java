package com.evacuation.engine.web;

import com.evacuation.engine.dto.common.ApiResponse;
import com.evacuation.engine.dto.graph.request.BlockedRoadRequest;
import com.evacuation.engine.dto.graph.response.BlockedRoadResponse;
import com.evacuation.engine.service.GraphAdminService;

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
 * The road block/unblock surface: where an operator reports that a stretch of road has become
 * impassable, or that it is open again, and the routing engine plans around it from that moment on.
 *
 * <p>Thin by intent, matching {@link HazardController}. It validates the body, delegates, and chooses
 * the status code; resolving the edge and disaster references, persisting the blockage, and
 * recompiling and swapping the hazard timeline all belong to the service. The list endpoint exists
 * for the admin console to show what is actually closed right now — it needed exactly that, so it
 * arrived alongside the panel that consumes it, not in anticipation.
 */
@RestController
@RequestMapping("/api/graph/blocked-roads")
@RequiredArgsConstructor
public class GraphAdminController {

    private final GraphAdminService graphAdminService;

    /**
     * Reports a road as blocked. The blockage is live for routing by the time this returns.
     *
     * @param request the blockage input, validated including its edge and disaster references
     * @return {@code 201 Created} with the persisted blockage
     */
    @PostMapping
    public ResponseEntity<ApiResponse<BlockedRoadResponse>> blockRoad(
            @Valid @RequestBody BlockedRoadRequest request) {

        BlockedRoadResponse response = graphAdminService.blockRoad(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Road blocked", response));
    }

    /**
     * Lifts an existing blockage. The road is usable by the next route planned after this returns.
     *
     * @param blockedRoadId the blockage to deactivate
     * @return {@code 200 OK} with the deactivated blockage
     */
    @PatchMapping("/{blockedRoadId}/unblock")
    public ResponseEntity<ApiResponse<BlockedRoadResponse>> unblockRoad(
            @PathVariable Long blockedRoadId) {

        BlockedRoadResponse response = graphAdminService.unblockRoad(blockedRoadId);

        return ResponseEntity.ok(ApiResponse.success("Road unblocked", response));
    }

    /**
     * Every currently-active blockage — the admin console's list of what's actually closed right now.
     *
     * @return {@code 200 OK} with the active blockages
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<BlockedRoadResponse>>> listActiveBlockedRoads() {
        List<BlockedRoadResponse> responses = graphAdminService.listActiveBlockedRoads();
        return ResponseEntity.ok(ApiResponse.success("Active blocked roads retrieved", responses));
    }
}