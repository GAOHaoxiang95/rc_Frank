package com.example.notification.api;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID notificationId,
        String sourceSystem,
        String targetUrl,
        String method,
        String status,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant lockedUntil,
        Instant createdAt,
        Instant updatedAt
) {
}
