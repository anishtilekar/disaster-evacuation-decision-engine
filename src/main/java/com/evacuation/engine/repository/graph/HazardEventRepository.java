package com.evacuation.engine.repository.graph;

import com.evacuation.engine.model.entity.HazardEvent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HazardEventRepository extends JpaRepository<HazardEvent, Long> {

    // Find hazard events by active flag
    // Used by the hazard timeline compiler to load the events currently in force
    List<HazardEvent> findByActive(Boolean active);
}