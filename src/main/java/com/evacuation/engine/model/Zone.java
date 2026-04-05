package com.evacuation.engine.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "zones")
@Data
@NoArgsConstructor
public class Zone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long zoneId;

    private String zoneName;

    private String riskLevel;

    private double latitude;

    private double longitude;

    private int population;

    private boolean evacuationRequired;

    @ManyToOne
    @JoinColumn(name = "disaster_id")
    private Disaster disaster;

}