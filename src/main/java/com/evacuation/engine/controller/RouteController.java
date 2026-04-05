package com.evacuation.engine.controller;

import com.evacuation.engine.model.Route;
import com.evacuation.engine.service.RouteService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/routes")
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @GetMapping
    public List<Route> getRoutes() {
        return routeService.getAllRoutes();
    }

    @PostMapping
    public Route createRoute(@RequestBody Route route) {
        return routeService.createRoute(route);
    }
}