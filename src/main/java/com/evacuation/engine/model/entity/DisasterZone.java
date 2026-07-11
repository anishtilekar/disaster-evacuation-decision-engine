package com.evacuation.engine.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import com.evacuation.engine.model.enums.RiskLevel;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "disaster_zones",
        indexes = {
                @Index(name = "idx_zone_disaster", columnList = "disaster_id"),
                @Index(name = "idx_zone_risk_level", columnList = "risk_level"),
                @Index(name = "idx_zone_evacuation_required", columnList = "evacuation_required")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "disaster")
public class DisasterZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "zone_id")
    private Long zoneId;

    @NotBlank(message = "Zone name is required")
    @Column(name = "zone_name", nullable = false, length = 150)
    private String zoneName;

    @NotNull(message = "Risk level is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 20)
    private RiskLevel riskLevel;

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

    @NotNull(message = "Population is required")
    @PositiveOrZero(message = "Population cannot be negative")
    @Column(name = "population", nullable = false)
    private Integer population;

    @NotNull(message = "Evacuation required flag must be set")
    @Column(name = "evacuation_required", nullable = false)
    private Boolean evacuationRequired;

    @NotNull(message = "Disaster reference is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "disaster_id", nullable = false)
    @JsonIgnoreProperties({"disasterZones", "hibernateLazyInitializer", "handler"})
    private Disaster disaster;

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

        if (riskLevel == null) {
            riskLevel = RiskLevel.MODERATE;
        }
        if (evacuationRequired == null) {
            evacuationRequired = Boolean.FALSE;
        }

        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DisasterZone)) return false;
        DisasterZone that = (DisasterZone) o;
        return zoneId != null && zoneId.equals(that.zoneId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(zoneId);
    }
}