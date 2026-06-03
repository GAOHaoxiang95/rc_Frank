package com.example.notification.core;

import java.time.Instant;
import java.util.UUID;

public record DeliveryAttempt(
        UUID id,
        UUID notificationId,
        int attemptNo,
        AttemptStatus status,
        Integer responseStatus,
        String errorType,
        String errorDetail,
        long latencyMs,
        Instant createdAt
) {
}
