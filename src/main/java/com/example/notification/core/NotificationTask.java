package com.example.notification.core;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record NotificationTask(
        UUID id,
        String sourceSystem,
        String targetUrl,
        String method,
        Map<String, String> headers,
        String body,
        String requestFingerprint,
        String idempotencyKey,
        NotificationStatus status,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant lockedUntil,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
