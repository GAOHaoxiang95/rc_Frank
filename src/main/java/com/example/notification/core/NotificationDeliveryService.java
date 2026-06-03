package com.example.notification.core;

import com.example.notification.config.NotificationProperties;
import com.example.notification.security.TargetUrlPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

    private final NotificationRepository repository;
    private final DeliveryHttpClient deliveryHttpClient;
    private final TargetUrlPolicy targetUrlPolicy;
    private final NotificationProperties properties;

    public NotificationDeliveryService(
            NotificationRepository repository,
            DeliveryHttpClient deliveryHttpClient,
            TargetUrlPolicy targetUrlPolicy,
            NotificationProperties properties
    ) {
        this.repository = repository;
        this.deliveryHttpClient = deliveryHttpClient;
        this.targetUrlPolicy = targetUrlPolicy;
        this.properties = properties;
    }

    public void deliver(NotificationTask task) {
        AttemptResult result = execute(task);
        boolean stored = repository.completeAttempt(task, result);
        if (!stored) {
            log.info("discarded stale delivery result for notification {}", task.id());
        }
    }

    private AttemptResult execute(NotificationTask task) {
        int attemptNo = task.attemptCount() + 1;
        Instant startedAt = Instant.now();
        try {
            URI uri = targetUrlPolicy.validate(task.sourceSystem(), task.targetUrl());
            DeliveryHttpResult httpResult = deliveryHttpClient.send(task, uri);
            long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();
            int statusCode = httpResult.statusCode();
            boolean success = statusCode >= 200 && statusCode < 300;
            boolean retryable = isRetryableStatus(statusCode);
            return new AttemptResult(
                    task.id(),
                    task.version(),
                    attemptNo,
                    success,
                    retryable,
                    statusCode,
                    success ? null : "HTTP_STATUS",
                    success ? null : summarizeBody(httpResult.body()),
                    latencyMs,
                    retryable ? nextAttemptAt(attemptNo, httpResult.headers()) : null);
        } catch (BadRequestException e) {
            return failure(task, attemptNo, startedAt, false, null, "URL_POLICY", e.getMessage(), null);
        } catch (HttpTimeoutException e) {
            return failure(task, attemptNo, startedAt, true, null, "TIMEOUT", e.getMessage(), null);
        } catch (IOException e) {
            return failure(task, attemptNo, startedAt, true, null, "IO_ERROR", e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(task, attemptNo, startedAt, true, null, "INTERRUPTED", e.getMessage(), null);
        } catch (RuntimeException e) {
            return failure(task, attemptNo, startedAt, true, null, "INTERNAL_ERROR", e.getMessage(), null);
        }
    }

    private AttemptResult failure(
            NotificationTask task,
            int attemptNo,
            Instant startedAt,
            boolean retryable,
            Integer responseStatus,
            String errorType,
            String errorDetail,
            HttpHeaders headers
    ) {
        return new AttemptResult(
                task.id(),
                task.version(),
                attemptNo,
                false,
                retryable,
                responseStatus,
                errorType,
                errorDetail,
                Duration.between(startedAt, Instant.now()).toMillis(),
                retryable ? nextAttemptAt(attemptNo, headers) : null);
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private Instant nextAttemptAt(int attemptNo, HttpHeaders headers) {
        Optional<Instant> retryAfter = retryAfter(headers);
        if (retryAfter.isPresent()) {
            return retryAfter.get();
        }

        Duration initial = properties.getDelivery().getInitialBackoff();
        Duration max = properties.getDelivery().getMaxBackoff();
        long multiplier = 1L << Math.min(Math.max(attemptNo - 1, 0), 10);
        long delayMillis = Math.min(max.toMillis(), initial.toMillis() * multiplier);
        double jitterRatio = Math.max(0.0, properties.getDelivery().getJitterRatio());
        if (jitterRatio > 0) {
            double min = 1.0 - jitterRatio;
            double maxMultiplier = 1.0 + jitterRatio;
            delayMillis = (long) (delayMillis * ThreadLocalRandom.current().nextDouble(min, maxMultiplier));
        }
        return Instant.now().plusMillis(Math.max(1, delayMillis));
    }

    private Optional<Instant> retryAfter(HttpHeaders headers) {
        if (headers == null) {
            return Optional.empty();
        }
        return headers.firstValue("Retry-After").flatMap(value -> {
            try {
                long seconds = Long.parseLong(value.trim());
                return Optional.of(Instant.now().plusSeconds(Math.max(0, seconds)));
            } catch (NumberFormatException ignored) {
                try {
                    return Optional.of(ZonedDateTime
                            .parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant());
                } catch (Exception ignoredAgain) {
                    return Optional.empty();
                }
            }
        });
    }

    private String summarizeBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        return body.length() <= 300 ? body : body.substring(0, 300);
    }
}
