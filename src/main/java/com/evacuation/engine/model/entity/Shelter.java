package com.evacuation.engine.model.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "shelters",
        indexes = {
                @Index(name = "idx_shelter_status", columnList = "shelter_status"),
                @Index(name = "idx_shelter_lat_lng", columnList = "latitude, longitude"),
                @Index(name = "idx_shelter_capacity", columnList = "capacity")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Shelter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shelter_id")
    private Long shelterId;

    @NotBlank(message = "Shelter name is required")
    @Column(name = "shelter_name", nullable = false, length = 150)
    private String shelterName;

    @NotBlank(message = "Location description is required")
    @Column(name = "location", nullable = false, length = 250)
    private String location;

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

    @NotNull(message = "Capacity is required")
    @Positive(message = "Capacity must be greater than zero")
    @Column(name = "capacity", nullable = false)
    private Integer capacity;

    @NotNull(message = "Current occupancy is required")
    @PositiveOrZero(message = "Current occupancy cannot be negative")
    @Column(name = "current_occupancy", nullable = false)
    private Integer currentOccupancy;

    @NotNull(message = "Medical facility flag must be set")
    @Column(name = "medical_facility", nullable = false)
    private Boolean medicalFacility;

    @NotNull(message = "Food supply flag must be set")
    @Column(name = "food_supply", nullable = false)
    private Boolean foodSupply;

    @NotNull(message = "Water supply flag must be set")
    @Column(name = "water_supply", nullable = false)
    private Boolean waterSupply;

    @NotBlank(message = "Contact number is required")
    @Pattern(regexp = "^[+]?[0-9]{10,15}$", message = "Contact number must be a valid phone number")
    @Column(name = "contact_number", nullable = false, length = 20)
    private String contactNumber;

    @NotNull(message = "Shelter status is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "shelter_status", nullable = false, length = 25)
    private ShelterStatus shelterStatus;

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

        if (currentOccupancy == null) {
            currentOccupancy = 0;
        }
        if (medicalFacility == null) {
            medicalFacility = Boolean.FALSE;
        }
        if (foodSupply == null) {
            foodSupply = Boolean.FALSE;
        }
        if (waterSupply == null) {
            waterSupply = Boolean.FALSE;
        }
        if (shelterStatus == null) {
            shelterStatus = ShelterStatus.AVAILABLE;
        }

        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Transient
    public Integer getAvailableCapacity() {
        if (capacity == null || currentOccupancy == null) {
            return null;
        }
        return capacity - currentOccupancy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Shelter)) return false;
        Shelter shelter = (Shelter) o;
        return shelterId != null && shelterId.equals(shelter.shelterId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(shelterId);
    }
}