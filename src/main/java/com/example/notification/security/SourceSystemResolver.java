package com.example.notification.security;

import com.example.notification.config.NotificationProperties;
import com.example.notification.core.UnauthorizedException;
import org.springframework.stereotype.Component;

@Component
public class SourceSystemResolver {

    private final NotificationProperties properties;

    public SourceSystemResolver(NotificationProperties properties) {
        this.properties = properties;
    }

    public String resolve(String sourceSystemHeader) {
        if (sourceSystemHeader == null || sourceSystemHeader.isBlank()) {
            throw new UnauthorizedException("X-Source-System header is required");
        }
        String sourceSystem = sourceSystemHeader.trim();
        if (!properties.getSecurity().getSources().containsKey(sourceSystem)) {
            throw new UnauthorizedException("unknown source system");
        }
        return sourceSystem;
    }
}
