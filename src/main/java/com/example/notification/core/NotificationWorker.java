package com.example.notification.core;

import com.example.notification.config.NotificationProperties;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);

    private final NotificationRepository repository;
    private final NotificationDeliveryService deliveryService;
    private final NotificationProperties properties;
    private final ExecutorService deliveryExecutor;
    private final Semaphore permits;

    public NotificationWorker(
            NotificationRepository repository,
            NotificationDeliveryService deliveryService,
            NotificationProperties properties,
            ExecutorService deliveryExecutor
    ) {
        this.repository = repository;
        this.deliveryService = deliveryService;
        this.properties = properties;
        this.deliveryExecutor = deliveryExecutor;
        this.permits = new Semaphore(Math.max(1, properties.getWorker().getMaxConcurrency()));
    }

    @Scheduled(fixedDelayString = "${app.worker.scan-delay:5s}")
    public void scheduledDispatch() {
        if (properties.getWorker().isEnabled()) {
            dispatchDueTasks();
        }
    }

    public int dispatchDueTasks() {
        int claimedPermits = reservePermits(properties.getWorker().getBatchSize());
        if (claimedPermits == 0) {
            return 0;
        }

        List<NotificationTask> tasks = repository.claimDueTasks(
                claimedPermits,
                Instant.now(),
                properties.getWorker().getLease());

        int unusedPermits = claimedPermits - tasks.size();
        if (unusedPermits > 0) {
            permits.release(unusedPermits);
        }

        for (NotificationTask task : tasks) {
            try {
                deliveryExecutor.submit(() -> {
                    try {
                        deliveryService.deliver(task);
                    } catch (Exception e) {
                        log.error("delivery task failed unexpectedly: {}", task.id(), e);
                    } finally {
                        permits.release();
                    }
                });
            } catch (RuntimeException e) {
                permits.release();
                log.error("failed to submit delivery task: {}", task.id(), e);
            }
        }
        return tasks.size();
    }

    private int reservePermits(int max) {
        int reserved = 0;
        int limit = Math.max(1, max);
        while (reserved < limit && permits.tryAcquire()) {
            reserved++;
        }
        return reserved;
    }
}
