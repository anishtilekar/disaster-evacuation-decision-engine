package com.evacuation.engine.model.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import com.evacuation.engine.model.enums.DisasterType;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A predicted, time-varying hazard — the persisted operator input that {@code HazardTimelineCompiler}
 * turns into future-dated {@code HazardTimeline} intervals.
 *
 * <p>This is the design's "polygon + growth rate" hazard event, modelled as an <strong>expanding
 * circle</strong> rather than an expanding polygon. The project deliberately carries no geometry
 * dependency (no JTS), and buffering an arbitrary polygon outward by a distance is exactly the
 * operation that would require one; a circle is the special case where that buffer is a single scalar,
 * so the whole geometry reduces to an origin, a radius, and a rate. The existing {@code Disaster}
 * entity already takes the same simplification with its {@code impactRadiusKm}, so this follows a
 * precedent rather than setting one. The cost is fidelity of shape, not of timing — and it is timing
 * that the search actually consumes. A genuinely polygonal front can be approximated today by
 * submitting several overlapping events, and swapping in real geometry later changes the compiler's
 * distance function, not this table.
 *
 * <p><strong>How it compiles.</strong> The front's radius at any moment is
 * {@code initialRadiusMeters + growthRateMetersPerMinute * minutes elapsed since eventStartTime}, so a
 * cell at distance {@code d} from the origin has an <em>onset bucket</em>: the first bucket at which
 * the expanding radius first reaches {@code d}. Before that bucket the cell is untouched; from it
 * onward, the cell is inside the front.
 *
 * <p><strong>Why it needs three states, not two.</strong> {@code h(x, b)} is
 * {@code SAFE / RISKY(rho) / LETHAL}, and an event that only ever flipped cells from SAFE straight to
 * LETHAL would waste the middle one — leaving a binary cliff where a route is either perfectly fine or
 * fatal one bucket later. {@code leadingRiskBufferMeters} gives the model an actual transition zone:
 * cells inside the radius are LETHAL, cells within that many extra metres <em>ahead</em> of the front
 * are {@code RISKY} carrying {@code riskFactor} as their rho, and everything beyond is SAFE. Routing
 * can then price proximity to an advancing front instead of only refusing to cross it.
 *
 * <p>{@code eventStartTime} may be in the future: a forecast front that has not begun advancing is a
 * legitimate, plannable input, which is the entire point of compiling hazards into a timeline rather
 * than a flag. Deactivating clears an event's effect on the next compile while preserving the audit
 * history, matching {@link BlockedRoad}'s pattern instead of deleting rows.
 */
@Entity
@Table(
        name = "hazard_events",
        indexes = {
                @Index(name = "idx_hazard_event_type", columnList = "hazard_type"),
                @Index(name = "idx_hazard_event_active", columnList = "is_active"),
                @Index(name = "idx_hazard_event_start_time", columnList = "event_start_time")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class HazardEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "hazard_event_id")
    private Long hazardEventId;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    @Column(name = "description", length = 500)
    private String description;

    @NotNull(message = "Hazard type is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "hazard_type", nullable = false, length = 30)
    private DisasterType hazardType;

    @NotNull(message = "Origin latitude is required")
    @DecimalMin(value = "-90.0", message = "Latitude must be >= -90")
    @DecimalMax(value = "90.0", message = "Latitude must be <= 90")
    @Column(name = "origin_latitude", nullable = false)
    private Double originLatitude;

    @NotNull(message = "Origin longitude is required")
    @DecimalMin(value = "-180.0", message = "Longitude must be >= -180")
    @DecimalMax(value = "180.0", message = "Longitude must be <= 180")
    @Column(name = "origin_longitude", nullable = false)
    private Double originLongitude;

    /** How far the hazard already extends at {@code eventStartTime} — the design's r0. */
    @NotNull(message = "Initial radius is required")
    @PositiveOrZero(message = "Initial radius cannot be negative")
    @Column(name = "initial_radius_meters", nullable = false)
    private Double initialRadiusMeters;

    /** The front's advance rate; a Scenario 2 flood front runs at roughly 200 m/min. */
    @NotNull(message = "Growth rate is required")
    @Positive(message = "Growth rate must be greater than zero")
    @Column(name = "growth_rate_meters_per_minute", nullable = false)
    private Double growthRateMetersPerMinute;

    /** Width of the RISKY band ahead of the LETHAL front; zero collapses it back to a hard edge. */
    @NotNull(message = "Leading risk buffer is required")
    @PositiveOrZero(message = "Leading risk buffer cannot be negative")
    @Column(name = "leading_risk_buffer_meters", nullable = false)
    private Double leadingRiskBufferMeters;

    /** The rho carried by cells inside the leading risk buffer. */
    @NotNull(message = "Risk factor is required")
    @DecimalMin(value = "0.0", message = "Risk factor must be >= 0")
    @DecimalMax(value = "1.0", message = "Risk factor must be <= 1")
    @Column(name = "risk_factor", nullable = false)
    private Double riskFactor;

    /** When the front begins advancing; may be now, or future-dated for a forecast hazard. */
    @NotNull(message = "Event start time is required")
    @Column(name = "event_start_time", nullable = false)
    private LocalDateTime eventStartTime;

    @NotNull(message = "Active status must be set")
    @Column(name = "is_active", nullable = false)
    private Boolean active;

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
        if (leadingRiskBufferMeters == null) {
            leadingRiskBufferMeters = 0.0;
        }
        if (riskFactor == null) {
            riskFactor = 0.5;
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
        if (!(o instanceof HazardEvent)) return false;
        HazardEvent that = (HazardEvent) o;
        return hazardEventId != null && hazardEventId.equals(that.hazardEventId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(hazardEventId);
    }
}