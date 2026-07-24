package com.evacuation.engine.mapper.communication;

import com.evacuation.engine.dto.communication.NotificationRequest;
import com.evacuation.engine.dto.communication.NotificationResponse;
import com.evacuation.engine.model.entity.Disaster;
import com.evacuation.engine.model.entity.Notification;
import com.evacuation.engine.model.enums.DisasterType;
import com.evacuation.engine.model.enums.NotificationStatus;
import com.evacuation.engine.model.enums.NotificationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMapperTest {

    private final NotificationMapper mapper = new NotificationMapperImpl();

    @Test
    void toEntity_ignoresDisasterAndServerManagedFields() {
        NotificationRequest request = NotificationRequest.builder()
                .disasterId(1L)
                .message("Evacuate low-lying areas near Mula riverbank immediately")
                .notificationType(NotificationType.EVACUATION_INSTRUCTION)
                .recipient("Kothrud Ward Residents")
                .recipientContact("+919800011122")
                .build();

        Notification entity = mapper.toEntity(request);

        assertThat(entity.getMessage()).isEqualTo("Evacuate low-lying areas near Mula riverbank immediately");
        assertThat(entity.getNotificationType()).isEqualTo(NotificationType.EVACUATION_INSTRUCTION);
        assertThat(entity.getRecipient()).isEqualTo("Kothrud Ward Residents");
        assertThat(entity.getRecipientContact()).isEqualTo("+919800011122");

        assertThat(entity.getNotificationId()).isNull();
        assertThat(entity.getDisaster()).isNull();
        assertThat(entity.getNotificationStatus()).isNull();
        assertThat(entity.getSentAt()).isNull();
        assertThat(entity.getDeliveredAt()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
        assertThat(entity.getVersion()).isNull();
    }

    @Test
    void toResponse_flattensDisasterRelation() {
        Disaster disaster = Disaster.builder()
                .disasterId(1L)
                .disasterName("Mula River Flooding")
                .disasterType(DisasterType.FLOOD)
                .build();

        Notification entity = Notification.builder()
                .notificationId(15L)
                .disaster(disaster)
                .message("Evacuate low-lying areas near Mula riverbank immediately")
                .notificationType(NotificationType.EVACUATION_INSTRUCTION)
                .notificationStatus(NotificationStatus.SENT)
                .recipient("Kothrud Ward Residents")
                .recipientContact("+919800011122")
                .build();

        NotificationResponse response = mapper.toResponse(entity);

        assertThat(response.getNotificationId()).isEqualTo(15L);
        assertThat(response.getDisasterId()).isEqualTo(1L);
        assertThat(response.getDisasterName()).isEqualTo("Mula River Flooding");
        assertThat(response.getMessage()).isEqualTo("Evacuate low-lying areas near Mula riverbank immediately");
        assertThat(response.getNotificationStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void toResponse_disasterFieldsAreNullWhenRelationIsNull() {
        Notification entity = Notification.builder()
                .notificationId(16L)
                .disaster(null)
                .message("Test message")
                .notificationType(NotificationType.ALERT)
                .notificationStatus(NotificationStatus.PENDING)
                .recipient("Test Recipient")
                .build();

        NotificationResponse response = mapper.toResponse(entity);

        assertThat(response.getDisasterId()).isNull();
        assertThat(response.getDisasterName()).isNull();
    }
}
