package com.evacuation.engine.service;

import com.evacuation.engine.model.Disaster;
import com.evacuation.engine.repository.DisasterRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DisasterService {

    private final DisasterRepository disasterRepository;

    public DisasterService(DisasterRepository disasterRepository) {
        this.disasterRepository = disasterRepository;
    }

    public List<Disaster> getAllDisasters() {
        return disasterRepository.findAll();
    }

    public Disaster getDisasterById(Long id) {
        return disasterRepository.findById(id).orElse(null);
    }

    public Disaster createDisaster(Disaster disaster) {
        return disasterRepository.save(disaster);
    }

    public Disaster updateDisaster(Long id, Disaster updatedDisaster) {

        Disaster disaster = disasterRepository.findById(id).orElseThrow();

        disaster.setDisasterName(updatedDisaster.getDisasterName());
        disaster.setSeverityLevel(updatedDisaster.getSeverityLevel());
        disaster.setStatus(updatedDisaster.getStatus());

        return disasterRepository.save(disaster);
    }

    public void deleteDisaster(Long id) {
        disasterRepository.deleteById(id);
    }
}