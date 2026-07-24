package com.evacuation.engine.repository.disaster;

import com.evacuation.engine.model.entity.Disaster;
import com.evacuation.engine.model.enums.DisasterStatus;
import com.evacuation.engine.model.enums.DisasterType;
import com.evacuation.engine.model.enums.SeverityLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DisasterRepository extends JpaRepository<Disaster, Long> {

    List<Disaster> findByStatus(DisasterStatus status);

    List<Disaster> findBySeverityLevelAndDisasterType(SeverityLevel severityLevel, DisasterType disasterType);

    boolean existsByDisasterName(String disasterName);
}