package com.example.notification.core;

import com.example.notification.security.PayloadProtector;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NotificationRepository {

    private static final TypeReference<Map<String, String>> HEADERS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PayloadProtector payloadProtector;

    public NotificationRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PayloadProtector payloadProtector
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.payloadProtector = payloadProtector;
    }

    public void insertTask(NotificationTask task) {
        jdbcTemplate.update("""
                        insert into notification_task (
                            id, source_system, target_url, method, headers_json, body,
                            request_fingerprint, idempotency_key, status,
                            attempt_count, max_attempts, next_attempt_at, locked_until,
                            version, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                task.id(),
                task.sourceSystem(),
                task.targetUrl(),
                task.method(),
                payloadProtector.encrypt(toJson(task.headers())),
                payloadProtector.encrypt(task.body()),
                task.requestFingerprint(),
                task.idempotencyKey(),
                task.status().name(),
                task.attemptCount(),
                task.maxAttempts(),
                timestamp(task.nextAttemptAt()),
                timestamp(task.lockedUntil()),
                task.version(),
                timestamp(task.createdAt()),
                timestamp(task.updatedAt()));
    }

    public Optional<NotificationTask> findBySourceAndIdempotencyKey(String sourceSystem, String idempotencyKey) {
        return queryOne("""
                        select * from notification_task
                        where source_system = ? and idempotency_key = ?
                        """,
                sourceSystem,
                idempotencyKey);
    }

    public Optional<NotificationTask> findByIdForSource(UUID id, String sourceSystem) {
        return queryOne("""
                        select * from notification_task
                        where id = ? and source_system = ?
                        """,
                id,
                sourceSystem);
    }

    public List<DeliveryAttempt> findAttemptsForSource(UUID notificationId, String sourceSystem) {
        return jdbcTemplate.query("""
                        select a.* from delivery_attempt a
                        join notification_task t on t.id = a.notification_id
                        where t.id = ? and t.source_system = ?
                        order by a.created_at asc
                        """,
                this::mapAttempt,
                notificationId,
                sourceSystem);
    }

    @Transactional
    public List<NotificationTask> claimDueTasks(int batchSize, Instant now, Duration lease) {
        List<NotificationTask> candidates = jdbcTemplate.query("""
                        select * from notification_task
                        where (
                            (status = 'PENDING' and next_attempt_at <= ?)
                            or (status = 'PROCESSING' and locked_until < ?)
                        )
                        and attempt_count < max_attempts
                        order by next_attempt_at asc, created_at asc
                        limit ?
                        for update skip locked
                        """,
                this::mapTask,
                timestamp(now),
                timestamp(now),
                batchSize);

        Instant lockedUntil = now.plus(lease);
        return candidates.stream()
                .map(task -> claim(task, now, lockedUntil))
                .flatMap(Optional::stream)
                .toList();
    }

    @Transactional
    public boolean completeAttempt(NotificationTask claimedTask, AttemptResult result) {
        NotificationStatus nextStatus = nextStatus(claimedTask, result);
        Instant nextAttemptAt = nextStatus == NotificationStatus.PENDING ? result.nextAttemptAt() : null;

        int updated = jdbcTemplate.update("""
                        update notification_task
                        set status = ?,
                            attempt_count = ?,
                            next_attempt_at = ?,
                            locked_until = null,
                            version = version + 1,
                            updated_at = ?
                        where id = ?
                          and status = 'PROCESSING'
                          and version = ?
                        """,
                nextStatus.name(),
                result.attemptNo(),
                timestamp(nextAttemptAt),
                timestamp(Instant.now()),
                claimedTask.id(),
                result.expectedVersion());

        if (updated == 0) {
            return false;
        }

        jdbcTemplate.update("""
                        insert into delivery_attempt (
                            id, notification_id, attempt_no, status, response_status,
                            error_type, error_detail, latency_ms, created_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(),
                result.notificationId(),
                result.attemptNo(),
                result.success() ? AttemptStatus.SUCCESS.name() : AttemptStatus.FAILED.name(),
                result.responseStatus(),
                result.errorType(),
                truncate(result.errorDetail(), 1024),
                result.latencyMs(),
                timestamp(Instant.now()));

        return true;
    }

    public int retryFailed(UUID id, String sourceSystem, Instant now) {
        return jdbcTemplate.update("""
                        update notification_task
                        set status = 'PENDING',
                            attempt_count = 0,
                            next_attempt_at = ?,
                            locked_until = null,
                            version = version + 1,
                            updated_at = ?
                        where id = ?
                          and source_system = ?
                          and status = 'FAILED'
                        """,
                timestamp(now),
                timestamp(now),
                id,
                sourceSystem);
    }

    private Optional<NotificationTask> claim(NotificationTask task, Instant now, Instant lockedUntil) {
        int updated = jdbcTemplate.update("""
                        update notification_task
                        set status = 'PROCESSING',
                            locked_until = ?,
                            version = version + 1,
                            updated_at = ?
                        where id = ?
                          and version = ?
                        """,
                timestamp(lockedUntil),
                timestamp(now),
                task.id(),
                task.version());

        if (updated == 0) {
            return Optional.empty();
        }
        return findById(task.id());
    }

    private Optional<NotificationTask> findById(UUID id) {
        return queryOne("select * from notification_task where id = ?", id);
    }

    private Optional<NotificationTask> queryOne(String sql, Object... args) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, this::mapTask, args));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private NotificationStatus nextStatus(NotificationTask task, AttemptResult result) {
        if (result.success()) {
            return NotificationStatus.SUCCESS;
        }
        if (result.retryable() && result.attemptNo() < task.maxAttempts()) {
            return NotificationStatus.PENDING;
        }
        return NotificationStatus.FAILED;
    }

    private NotificationTask mapTask(ResultSet rs, int rowNum) throws SQLException {
        return new NotificationTask(
                rs.getObject("id", UUID.class),
                rs.getString("source_system"),
                rs.getString("target_url"),
                rs.getString("method"),
                fromJson(payloadProtector.decrypt(rs.getString("headers_json"))),
                payloadProtector.decrypt(rs.getString("body")),
                rs.getString("request_fingerprint"),
                rs.getString("idempotency_key"),
                NotificationStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                instant(rs, "next_attempt_at"),
                instant(rs, "locked_until"),
                rs.getLong("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private DeliveryAttempt mapAttempt(ResultSet rs, int rowNum) throws SQLException {
        return new DeliveryAttempt(
                rs.getObject("id", UUID.class),
                rs.getObject("notification_id", UUID.class),
                rs.getInt("attempt_no"),
                AttemptStatus.valueOf(rs.getString("status")),
                (Integer) rs.getObject("response_status"),
                rs.getString("error_type"),
                rs.getString("error_detail"),
                rs.getLong("latency_ms"),
                instant(rs, "created_at"));
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String toJson(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize headers", e);
        }
    }

    private Map<String, String> fromJson(String json) {
        try {
            return objectMapper.readValue(json, HEADERS_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize headers", e);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
