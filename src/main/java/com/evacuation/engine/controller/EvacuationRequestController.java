package com.evacuation.engine.controller;

import com.evacuation.engine.model.EvacuationRequest;
import com.evacuation.engine.service.EvacuationRequestService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/requests")
public class EvacuationRequestController {

    private final EvacuationRequestService requestService;

    public EvacuationRequestController(EvacuationRequestService requestService) {
        this.requestService = requestService;
    }

    @PostMapping
    public EvacuationRequest createRequest(@RequestBody EvacuationRequest request) {
        return requestService.createRequest(request);
    }

    @GetMapping
    public List<EvacuationRequest> getRequests() {
        return requestService.getAllRequests();
    }
}