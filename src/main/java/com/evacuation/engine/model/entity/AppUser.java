package com.evacuation.engine.model.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import com.evacuation.engine.model.enums.UserRole;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A login account for this app's Spring Security layer. Table is {@code app_users} rather than
 * {@code users} because {@code user} is a reserved word in MySQL.
 *
 * <p>{@code passwordHash} is always a BCrypt hash, never a plain password — see
 * {@code security.AppUserDetailsService} and {@code security.UserSeedRunner}, the only two classes
 * that read or write it.
 */
@Entity
@Table(
        name = "app_users",
        indexes = {
                @Index(name = "idx_app_user_username", columnList = "username", unique = true)
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"passwordHash"})
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;


    @NotBlank(message = "Username is required")
    @Column(name = "username", nullable = false, unique = true, length = 100)
    private String username;


    @NotBlank(message = "Password hash is required")
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;


    @NotNull(message = "Role is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;


    @NotNull(message = "Enabled flag must be set")
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;


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
        if (enabled == null) {
            enabled = Boolean.TRUE;
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

        if (!(o instanceof AppUser)) {
            return false;
        }

        AppUser that = (AppUser) o;

        return userId != null && userId.equals(that.userId);
    }


    @Override
    public int hashCode() {
        return Objects.hashCode(userId);
    }
}
