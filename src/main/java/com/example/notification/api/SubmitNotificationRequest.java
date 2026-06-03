package com.example.notification.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;

public record SubmitNotificationRequest(
        @NotBlank String idempotencyKey,
        @NotBlank String targetUrl,
        @NotBlank @Pattern(regexp = "(?i)GET|POST|PUT|PATCH|DELETE") String method,
        Map<@NotBlank String, @NotBlank String> headers,
        String body,
        @Min(1) @Max(20) Integer maxAttempts
) {
    public SubmitNotificationRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? "" : body;
    }
}
