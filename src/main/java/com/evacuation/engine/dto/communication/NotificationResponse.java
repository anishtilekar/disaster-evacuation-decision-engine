package com.evacuation.engine.dto.communication;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.evacuation.engine.model.enums.NotificationStatus;
import com.evacuation.engine.model.enums.NotificationType;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationResponse {

    private Long notificationId;

    private Long disasterId;

    private String disasterName;

    private String message;

    private NotificationType notificationType;

    private NotificationStatus notificationStatus;

    private String recipient;

    private String recipientContact;

    private LocalDateTime sentAt;

    private LocalDateTime deliveredAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}