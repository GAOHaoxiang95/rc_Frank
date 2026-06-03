package com.example.notification.security;

import com.example.notification.api.SubmitNotificationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class FingerprintService {

    private final ObjectMapper objectMapper;

    public FingerprintService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String fingerprint(SubmitNotificationRequest request, int maxAttempts) {
        try {
            Map<String, Object> canonical = new TreeMap<>();
            canonical.put("targetUrl", request.targetUrl());
            canonical.put("method", request.method().toUpperCase(Locale.ROOT));
            canonical.put("headers", normalizedHeaders(request.headers()));
            canonical.put("body", request.body() == null ? "" : request.body());
            canonical.put("maxAttempts", maxAttempts);

            byte[] json = objectMapper.writeValueAsBytes(canonical);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to calculate request fingerprint", e);
        }
    }

    public Map<String, String> normalizedHeaders(Map<String, String> headers) {
        Map<String, String> normalized = new TreeMap<>();
        if (headers == null) {
            return normalized;
        }
        headers.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        return normalized;
    }
}
