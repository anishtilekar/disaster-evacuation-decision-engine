package com.evacuation.engine.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import com.evacuation.engine.model.enums.BlockageReason;
import com.evacuation.engine.model.enums.SeverityLevel;


import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "blocked_roads",
        indexes = {
                @Index(name = "idx_blocked_road_disaster", columnList = "disaster_id"),
                @Index(name = "idx_blocked_road_edge", columnList = "road_edge_id"),
                @Index(name = "idx_blocked_road_active", columnList = "is_active"),
                @Index(name = "idx_blocked_road_blocked_at", columnList = "blocked_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"disaster", "roadEdge"})
public class BlockedRoad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "blocked_road_id")
    private Long blockedRoadId;

    @NotNull(message = "Disaster reference is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "disaster_id", nullable = false)
    @JsonIgnoreProperties({"disasterZones", "hibernateLazyInitializer", "handler"})
    private Disaster disaster;

    @NotNull(message = "Road edge reference is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "road_edge_id", nullable = false)
    @JsonIgnoreProperties({"sourceNode", "destinationNode", "hibernateLazyInitializer", "handler"})
    private RoadEdge roadEdge;

    @NotNull(message = "Blockage reason is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "blockage_reason", nullable = false, length = 40)
    private BlockageReason blockageReason;

    @NotNull(message = "Impact severity is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "impact_severity", nullable = false, length = 20)
    private SeverityLevel impactSeverity;

    @NotNull(message = "Blocked at timestamp is required")
    @Column(name = "blocked_at", nullable = false)
    private LocalDateTime blockedAt;

    @Column(name = "expected_clear_time")
    private LocalDateTime expectedClearTime;

    @NotNull(message = "Active status must be set")
    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @Size(max = 500, message = "Remarks cannot exceed 500 characters")
    @Column(name = "remarks", length = 500)
    private String remarks;

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

        if (blockedAt == null) {
            blockedAt = now;
        }
        if (active == null) {
            active = Boolean.TRUE;
        }
        if (impactSeverity == null) {
            impactSeverity = SeverityLevel.MEDIUM;
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
        if (!(o instanceof BlockedRoad)) return false;
        BlockedRoad that = (BlockedRoad) o;
        return blockedRoadId != null && blockedRoadId.equals(that.blockedRoadId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(blockedRoadId);
    }
}