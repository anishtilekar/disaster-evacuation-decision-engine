package com.evacuation.engine.repository.evacuation;

import com.evacuation.engine.model.entity.Shelter;
import com.evacuation.engine.model.enums.ShelterStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShelterRepository extends JpaRepository<Shelter, Long> {

    List<Shelter> findByShelterStatus(ShelterStatus shelterStatus);

    List<Shelter> findByLocation(String location);

    List<Shelter> findByCapacityGreaterThanEqual(Integer capacity);
}