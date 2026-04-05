package com.evacuation.engine.controller;

import com.evacuation.engine.model.Shelter;
import com.evacuation.engine.service.ShelterService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/shelters")
public class ShelterController {

    private final ShelterService shelterService;

    public ShelterController(ShelterService shelterService) {
        this.shelterService = shelterService;
    }

    @GetMapping
    public List<Shelter> getAllShelters() {
        return shelterService.getAllShelters();
    }

    @PostMapping
    public Shelter addShelter(@RequestBody Shelter shelter) {
        return shelterService.addShelter(shelter);
    }
}