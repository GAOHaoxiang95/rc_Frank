package com.example.notification.api;

import java.util.UUID;

public record SubmitNotificationResponse(
        UUID notificationId,
        String status,
        boolean duplicate
) {
}
