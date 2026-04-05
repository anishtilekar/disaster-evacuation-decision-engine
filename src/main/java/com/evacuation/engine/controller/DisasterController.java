package com.evacuation.engine.controller;

import com.evacuation.engine.model.Disaster;
import com.evacuation.engine.service.DisasterService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/disasters")
public class DisasterController {

    private final DisasterService disasterService;

    public DisasterController(DisasterService disasterService) {
        this.disasterService = disasterService;
    }

    @GetMapping
    public ResponseEntity<List<Disaster>> getAllDisasters() {
        List<Disaster> disasters = disasterService.getAllDisasters();
        return ResponseEntity.ok(disasters);
    }

    @PostMapping
    public ResponseEntity<Disaster> createDisaster(@Valid @RequestBody Disaster disaster) {
        Disaster savedDisaster = disasterService.createDisaster(disaster);
        return ResponseEntity.ok(savedDisaster);
    }
}