package com.evacuation.engine.model;

import jakarta.persistence.*;

@Entity
@Table(name = "routes")
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

    public Route() {}

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    public String getRouteName() {
        return routeName;
    }

    public void setRouteName(String routeName) {
        this.routeName = routeName;
    }

    public String getStartLocation() {
        return startLocation;
    }

    public void setStartLocation(String startLocation) {
        this.startLocation = startLocation;
    }

    public String getEndLocation() {
        return endLocation;
    }

    public void setEndLocation(String endLocation) {
        this.endLocation = endLocation;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(double distanceKm) {
        this.distanceKm = distanceKm;
    }

    public int getEstimatedTravelTime() {
        return estimatedTravelTime;
    }

    public void setEstimatedTravelTime(int estimatedTravelTime) {
        this.estimatedTravelTime = estimatedTravelTime;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    public boolean isHighTraffic() {
        return highTraffic;
    }

    public void setHighTraffic(boolean highTraffic) {
        this.highTraffic = highTraffic;
    }

    public String getRecommendedVehicle() {
        return recommendedVehicle;
    }

    public void setRecommendedVehicle(String recommendedVehicle) {
        this.recommendedVehicle = recommendedVehicle;
    }
}
