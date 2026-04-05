package com.evacuation.engine.service;

import com.evacuation.engine.model.EvacuationRequest;
import com.evacuation.engine.repository.EvacuationRequestRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EvacuationRequestService {

    private final EvacuationRequestRepository requestRepository;

    public EvacuationRequestService(EvacuationRequestRepository requestRepository) {
        this.requestRepository = requestRepository;
    }

    public List<EvacuationRequest> getAllRequests() {
        return requestRepository.findAll();
    }

    public EvacuationRequest createRequest(EvacuationRequest request) {
        return requestRepository.save(request);
    }
}