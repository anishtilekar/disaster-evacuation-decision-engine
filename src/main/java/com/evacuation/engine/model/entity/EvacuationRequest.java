package com.evacuation.engine.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import com.evacuation.engine.model.enums.EvacuationStatus;
import com.evacuation.engine.model.enums.EvacuationPriority;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "evacuation_requests",
        indexes = {
                @Index(name = "idx_evac_request_disaster", columnList = "disaster_id"),
                @Index(name = "idx_evac_request_zone", columnList = "disaster_zone_id"),
                @Index(name = "idx_evac_request_source_node", columnList = "source_node_id"),
                @Index(name = "idx_evac_request_status", columnList = "status"),
                @Index(name = "idx_evac_request_priority", columnList = "priority"),
                @Index(name = "idx_evac_request_time", columnList = "requested_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"disaster", "disasterZone", "sourceRoadNode"})
public class EvacuationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "evacuation_request_id")
    private Long evacuationRequestId;


    @NotNull(message = "Disaster reference is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "disaster_id", nullable = false)
    @JsonIgnoreProperties({"disasterZones", "hibernateLazyInitializer", "handler"})
    private Disaster disaster;


    @NotNull(message = "Disaster zone reference is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "disaster_zone_id", nullable = false)
    @JsonIgnoreProperties({"disaster", "hibernateLazyInitializer", "handler"})
    private DisasterZone disasterZone;


    /*
     * Starting point of evacuation request.
     * Used by graph engine for route calculation.
     */
    @NotNull(message = "Source road node reference is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_node_id", nullable = false)
    @JsonIgnoreProperties({
            "outgoingEdges",
            "incomingEdges",
            "hibernateLazyInitializer",
            "handler"
    })
    private RoadNode sourceRoadNode;


    @NotBlank(message = "Requester name is required")
    @Column(name = "requester_name", nullable = false, length = 150)
    private String requesterName;


    @NotBlank(message = "Contact number is required")
    @Pattern(
            regexp = "^[+]?[0-9]{10,15}$",
            message = "Contact number must be a valid phone number"
    )
    @Column(name = "contact_number", nullable = false, length = 20)
    private String contactNumber;


    @NotNull(message = "Number of people is required")
    @Positive(message = "Number of people must be greater than zero")
    @Column(name = "number_of_people", nullable = false)
    private Integer numberOfPeople;


    @NotNull(message = "Priority is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private EvacuationPriority priority;


    @NotNull(message = "Status is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EvacuationStatus status;


    @NotNull(message = "Requested at timestamp is required")
    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;


    @Size(
            max = 500,
            message = "Emergency notes cannot exceed 500 characters"
    )
    @Column(name = "emergency_notes", length = 500)
    private String emergencyNotes;


    @NotNull(message = "Medical assistance required flag must be set")
    @Column(name = "medical_assistance_required", nullable = false)
    private Boolean medicalAssistanceRequired;


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

        if (requestedAt == null) {
            requestedAt = now;
        }

        if (priority == null) {
            priority = EvacuationPriority.MEDIUM;
        }

        if (status == null) {
            status = EvacuationStatus.PENDING;
        }

        if (medicalAssistanceRequired == null) {
            medicalAssistanceRequired = Boolean.FALSE;
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

        if (this == o) {
            return true;
        }

        if (!(o instanceof EvacuationRequest)) {
            return false;
        }

        EvacuationRequest that = (EvacuationRequest) o;

        return evacuationRequestId != null &&
                evacuationRequestId.equals(that.evacuationRequestId);
    }


    @Override
    public int hashCode() {
        return Objects.hashCode(evacuationRequestId);
    }
}