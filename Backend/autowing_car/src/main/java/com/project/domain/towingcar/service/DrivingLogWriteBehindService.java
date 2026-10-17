package com.project.domain.towingcar.service;

import com.project.domain.towingcar.entity.DrivingLog;
import com.project.global.util.TxUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Opt-in Redis Streams write-behind for telemetry ONLY.
 *
 * Vehicle/mission state still commits synchronously to PostgreSQL.
 * Stream append is deferred until the surrounding DB transaction commits.
 * A single stable consumer replays pending entries after a restart.
 *
 * NOTE: DB commit followed by Redis append is not atomic. A Redis outage at
 * append time can lose a telemetry record. For strict losslessness, use a
 * transactional DB outbox instead. Do not use this service for business state.
 */
@Slf4j
@Service
public class DrivingLogWriteBehindService {
    static final String STREAM_KEY = "towing_car:driving_log:stream";
    static final String GROUP = "driving-log-db-writer";

    private final RedisTemplate<String, String> redisTemplate;
    private final DrivingLogBatchWriter batchWriter;

    @Value("${app.driving-log-write-behind.enabled:false}")
    private boolean enabled;

    @Value("${app.driving-log-write-behind.batch-size:500}")
    private int batchSize;

    @Value("${app.driving-log-write-behind.max-batches-per-poll:20}")
    private int maxBatchesPerPoll;

    @Value("${app.driving-log-write-behind.consumer-name:writer-1}")
    private String consumerName;

    private volatile boolean groupReady;

    public DrivingLogWriteBehindService(
            @Qualifier("redisTemplate") RedisTemplate<String, String> redisTemplate,
            DrivingLogBatchWriter batchWriter) {
        this.redisTemplate = redisTemplate;
        this.batchWriter = batchWriter;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void enqueueAfterCommit(DrivingLog logEntry) {
        Map<String, String> snapshot = serialize(logEntry);
        TxUtil.executeAfterCommit(() -> {
            RecordId id = redisTemplate.opsForStream().add(STREAM_KEY, snapshot);
            if (id == null) {
                throw new IllegalStateException("Redis Stream XADD returned no record ID");
            }
        });
    }

    @Scheduled(fixedDelayString = "${app.driving-log-write-behind.poll-ms:250}")
    public void flushScheduled() {
        if (!enabled) {
            return;
        }

        try {
            ensureGroup();
            for (int i = 0; i < maxBatchesPerPoll; ++i) {
                if (!flushOneBatch()) {
                    break;
                }
            }
        } catch (Exception e) {
            if (containsMessage(e, "NOGROUP")) {
                groupReady = false;
            }
            log.error("Driving log write-behind flush failed; records remain pending for retry", e);
        }
    }

    boolean flushOneBatch() {
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
        Consumer consumer = Consumer.from(GROUP, consumerName);
        StreamReadOptions options = StreamReadOptions.empty().count(batchSize);

        // Read this stable consumer's pending entries first (including on restart).
        List<MapRecord<String, String, String>> records =
                ops.read(consumer, options, StreamOffset.create(STREAM_KEY, ReadOffset.from("0")));
        if (records == null || records.isEmpty()) {
            records = ops.read(consumer, options,
                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
        }
        if (records == null || records.isEmpty()) {
            return false;
        }

        List<BufferedDrivingLog> logs = new ArrayList<>(records.size());
        List<String> ids = new ArrayList<>(records.size());
        for (MapRecord<String, String, String> record : records) {
            logs.add(BufferedDrivingLog.fromRecord(record));
            ids.add(record.getId().getValue());
        }

        // The @Transactional method returns only after the DB commit completes.
        batchWriter.persist(logs);

        String[] recordIds = ids.toArray(String[]::new);
        Long acknowledged = ops.acknowledge(STREAM_KEY, GROUP, recordIds);
        if (acknowledged == null || acknowledged != recordIds.length) {
            throw new IllegalStateException(
                    "Redis Stream ACK count mismatch: " + acknowledged + " / " + recordIds.length);
        }
        try {
            ops.delete(STREAM_KEY, recordIds);
        } catch (Exception e) {
            // Already ACKed: old entries are harmless but require later cleanup.
            log.warn("Driving log persisted and ACKed, but stream cleanup failed", e);
        }
        return true;
    }

    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0-0"), GROUP);
        } catch (RuntimeException e) {
            if (!containsMessage(e, "BUSYGROUP")) {
                throw e;
            }
        }
        groupReady = true;
    }

    private static boolean containsMessage(Throwable error, String token) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> serialize(DrivingLog logEntry) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("createdAt", logEntry.getCreatedAt().toString());
        map.put("towingCarId", String.valueOf(logEntry.getTowingCarId()));
        map.put("missionId", logEntry.getMissionId() == null ? "" : logEntry.getMissionId().toString());
        map.put("posX", String.valueOf(logEntry.getPosX()));
        map.put("posY", String.valueOf(logEntry.getPosY()));
        map.put("heading", String.valueOf(logEntry.getHeading()));
        map.put("velocity", String.valueOf(logEntry.getVelocity()));
        map.put("battery", String.valueOf(logEntry.getBattery()));
        map.put("carStatus", logEntry.getCarStatus().name());
        map.put("missionStatus", logEntry.getMissionStatus().name());
        return map;
    }
}
