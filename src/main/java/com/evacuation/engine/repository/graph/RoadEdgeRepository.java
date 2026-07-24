package com.evacuation.engine.repository.graph;

import com.evacuation.engine.model.entity.RoadEdge;
import com.evacuation.engine.model.entity.RoadNode;
import com.evacuation.engine.model.enums.RoadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoadEdgeRepository extends JpaRepository<RoadEdge, Long> {

    List<RoadEdge> findBySourceNode(RoadNode sourceNode);

    List<RoadEdge> findByDestinationNode(RoadNode destinationNode);

    List<RoadEdge> findByRoadStatus(RoadStatus roadStatus);
}