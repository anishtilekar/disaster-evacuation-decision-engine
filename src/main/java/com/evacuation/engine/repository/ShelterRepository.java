package com.evacuation.engine.repository;

import com.evacuation.engine.model.Shelter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShelterRepository extends JpaRepository<Shelter, Long> {

    List<Shelter> findByCapacityGreaterThan(int capacity);

    List<Shelter> findByMedicalFacility(boolean medicalFacility);

}