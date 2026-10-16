package com.justcommit.backend.notification.presentation.dto;

import com.justcommit.backend.notification.domain.Notification;

import java.time.LocalDateTime;

public record NotificationResponse(
        Long notificationId,
        String type,
        String message,
        Long sellerOrderId,
        boolean isRead,
        LocalDateTime createdAt,
        LocalDateTime readAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType().name(),
                notification.getMessage(),
                notification.getSellerOrderId(),
                notification.isRead(),
                notification.getCreatedAt(),
                notification.getReadAt());
    }
}
