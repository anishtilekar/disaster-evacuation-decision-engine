package com.evacuation.engine.repository.evacuation;

import com.evacuation.engine.model.entity.EvacuationRoute;
import com.evacuation.engine.model.entity.RoadNode;
import com.evacuation.engine.model.entity.Shelter;
import com.evacuation.engine.model.enums.RouteStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EvacuationRouteRepository extends JpaRepository<EvacuationRoute, Long> {

    List<EvacuationRoute> findByRouteStatus(RouteStatus routeStatus);

    List<EvacuationRoute> findBySourceNode(RoadNode sourceNode);

    List<EvacuationRoute> findByDestinationShelter(Shelter destinationShelter);
}