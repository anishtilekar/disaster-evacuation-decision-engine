package com.evacuation.engine.dto.communication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.evacuation.engine.model.enums.NotificationType;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationRequest {

    @NotNull(message = "Disaster ID is required")
    private Long disasterId;

    @NotBlank(message = "Notification message is required")
    @Size(max = 1000, message = "Message cannot exceed 1000 characters")
    private String message;

    @NotNull(message = "Notification type is required")
    private NotificationType notificationType;

    @NotBlank(message = "Recipient identifier is required")
    @Size(max = 150, message = "Recipient cannot exceed 150 characters")
    private String recipient;

    @Size(max = 50, message = "Recipient contact cannot exceed 50 characters")
    private String recipientContact;
}