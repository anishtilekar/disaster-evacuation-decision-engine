package com.evacuation.engine.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "disasters")
@Data
@NoArgsConstructor
public class Disaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long disasterId;

    @Column(nullable = false)
    private String disasterName;

    private String disasterType;

    @Column(length = 500)
    private String description;

    private String severityLevel;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private String status;

    private String affectedRegion;

    private double latitude;

    private double longitude;

    private double impactRadius;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {

        if (startTime == null) {
            startTime = LocalDateTime.now();
        }

        if (severityLevel == null) {
            severityLevel = "MEDIUM";
        }

        if (status == null) {
            status = "ACTIVE";
        }

        if (affectedRegion == null) {
            affectedRegion = "UNKNOWN";
        }

        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}