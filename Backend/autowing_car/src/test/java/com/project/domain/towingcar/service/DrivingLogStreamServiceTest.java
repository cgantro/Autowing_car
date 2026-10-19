package com.project.domain.towingcar.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;
import com.project.domain.common.CarStatus;
import com.project.domain.towingcar.entity.TowingCar;

import java.util.List;
import java.util.Map;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.InOrder;

class DrivingLogStreamServiceTest {
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final DrivingLogStreamService service = new DrivingLogStreamService(redisTemplate, jdbcTemplate, transactionTemplate);

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "stream", "driving-log-stream");
        ReflectionTestUtils.setField(service, "configuredBatchSize", 500);
        ReflectionTestUtils.setField(service, "pendingMinIdleMillis", 30000L);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.pending(anyString(), anyString(), any(Range.class), anyLong())).thenReturn(null);
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getId()).thenReturn(RecordId.of("1-0"));
        when(record.getValue()).thenReturn(Map.ofEntries(
                Map.entry("eventId", "event-1"), Map.entry("createdAt", "2026-10-18T12:00:00"),
                Map.entry("towingCarId", "1"), Map.entry("posX", "1.0"), Map.entry("posY", "2.0"),
                Map.entry("heading", "0.5"), Map.entry("velocity", "0.0"), Map.entry("battery", "90"),
                Map.entry("carStatus", "IDLE"), Map.entry("missionId", ""), Map.entry("missionStatus", "")));
        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record));
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0)
                    .accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void acknowledgesOnlyAfterDatabaseBatchSucceeds() {
        when(jdbcTemplate.batchUpdate(anyString(), anyCollection(), anyInt(),
                any(ParameterizedPreparedStatementSetter.class))).thenReturn(new int[][] { { 1 } });

        service.flushBatch();

        InOrder order = inOrder(jdbcTemplate, streamOperations);
        order.verify(jdbcTemplate).batchUpdate(anyString(), anyCollection(), anyInt(),
                any(ParameterizedPreparedStatementSetter.class));
        order.verify(streamOperations).acknowledge(eq("driving-log-stream"), eq("driving-log-writers"),
                any(RecordId[].class));
    }

    @Test
    void leavesMessagesPendingWhenDatabaseBatchFails() {
        when(jdbcTemplate.batchUpdate(anyString(), anyCollection(), anyInt(),
                any(ParameterizedPreparedStatementSetter.class))).thenThrow(new IllegalStateException("DB unavailable"));

        service.flushBatch();

        verify(streamOperations, never()).acknowledge(anyString(), anyString(), any(RecordId[].class));
        verify(streamOperations, never()).delete(anyString(), any(RecordId[].class));
    }

    @Test
    void fallsBackToDatabaseWhenRedisAppendFails() {
        TowingCar car = TowingCar.builder().id(1L).code("TC01").battery(90).carStatus(CarStatus.IDLE)
                .lastPosX(1.0).lastPosY(2.0).lastHeading(0.5).lastVelocity(0.0).build();
        when(streamOperations.add(any(MapRecord.class))).thenThrow(new IllegalStateException("Redis unavailable"));

        service.append(car, null);

        verify(jdbcTemplate).update(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class));
    }

    @Test
    void persistsCanonicalLogBeforePublishingToRedis() {
        TowingCar car = TowingCar.builder().id(1L).code("TC01").battery(90).carStatus(CarStatus.IDLE)
                .lastPosX(1.0).lastPosY(2.0).lastHeading(0.5).lastVelocity(0.0).build();

        service.append(car, null);

        InOrder order = inOrder(jdbcTemplate, streamOperations);
        order.verify(jdbcTemplate).update(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class));
        order.verify(streamOperations).add(any(MapRecord.class));
    }

    @Test
    void appendsToRedisOnlyAfterVehicleStateTransactionCommits() {
        TowingCar car = TowingCar.builder().id(1L).code("TC01").battery(90).carStatus(CarStatus.IDLE)
                .lastPosX(1.0).lastPosY(2.0).lastHeading(0.5).lastVelocity(0.0).build();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.append(car, null);
            verify(jdbcTemplate).update(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class));
            verify(streamOperations, never()).add(any(MapRecord.class));

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            verify(streamOperations).add(any(MapRecord.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void rollbackDoesNotPublishAnOrphanedLogToRedis() {
        TowingCar car = TowingCar.builder().id(1L).code("TC01").battery(90).carStatus(CarStatus.IDLE)
                .lastPosX(1.0).lastPosY(2.0).lastHeading(0.5).lastVelocity(0.0).build();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.append(car, null);
            // A rollback never invokes afterCommit; clearing synchronization simulates that outcome.
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(streamOperations, never()).add(any(MapRecord.class));
    }

    @Test
    void batchSizeAndPendingClaimAgeAreIndependentSettings() {
        ReflectionTestUtils.setField(service, "configuredBatchSize", 750);
        ReflectionTestUtils.setField(service, "pendingMinIdleMillis", 45000L);

        Integer batchSize = ReflectionTestUtils.invokeMethod(service, "batchSize");
        assertEquals(750, batchSize.intValue());
        Duration minIdle = ReflectionTestUtils.invokeMethod(service, "pendingMinIdle");
        assertEquals(Duration.ofSeconds(45), minIdle);
    }
}
