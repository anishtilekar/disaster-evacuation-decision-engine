package com.evacuation.engine.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(
        name = "road_nodes",
        indexes = {
                @Index(name = "idx_road_node_type", columnList = "node_type"),
                @Index(name = "idx_road_node_lat_lng", columnList = "latitude, longitude")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"outgoingEdges", "incomingEdges"})
public class RoadNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "node_id")
    private Long nodeId;

    @NotBlank(message = "Node name is required")
    @Column(name = "node_name", nullable = false, length = 150)
    private String nodeName;

    @NotNull(message = "Node type is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false, length = 30)
    private NodeType nodeType;

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

    @NotNull(message = "Active status must be set")
    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @OneToMany(mappedBy = "sourceNode", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    @Builder.Default
    private List<RoadEdge> outgoingEdges = new ArrayList<>();

    @OneToMany(mappedBy = "destinationNode", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    @Builder.Default
    private List<RoadEdge> incomingEdges = new ArrayList<>();

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

        if (active == null) {
            active = Boolean.TRUE;
        }

        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void addOutgoingEdge(RoadEdge edge) {
        outgoingEdges.add(edge);
        edge.setSourceNode(this);
    }

    public void removeOutgoingEdge(RoadEdge edge) {
        outgoingEdges.remove(edge);
        edge.setSourceNode(null);
    }

    public void addIncomingEdge(RoadEdge edge) {
        incomingEdges.add(edge);
        edge.setDestinationNode(this);
    }

    public void removeIncomingEdge(RoadEdge edge) {
        incomingEdges.remove(edge);
        edge.setDestinationNode(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RoadNode)) return false;
        RoadNode roadNode = (RoadNode) o;
        return nodeId != null && nodeId.equals(roadNode.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(nodeId);
    }
}