package com.evacuation.engine.web;

import com.evacuation.engine.dto.common.ApiResponse;
import com.evacuation.engine.dto.hazard.request.HazardEventRequest;
import com.evacuation.engine.dto.hazard.response.HazardEventResponse;
import com.evacuation.engine.dto.hazard.response.HazardTimelineResponse;
import com.evacuation.engine.service.HazardService;

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
 * The hazard-input surface: the design's "polygon + growth rate becomes compiled onset intervals"
 * entry point, where a predicted front is reported and immediately becomes something the routing
 * search plans around. The geometry accepted is an expanding circle rather than a polygon, per the
 * entity's own documented simplification.
 *
 * <p>Thin by intent. It validates the body, delegates, and chooses the status code; persistence,
 * compilation, and the cache swap all belong to the service. Listing and resolving arrived once the
 * admin console needed to show which fronts are still active and let an operator retire one — a
 * flood receding or a fire being contained is a real event this model has to be able to represent,
 * not just growth.
 */
@RestController
@RequestMapping("/api/hazards")
@RequiredArgsConstructor
public class HazardController {

    private final HazardService hazardService;

    /**
     * Reports a new hazard event. The hazard is live for routing by the time this returns.
     *
     * @param request the hazard input, validated including the origin's Pune-bounds constraint
     * @return {@code 201 Created} with the persisted hazard event
     */
    @PostMapping
    public ResponseEntity<ApiResponse<HazardEventResponse>> createHazardEvent(
            @Valid @RequestBody HazardEventRequest request) {

        HazardEventResponse response = hazardService.createHazardEvent(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Hazard event created", response));
    }

    /**
     * A compact read of the currently-compiled hazard timeline, for a map overlay to shade cells as
     * the time slider passes each one's lethal onset.
     *
     * @return {@code 200 OK} with the onsets, or a {@code null} data payload if no timeline has been
     *         compiled yet
     */
    @GetMapping("/timeline")
    public ResponseEntity<ApiResponse<HazardTimelineResponse>> getTimeline() {
        HazardTimelineResponse response = hazardService.getTimeline();
        return ResponseEntity.ok(ApiResponse.success("Hazard timeline retrieved", response));
    }

    /**
     * Every currently-active hazard — the admin console's list of fronts still in force.
     *
     * @return {@code 200 OK} with the active hazard events
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<HazardEventResponse>>> listActiveHazards() {
        List<HazardEventResponse> responses = hazardService.listActiveHazards();
        return ResponseEntity.ok(ApiResponse.success("Active hazards retrieved", responses));
    }

    /**
     * Marks a hazard resolved (receded, contained, dissipated) and republishes the timeline so the
     * area it covered is usable again. The next dispatch or improvement pass can then route through
     * it — this call itself does not retroactively touch any committed platoon.
     *
     * @param id the hazard to resolve
     * @return {@code 200 OK} with the updated hazard event
     */
    @PatchMapping("/{id}/resolve")
    public ResponseEntity<ApiResponse<HazardEventResponse>> resolveHazard(@PathVariable Long id) {
        HazardEventResponse response = hazardService.resolveHazard(id);
        return ResponseEntity.ok(ApiResponse.success("Hazard resolved", response));
    }
}