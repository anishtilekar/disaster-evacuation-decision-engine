package com.evacuation.engine.controller;

import com.evacuation.engine.model.Zone;
import com.evacuation.engine.service.ZoneService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/zones")
public class ZoneController {

    private final ZoneService zoneService;

    public ZoneController(ZoneService zoneService) {
        this.zoneService = zoneService;
    }

    @GetMapping
    public List<Zone> getAllZones() {
        return zoneService.getAllZones();
    }

    @PostMapping
    public Zone createZone(@RequestBody Zone zone) {
        return zoneService.createZone(zone);
    }
}