package com.evacuation.engine.repository;

import com.evacuation.engine.model.Disaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DisasterRepository extends JpaRepository<Disaster, Long> {

    // Find disasters by status (ACTIVE, RESOLVED, etc.)
    List<Disaster> findByStatus(String status);

    // Find disasters by severity level (LOW, MEDIUM, HIGH)
    List<Disaster> findBySeverityLevel(String severityLevel);

}