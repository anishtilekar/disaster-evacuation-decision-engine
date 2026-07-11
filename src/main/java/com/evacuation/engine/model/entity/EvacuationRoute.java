package com.evacuation.engine.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import com.evacuation.engine.model.enums.RouteStatus;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "evacuation_routes",
        indexes = {
                @Index(name = "idx_route_source_node", columnList = "source_node_id"),
                @Index(name = "idx_route_shelter", columnList = "shelter_id"),
                @Index(name = "idx_route_status", columnList = "route_status"),
                @Index(name = "idx_route_created_at", columnList = "created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"sourceNode", "destinationShelter"})
public class EvacuationRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "route_id")
    private Long routeId;

    @NotBlank(message = "Route name is required")
    @Column(name = "route_name", nullable = false, length = 150)
    private String routeName;

    @NotNull(message = "Source node is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_node_id", nullable = false)
    @JsonIgnoreProperties({"outgoingEdges", "incomingEdges", "hibernateLazyInitializer", "handler"})
    private RoadNode sourceNode;

    @NotNull(message = "Destination shelter is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shelter_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Shelter destinationShelter;

    @NotNull(message = "Total distance is required")
    @Positive(message = "Total distance must be greater than zero")
    @Column(name = "total_distance_km", nullable = false)
    private Double totalDistanceKm;

    @NotNull(message = "Estimated travel time is required")
    @Positive(message = "Estimated travel time must be greater than zero")
    @Column(name = "estimated_travel_time_minutes", nullable = false)
    private Double estimatedTravelTimeMinutes;

    @DecimalMin(value = "0.0", message = "Safety score must be >= 0")
    @DecimalMax(value = "100.0", message = "Safety score must be <= 100")
    @Column(name = "safety_score")
    private Double safetyScore;

    @NotNull(message = "Route status is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "route_status", nullable = false, length = 20)
    private RouteStatus routeStatus;

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

        if (routeStatus == null) {
            routeStatus = RouteStatus.PLANNED;
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
        if (!(o instanceof EvacuationRoute)) return false;
        EvacuationRoute that = (EvacuationRoute) o;
        return routeId != null && routeId.equals(that.routeId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(routeId);
    }
}