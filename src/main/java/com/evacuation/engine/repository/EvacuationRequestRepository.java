package com.evacuation.engine.repository;

import com.evacuation.engine.model.EvacuationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EvacuationRequestRepository extends JpaRepository<EvacuationRequest, Long> {

    List<EvacuationRequest> findByRequestStatus(String requestStatus);

}