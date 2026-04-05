package com.evacuation.engine.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
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

    @NotBlank
    @Column(nullable = false)
    private String disasterName;

    @NotBlank
    private String disasterType;

    @Column(length = 500)
    private String description;

    @NotBlank
    private String severityLevel;

    @NotNull
    private LocalDateTime startTime;

    private LocalDateTime endTime;

    @NotBlank
    private String status;

    @NotBlank
    private String affectedRegion;

    private double latitude;

    private double longitude;

    private double impactRadius;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}