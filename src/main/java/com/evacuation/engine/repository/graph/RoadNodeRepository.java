package com.evacuation.engine.repository.graph;

import com.evacuation.engine.model.entity.RoadNode;
import com.evacuation.engine.model.enums.NodeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoadNodeRepository extends JpaRepository<RoadNode, Long> {

    List<RoadNode> findByNodeType(NodeType nodeType);

    List<RoadNode> findByActive(Boolean active);
}