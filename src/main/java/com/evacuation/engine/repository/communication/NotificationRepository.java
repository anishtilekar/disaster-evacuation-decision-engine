package com.evacuation.engine.repository.communication;

import com.evacuation.engine.model.entity.Disaster;
import com.evacuation.engine.model.entity.Notification;
import com.evacuation.engine.model.enums.NotificationStatus;
import com.evacuation.engine.model.enums.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByNotificationStatus(NotificationStatus notificationStatus);

    List<Notification> findByNotificationType(NotificationType notificationType);

    List<Notification> findByDisaster(Disaster disaster);

    List<Notification> findByRecipient(String recipient);
}