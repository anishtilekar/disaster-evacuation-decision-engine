package com.evacuation.engine.service;

import com.evacuation.engine.model.Shelter;
import com.evacuation.engine.repository.ShelterRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ShelterService {

    private final ShelterRepository shelterRepository;

    public ShelterService(ShelterRepository shelterRepository) {
        this.shelterRepository = shelterRepository;
    }

    public List<Shelter> getAllShelters() {
        return shelterRepository.findAll();
    }

    public Shelter addShelter(Shelter shelter) {
        return shelterRepository.save(shelter);
    }

    public void deleteShelter(Long id) {
        shelterRepository.deleteById(id);
    }
}
