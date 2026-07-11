package com.evacuation.engine.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import com.evacuation.engine.model.enums.SeverityLevel;
import com.evacuation.engine.model.enums.DisasterStatus;
import com.evacuation.engine.model.enums.DisasterType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(
        name = "disasters",
        indexes = {
                @Index(name = "idx_disaster_status", columnList = "status"),
                @Index(name = "idx_disaster_type", columnList = "disaster_type")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "disasterZones")
public class Disaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "disaster_id")
    private Long disasterId;

    @NotBlank(message = "Disaster name is required")
    @Column(name = "disaster_name", nullable = false, length = 150)
    private String disasterName;

    @NotNull(message = "Disaster type is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "disaster_type", nullable = false, length = 30)
    private DisasterType disasterType;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    @Column(name = "description", length = 500)
    private String description;

    @NotNull(message = "Severity level is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "severity_level", nullable = false, length = 20)
    private SeverityLevel severityLevel;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @NotNull(message = "Status is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DisasterStatus status;

    @NotBlank(message = "Affected region is required")
    @Column(name = "affected_region", nullable = false, length = 150)
    private String affectedRegion;

    @NotNull(message = "Latitude is required")
    @DecimalMin(value = "-90.0", message = "Latitude must be >= -90")
    @DecimalMax(value = "90.0", message = "Latitude must be <= 90")
    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @NotNull(message = "Longitude is required")
    @DecimalMin(value = "-180.0", message = "Longitude must be >= -180")
    @DecimalMax(value = "180.0", message = "Longitude must be <= 180")
    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @NotNull(message = "Impact radius is required")
    @PositiveOrZero(message = "Impact radius cannot be negative")
    @Column(name = "impact_radius_km", nullable = false)
    private Double impactRadius;

    @OneToMany(mappedBy = "disaster", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @JsonIgnore
    @Builder.Default
    private List<DisasterZone> disasterZones = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();

        if (startTime == null) {
            startTime = now;
        }
        if (severityLevel == null) {
            severityLevel = SeverityLevel.MEDIUM;
        }
        if (status == null) {
            status = DisasterStatus.ACTIVE;
        }
        if (affectedRegion == null || affectedRegion.isBlank()) {
            affectedRegion = "UNKNOWN";
        }

        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void addDisasterZone(DisasterZone zone) {
        disasterZones.add(zone);
        zone.setDisaster(this);
    }

    public void removeDisasterZone(DisasterZone zone) {
        disasterZones.remove(zone);
        zone.setDisaster(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Disaster)) return false;
        Disaster disaster = (Disaster) o;
        return disasterId != null && disasterId.equals(disaster.disasterId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(disasterId);
    }
}