package com.example.notification.api;

import com.example.notification.core.DeliveryAttempt;
import com.example.notification.core.NotificationService;
import com.example.notification.core.NotificationTask;
import com.example.notification.core.SubmitResult;
import com.example.notification.security.SourceSystemResolver;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final SourceSystemResolver sourceSystemResolver;

    public NotificationController(
            NotificationService notificationService,
            SourceSystemResolver sourceSystemResolver
    ) {
        this.notificationService = notificationService;
        this.sourceSystemResolver = sourceSystemResolver;
    }

    @PostMapping
    public ResponseEntity<SubmitNotificationResponse> submit(
            @RequestHeader(value = "X-Source-System", required = false) String sourceSystemHeader,
            @Valid @RequestBody SubmitNotificationRequest request
    ) {
        String sourceSystem = sourceSystemResolver.resolve(sourceSystemHeader);
        SubmitResult result = notificationService.submit(sourceSystem, request);
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status)
                .body(new SubmitNotificationResponse(
                        result.task().id(),
                        result.task().status().name(),
                        result.duplicate()));
    }

    @GetMapping("/{id}")
    public NotificationResponse get(
            @RequestHeader(value = "X-Source-System", required = false) String sourceSystemHeader,
            @PathVariable UUID id
    ) {
        String sourceSystem = sourceSystemResolver.resolve(sourceSystemHeader);
        return toResponse(notificationService.get(sourceSystem, id));
    }

    @GetMapping("/{id}/attempts")
    public List<AttemptResponse> attempts(
            @RequestHeader(value = "X-Source-System", required = false) String sourceSystemHeader,
            @PathVariable UUID id
    ) {
        String sourceSystem = sourceSystemResolver.resolve(sourceSystemHeader);
        return notificationService.attempts(sourceSystem, id).stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<NotificationResponse> retry(
            @RequestHeader(value = "X-Source-System", required = false) String sourceSystemHeader,
            @PathVariable UUID id
    ) {
        String sourceSystem = sourceSystemResolver.resolve(sourceSystemHeader);
        return ResponseEntity.accepted()
                .body(toResponse(notificationService.retry(sourceSystem, id)));
    }

    private NotificationResponse toResponse(NotificationTask task) {
        return new NotificationResponse(
                task.id(),
                task.sourceSystem(),
                task.targetUrl(),
                task.method(),
                task.status().name(),
                task.attemptCount(),
                task.maxAttempts(),
                task.nextAttemptAt(),
                task.lockedUntil(),
                task.createdAt(),
                task.updatedAt());
    }

    private AttemptResponse toResponse(DeliveryAttempt attempt) {
        return new AttemptResponse(
                attempt.id(),
                attempt.attemptNo(),
                attempt.status().name(),
                attempt.responseStatus(),
                attempt.errorType(),
                attempt.errorDetail(),
                attempt.latencyMs(),
                attempt.createdAt());
    }
}
