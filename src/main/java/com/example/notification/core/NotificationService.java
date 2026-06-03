package com.example.notification.core;

import com.example.notification.api.SubmitNotificationRequest;
import com.example.notification.config.NotificationProperties;
import com.example.notification.security.FingerprintService;
import com.example.notification.security.TargetUrlPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private static final Set<String> RESERVED_HEADERS = Set.of("x-idempotency-key");

    private final NotificationRepository repository;
    private final NotificationProperties properties;
    private final FingerprintService fingerprintService;
    private final TargetUrlPolicy targetUrlPolicy;

    public NotificationService(
            NotificationRepository repository,
            NotificationProperties properties,
            FingerprintService fingerprintService,
            TargetUrlPolicy targetUrlPolicy
    ) {
        this.repository = repository;
        this.properties = properties;
        this.fingerprintService = fingerprintService;
        this.targetUrlPolicy = targetUrlPolicy;
    }

    public SubmitResult submit(String sourceSystem, SubmitNotificationRequest request) {
        rejectReservedHeaders(request.headers());
        int maxAttempts = request.maxAttempts() == null
                ? properties.getDelivery().getDefaultMaxAttempts()
                : request.maxAttempts();
        targetUrlPolicy.validate(sourceSystem, request.targetUrl());
        String fingerprint = fingerprintService.fingerprint(request, maxAttempts);

        return repository.findBySourceAndIdempotencyKey(sourceSystem, request.idempotencyKey())
                .map(existing -> existingResult(existing, fingerprint))
                .orElseGet(() -> insert(sourceSystem, request, maxAttempts, fingerprint));
    }

    public NotificationTask get(String sourceSystem, UUID id) {
        return repository.findByIdForSource(id, sourceSystem)
                .orElseThrow(() -> new NotFoundException("notification not found"));
    }

    public List<DeliveryAttempt> attempts(String sourceSystem, UUID id) {
        get(sourceSystem, id);
        return repository.findAttemptsForSource(id, sourceSystem);
    }

    public NotificationTask retry(String sourceSystem, UUID id) {
        NotificationTask task = get(sourceSystem, id);
        if (task.status() != NotificationStatus.FAILED) {
            throw new BadRequestException("only FAILED notifications can be retried");
        }
        repository.retryFailed(id, sourceSystem, Instant.now());
        return get(sourceSystem, id);
    }

    private SubmitResult insert(
            String sourceSystem,
            SubmitNotificationRequest request,
            int maxAttempts,
            String fingerprint
    ) {
        Instant now = Instant.now();
        NotificationTask task = new NotificationTask(
                UUID.randomUUID(),
                sourceSystem,
                request.targetUrl(),
                request.method().toUpperCase(Locale.ROOT),
                fingerprintService.normalizedHeaders(request.headers()),
                request.body(),
                fingerprint,
                request.idempotencyKey(),
                NotificationStatus.PENDING,
                0,
                maxAttempts,
                now,
                null,
                0,
                now,
                now);
        try {
            repository.insertTask(task);
            return new SubmitResult(task, false);
        } catch (DuplicateKeyException ignored) {
            NotificationTask existing = repository
                    .findBySourceAndIdempotencyKey(sourceSystem, request.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("idempotency key conflict but task not found"));
            return existingResult(existing, fingerprint);
        }
    }

    private SubmitResult existingResult(NotificationTask existing, String fingerprint) {
        if (!existing.requestFingerprint().equals(fingerprint)) {
            throw new ConflictException("idempotencyKey already exists with different payload");
        }
        return new SubmitResult(existing, true);
    }

    private void rejectReservedHeaders(Map<String, String> headers) {
        for (String header : headers.keySet()) {
            if (RESERVED_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("header " + header + " is reserved by platform");
            }
        }
    }
}
