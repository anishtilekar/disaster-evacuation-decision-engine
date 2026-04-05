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
    private User user;

    @ManyToOne
    private Zone zone;

    @ManyToOne
    private Shelter assignedShelter;

    private String requestStatus;

    private int numberOfPeople;

    private boolean medicalAssistanceNeeded;

    private boolean transportationNeeded;

    private LocalDateTime requestTime;

    private LocalDateTime evacuationTime;

}