package com.evacuation.engine.mapper.communication;

import com.evacuation.engine.dto.communication.NotificationRequest;
import com.evacuation.engine.dto.communication.NotificationResponse;
import com.evacuation.engine.model.entity.Notification;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface NotificationMapper {

    @Mapping(target = "notificationId", ignore = true)
    @Mapping(target = "disaster", ignore = true)
    @Mapping(target = "notificationStatus", ignore = true)
    @Mapping(target = "sentAt", ignore = true)
    @Mapping(target = "deliveredAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    Notification toEntity(NotificationRequest request);

    @Mapping(target = "disasterId", source = "disaster.disasterId")
    @Mapping(target = "disasterName", source = "disaster.disasterName")
    NotificationResponse toResponse(Notification entity);
}
