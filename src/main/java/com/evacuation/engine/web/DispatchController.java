package com.evacuation.engine.web;

import com.evacuation.engine.dto.common.ApiResponse;
import com.evacuation.engine.dto.dispatch.response.ImproveResponse;
import com.evacuation.engine.dto.dispatch.response.InstructionSetResponse;
import com.evacuation.engine.service.DispatchOrchestrationService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * The dispatch surface: plan every pending request, read the standing plan, and run an anytime
 * improvement pass — the three operations the map frontend drives directly. Thin by intent, matching
 * every other controller in this package; {@link DispatchOrchestrationService} does the work.
 */
@RestController
@RequestMapping("/api/dispatch")
@RequiredArgsConstructor
public class DispatchController {

    private final DispatchOrchestrationService dispatchOrchestrationService;

    /**
     * Plans every currently-pending evacuation request against the retained session.
     *
     * @return {@code 200 OK} with what this call committed and what fell short
     */
    @PostMapping("/plan")
    public ResponseEntity<ApiResponse<InstructionSetResponse>> plan() {
        InstructionSetResponse response = dispatchOrchestrationService.planPending(LocalDateTime.now());
        return ResponseEntity.ok(ApiResponse.success("Dispatch plan run", response));
    }

    /**
     * The current session's standing plan.
     *
     * @return {@code 200 OK} with every platoon presently committed
     */
    @GetMapping("/plan/current")
    public ResponseEntity<ApiResponse<InstructionSetResponse>> currentPlan() {
        InstructionSetResponse response = dispatchOrchestrationService.currentPlan();
        return ResponseEntity.ok(ApiResponse.success("Current plan retrieved", response));
    }

    /**
     * Runs one anytime-LNS improvement pass against the current session.
     *
     * @param maxIterations the iteration budget, defaulting to 20
     * @return {@code 200 OK} with the pass's own report
     */
    @PostMapping("/improve")
    public ResponseEntity<ApiResponse<ImproveResponse>> improve(
            @RequestParam(defaultValue = "20") int maxIterations) {
        ImproveResponse response = dispatchOrchestrationService.improve(maxIterations);
        return ResponseEntity.ok(ApiResponse.success("Improvement pass run", response));
    }
}
