package com.evacuation.engine.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import com.evacuation.engine.model.enums.RoadStatus;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "road_edges",
        indexes = {
                @Index(name = "idx_edge_source_node", columnList = "source_node_id"),
                @Index(name = "idx_edge_destination_node", columnList = "destination_node_id"),
                @Index(name = "idx_edge_status", columnList = "road_status"),
                @Index(name = "idx_edge_source_destination", columnList = "source_node_id, destination_node_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"sourceNode", "destinationNode"})
public class RoadEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "edge_id")
    private Long edgeId;

    @NotBlank(message = "Road name is required")
    @Column(name = "road_name", nullable = false, length = 150)
    private String roadName;

    @NotNull(message = "Source node is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_node_id", nullable = false)
    @JsonIgnoreProperties({"outgoingEdges", "incomingEdges", "hibernateLazyInitializer", "handler"})
    private RoadNode sourceNode;

    @NotNull(message = "Destination node is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_node_id", nullable = false)
    @JsonIgnoreProperties({"outgoingEdges", "incomingEdges", "hibernateLazyInitializer", "handler"})
    private RoadNode destinationNode;

    @NotNull(message = "Distance is required")
    @Positive(message = "Distance must be greater than zero")
    @Column(name = "distance_km", nullable = false)
    private Double distanceKm;

    @NotNull(message = "Estimated travel time is required")
    @Positive(message = "Estimated travel time must be greater than zero")
    @Column(name = "estimated_travel_time_minutes", nullable = false)
    private Double estimatedTravelTimeMinutes;

    @NotNull(message = "Capacity is required")
    @Positive(message = "Capacity must be greater than zero")
    @Column(name = "capacity_persons_per_hour", nullable = false)
    private Double capacityPersonsPerHour;

    @NotNull(message = "Road status is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "road_status", nullable = false, length = 20)
    private RoadStatus roadStatus;

    @NotNull(message = "Bidirectional flag must be set")
    @Column(name = "is_bidirectional", nullable = false)
    private Boolean bidirectional;

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

        if (roadStatus == null) {
            roadStatus = RoadStatus.OPEN;
        }
        if (bidirectional == null) {
            bidirectional = Boolean.TRUE;
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
        if (!(o instanceof RoadEdge)) return false;
        RoadEdge roadEdge = (RoadEdge) o;
        return edgeId != null && edgeId.equals(roadEdge.edgeId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(edgeId);
    }
}