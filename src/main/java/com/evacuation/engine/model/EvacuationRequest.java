package com.evacuation.engine.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "evacuation_requests")
@Data
@NoArgsConstructor
public class EvacuationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long requestId;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne
    @JoinColumn(name = "zone_id")
    private Zone zone;

    @ManyToOne
    @JoinColumn(name = "shelter_id")
    private Shelter assignedShelter;

    private String requestStatus;

    private int numberOfPeople;

    private boolean medicalAssistanceNeeded;

    private boolean transportationNeeded;

    private LocalDateTime requestTime;

    private LocalDateTime evacuationTime;


    @PrePersist
    protected void onCreate() {

        if (requestTime == null) {
            requestTime = LocalDateTime.now();
        }

        if (requestStatus == null) {
            requestStatus = "PENDING";
        }

        if (numberOfPeople == 0) {
            numberOfPeople = 1;
        }
    }
}