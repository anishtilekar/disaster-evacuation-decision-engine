package com.evacuation.engine.repository;

import com.evacuation.engine.model.Zone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ZoneRepository extends JpaRepository<Zone, Long> {

    List<Zone> findByRiskLevel(String riskLevel);

    List<Zone> findByEvacuationRequired(boolean evacuationRequired);

}