package com.evacuation.engine.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "routes")
@Data
@NoArgsConstructor
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long routeId;

    private String routeName;

    private String startLocation;

    private String endLocation;

    private double distanceKm;

    private int estimatedTravelTime;

    private boolean blocked;

    private boolean highTraffic;

    private String recommendedVehicle;

}