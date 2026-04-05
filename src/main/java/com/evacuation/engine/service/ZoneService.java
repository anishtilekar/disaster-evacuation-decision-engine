package com.evacuation.engine.service;

import com.evacuation.engine.model.Zone;
import com.evacuation.engine.repository.ZoneRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ZoneService {

    private final ZoneRepository zoneRepository;

    public ZoneService(ZoneRepository zoneRepository) {
        this.zoneRepository = zoneRepository;
    }

    public List<Zone> getAllZones() {
        return zoneRepository.findAll();
    }

    public Zone getZoneById(Long id) {
        return zoneRepository.findById(id).orElse(null);
    }

    public Zone createZone(Zone zone) {
        return zoneRepository.save(zone);
    }

    public void deleteZone(Long id) {
        zoneRepository.deleteById(id);
    }
}