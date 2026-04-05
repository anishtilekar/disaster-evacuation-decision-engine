package com.evacuation.engine.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long userId;

    private String fullName;

    @Column(unique = true)
    private String email;

    private String phoneNumber;

    private String password;

    private String role;

    private String address;

    private double latitude;

    private double longitude;

    private boolean active;

}