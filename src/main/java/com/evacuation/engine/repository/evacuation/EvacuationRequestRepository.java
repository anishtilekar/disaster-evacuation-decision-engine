package com.evacuation.engine.repository.evacuation;

import com.evacuation.engine.model.entity.EvacuationRequest;
import com.evacuation.engine.model.enums.EvacuationPriority;
import com.evacuation.engine.model.enums.EvacuationStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EvacuationRequestRepository extends JpaRepository<EvacuationRequest, Long> {


    // Find evacuation requests by current lifecycle status
    List<EvacuationRequest> findByStatus(EvacuationStatus status);


    // Find evacuation requests by urgency level
    List<EvacuationRequest> findByPriority(EvacuationPriority priority);


    // Find all evacuation requests belonging to a disaster event
    List<EvacuationRequest> findByDisaster_DisasterId(Long disasterId);


    // Find evacuation requests originating from a specific road node
    // Used by route optimization and graph traversal
    List<EvacuationRequest> findBySourceRoadNode_RoadNodeId(Long roadNodeId);


    // Find active evacuation requests from a disaster zone
    // Useful for zone-wise evacuation management
    List<EvacuationRequest> findByDisasterZone_DisasterZoneIdAndStatus(
            Long disasterZoneId,
            EvacuationStatus status
    );


    // Find high-priority pending evacuations
    // Useful for emergency response queue
    List<EvacuationRequest> findByPriorityAndStatus(
            EvacuationPriority priority,
            EvacuationStatus status
    );

}