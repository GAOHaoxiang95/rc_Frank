package com.example.notification.core;

import java.time.Instant;
import java.util.UUID;

public record AttemptResult(
        UUID notificationId,
        long expectedVersion,
        int attemptNo,
        boolean success,
        boolean retryable,
        Integer responseStatus,
        String errorType,
        String errorDetail,
        long latencyMs,
        Instant nextAttemptAt
) {
}
