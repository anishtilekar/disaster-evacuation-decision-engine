package com.evacuation.engine.repository.graph;

import com.evacuation.engine.model.entity.BlockedRoad;
import com.evacuation.engine.model.entity.RoadEdge;
import com.evacuation.engine.model.enums.BlockageReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BlockedRoadRepository extends JpaRepository<BlockedRoad, Long> {

    List<BlockedRoad> findByActive(Boolean active);

    List<BlockedRoad> findByRoadEdge(RoadEdge roadEdge);

    List<BlockedRoad> findByBlockageReason(BlockageReason blockageReason);
}