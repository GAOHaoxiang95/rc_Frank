package com.example.notification.api;

import java.time.Instant;
import java.util.UUID;

public record AttemptResponse(
        UUID id,
        int attemptNo,
        String status,
        Integer responseStatus,
        String errorType,
        String errorDetail,
        long latencyMs,
        Instant createdAt
) {
}
