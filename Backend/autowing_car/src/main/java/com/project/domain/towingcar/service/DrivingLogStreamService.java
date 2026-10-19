package com.project.domain.towingcar.service;

import com.project.domain.mission.entity.Mission;
import com.project.domain.towingcar.entity.TowingCar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/** Buffers telemetry in Redis and persists it in idempotent JDBC batches. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DrivingLogStreamService {
    private static final String GROUP = "driving-log-writers";
    private static final String CONSUMER = UUID.randomUUID().toString();

    private final RedisTemplate<String, String> redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.driving-log.stream:driving-log-stream}")
    private String stream;
    @Value("${app.driving-log.batch-size:500}")
    private int configuredBatchSize;
    @Value("${app.driving-log.pending-min-idle-ms:30000}")
    private long pendingMinIdleMillis;

    private int batchSize() { return Math.max(1, configuredBatchSize); }
    private Duration pendingMinIdle() { return Duration.ofMillis(Math.max(0, pendingMinIdleMillis)); }
    private static final String INSERT_SQL =
            "INSERT INTO driving_log (log_id, created_at, pos_x, pos_y, heading, velocity, battery, car_status, mission_status, towing_car_id, mission_id, event_id) " +
                    "VALUES (nextval('driving_log_seq'), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (event_id, created_at) DO NOTHING";

    public void append(TowingCar car, Mission mission) {
        Map<String, String> fields = new HashMap<>();
        fields.put("eventId", UUID.randomUUID().toString());
        fields.put("createdAt", LocalDateTime.now().toString());
        fields.put("towingCarId", car.getId().toString());
        fields.put("posX", String.valueOf(car.getLastPosX()));
        fields.put("posY", String.valueOf(car.getLastPosY()));
        fields.put("heading", String.valueOf(car.getLastHeading()));
        fields.put("velocity", String.valueOf(car.getLastVelocity()));
        fields.put("battery", String.valueOf(car.getBattery()));
        fields.put("carStatus", car.getCarStatus().name());
        fields.put("missionId", mission == null ? "" : String.valueOf(mission.getId()));
        fields.put("missionStatus", mission == null || mission.getStatus() == null ? "" : mission.getStatus().name());
        // Persist with the caller's vehicle-state transaction first. Redis is a
        // delivery buffer, so a crash between DB commit and XADD cannot lose the log.
        persistRecord(fields);
        Runnable appendAfterCommit = () -> appendToStream(fields, car.getCode());
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            // Do not publish a log for vehicle state that may still roll back.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    appendAfterCommit.run();
                }
            });
        } else {
            appendAfterCommit.run();
        }
    }

    private void appendToStream(Map<String, String> fields, String carCode) {
        try {
            redisTemplate.opsForStream().add(StreamRecords.mapBacked(fields).withStreamKey(stream));
        } catch (RuntimeException redisFailure) {
            // The canonical DB record is already committed with the vehicle state.
            log.warn("Redis Stream unavailable; driving log remains safely persisted in the database: carCode={}",
                    carCode, redisFailure);
        }
    }

    @Scheduled(fixedDelayString = "${app.driving-log.flush-interval-ms:1000}")
    public void flushBatch() {
        try {
            ensureGroup();
            List<MapRecord<String, Object, Object>> records = reclaimPending();
            if (records.isEmpty()) {
                List<MapRecord<String, Object, Object>> fresh = redisTemplate.opsForStream().read(
                Consumer.from(GROUP, CONSUMER), StreamReadOptions.empty().count(batchSize()).block(Duration.ofMillis(100)),
                        StreamOffset.create(stream, ReadOffset.lastConsumed()));
                records = fresh == null ? List.of() : fresh;
            }
            if (records.isEmpty()) return;

            persistBatch(records);
            // ACK only after the JDBC transaction commits. A crash before ACK replays
            // the same event IDs; the database unique key makes that replay harmless.
            redisTemplate.opsForStream().acknowledge(stream, GROUP,
                    records.stream().map(MapRecord::getId).toArray(RecordId[]::new));
            redisTemplate.opsForStream().delete(stream,
                    records.stream().map(MapRecord::getId).toArray(RecordId[]::new));
        } catch (Exception e) {
            log.error("Driving log batch failed; stream messages remain pending for retry", e);
        }
    }

    private void ensureGroup() {
        try {
            redisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0-0"), GROUP);
        } catch (RedisSystemException e) {
            if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) throw e;
        }
    }

    private List<MapRecord<String, Object, Object>> reclaimPending() {
        PendingMessages pending = redisTemplate.opsForStream().pending(stream, GROUP, Range.unbounded(), batchSize());
        if (pending == null || pending.isEmpty()) return List.of();
        RecordId[] ids = new RecordId[pending.size()];
        int i = 0;
        for (PendingMessage message : pending) ids[i++] = message.getId();
        List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                .claim(stream, GROUP, CONSUMER, pendingMinIdle(), ids);
        return claimed == null ? List.of() : claimed;
    }

    private void persistBatch(List<MapRecord<String, Object, Object>> records) {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.batchUpdate(
                INSERT_SQL,
                records, records.size(), (ps, record) -> {
                    bindRecord(ps, record.getValue());
                }));
    }

    private void persistRecord(Map<String, String> record) {
        jdbcTemplate.update(INSERT_SQL, ps -> bindRecord(ps, record));
    }

    private static void bindRecord(java.sql.PreparedStatement ps, Map<?, ?> values) throws java.sql.SQLException {
        ps.setObject(1, LocalDateTime.parse(value(values, "createdAt")));
        ps.setDouble(2, Double.parseDouble(value(values, "posX")));
        ps.setDouble(3, Double.parseDouble(value(values, "posY")));
        ps.setDouble(4, Double.parseDouble(value(values, "heading")));
        ps.setDouble(5, Double.parseDouble(value(values, "velocity")));
        ps.setInt(6, Integer.parseInt(value(values, "battery")));
        ps.setString(7, value(values, "carStatus"));
        ps.setString(8, emptyToNull(value(values, "missionStatus")));
        ps.setLong(9, Long.parseLong(value(values, "towingCarId")));
        String missionId = emptyToNull(value(values, "missionId"));
        if (missionId == null) ps.setNull(10, java.sql.Types.BIGINT);
        else ps.setLong(10, Long.parseLong(missionId));
        ps.setString(11, value(values, "eventId"));
    }

    private static String value(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value == null) throw new IllegalArgumentException("Missing driving log field: " + key);
        return value.toString();
    }

    private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
