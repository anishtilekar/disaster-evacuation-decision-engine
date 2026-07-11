package com.evacuation.engine.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import com.evacuation.engine.model.enums.NotificationStatus;
import com.evacuation.engine.model.enums.NotificationType;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "notifications",
        indexes = {
                @Index(name = "idx_notification_disaster", columnList = "disaster_id"),
                @Index(name = "idx_notification_type", columnList = "notification_type"),
                @Index(name = "idx_notification_status", columnList = "notification_status"),
                @Index(name = "idx_notification_sent_at", columnList = "sent_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "disaster")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long notificationId;

    @NotNull(message = "Disaster reference is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "disaster_id", nullable = false)
    @JsonIgnoreProperties({"disasterZones", "hibernateLazyInitializer", "handler"})
    private Disaster disaster;

    @NotBlank(message = "Notification message is required")
    @Column(name = "message", nullable = false, length = 1000)
    private String message;

    @NotNull(message = "Notification type is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 30)
    private NotificationType notificationType;

    @NotNull(message = "Notification status is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "notification_status", nullable = false, length = 20)
    private NotificationStatus notificationStatus;

    @NotBlank(message = "Recipient identifier is required")
    @Column(name = "recipient", nullable = false, length = 150)
    private String recipient;

    @Column(name = "recipient_contact", length = 50)
    private String recipientContact;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

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

        if (notificationStatus == null) {
            notificationStatus = NotificationStatus.PENDING;
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
        if (!(o instanceof Notification)) return false;
        Notification that = (Notification) o;
        return notificationId != null && notificationId.equals(that.notificationId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(notificationId);
    }
}