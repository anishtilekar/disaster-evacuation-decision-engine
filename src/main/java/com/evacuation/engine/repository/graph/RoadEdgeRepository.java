package com.evacuation.engine.repository.graph;

import com.evacuation.engine.model.entity.RoadEdge;
import com.evacuation.engine.model.entity.RoadNode;
import com.evacuation.engine.model.enums.RoadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoadEdgeRepository extends JpaRepository<RoadEdge, Long> {

    List<RoadEdge> findBySourceNode(RoadNode sourceNode);

    List<RoadEdge> findByDestinationNode(RoadNode destinationNode);

    List<RoadEdge> findByRoadStatus(RoadStatus roadStatus);

    /**
     * Bulk-loads every edge with both endpoint nodes eagerly fetched, so the graph
     * builder can compile the in-memory snapshot without triggering N+1 lazy loads
     * across {@code sourceNode} / {@code destinationNode}.
     */
    @Query("select e from RoadEdge e join fetch e.sourceNode join fetch e.destinationNode")
    List<RoadEdge> findAllForGraph();
}