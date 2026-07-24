package com.evacuation.engine.repository.disaster;

import com.evacuation.engine.model.entity.DisasterZone;
import com.evacuation.engine.model.enums.RiskLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DisasterZoneRepository extends JpaRepository<DisasterZone, Long> {

    List<DisasterZone> findByDisaster_DisasterId(Long disasterId);

    List<DisasterZone> findByRiskLevel(RiskLevel riskLevel);
}