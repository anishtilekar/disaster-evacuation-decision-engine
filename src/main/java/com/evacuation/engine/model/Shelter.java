package com.evacuation.engine.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "shelters")
@Data
@NoArgsConstructor
public class Shelter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long shelterId;

    private String shelterName;

    private String location;

    private double latitude;

    private double longitude;

    private int capacity;

    private int currentOccupancy;

    private boolean medicalFacility;

    private boolean foodSupply;

    private boolean waterSupply;

    private String contactNumber;

}