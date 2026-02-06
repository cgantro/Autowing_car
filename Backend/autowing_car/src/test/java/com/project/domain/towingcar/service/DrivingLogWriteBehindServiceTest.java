package com.project.domain.towingcar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.test.util.ReflectionTestUtils;

@SuppressWarnings("unchecked")
class DrivingLogWriteBehindServiceTest {
    private RedisTemplate<String, String> redis;
    private StreamOperations<String, String, String> streams;
    private DrivingLogBatchWriter writer;
    private DrivingLogWriteBehindService service;

    @BeforeEach
    void setUp() {
        redis = mock(RedisTemplate.class);
        streams = mock(StreamOperations.class);
        writer = mock(DrivingLogBatchWriter.class);
        when(redis.opsForStream()).thenReturn(streams);
        service = new DrivingLogWriteBehindService(redis, writer);
        ReflectionTestUtils.setField(service, "batchSize", 500);
        ReflectionTestUtils.setField(service, "consumerName", "writer-1");
    }

    private MapRecord<String, String, String> telemetry() {
        MapRecord<String, String, String> record = mock(MapRecord.class);
        when(record.getId()).thenReturn(RecordId.of("1760760000000-7"));
        when(record.getValue()).thenReturn(Map.of(
                "createdAt", "2026-10-18T00:00:00.123",
                "towingCarId", "12",
                "missionId", "",
                "posX", "1.5",
                "posY", "2.5",
                "heading", "0.25",
                "velocity", "3.0",
                "battery", "75",
                "carStatus", "IDLE",
                "missionStatus", "RUNNING"));
        return record;
    }

    @Test
    void stableIdAndTimestampSurviveRedelivery() {
        var first = BufferedDrivingLog.fromRecord(telemetry());
        var second = BufferedDrivingLog.fromRecord(telemetry());
        assertEquals(first.logId(), second.logId());
        assertEquals(first.createdAt(), second.createdAt());
        assertTrue(first.logId() < 0); // No conflict with legacy positive sequence IDs.
        assertEquals(12, first.towingCarId());
    }

    @Test
    void databaseCommitComesBeforeAcknowledgement() {
        var record = telemetry();
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class),
                org.mockito.ArgumentMatchers.<StreamOffset<String>>any()))
                .thenReturn(List.of(record));
        when(streams.acknowledge(eq(DrivingLogWriteBehindService.STREAM_KEY),
                eq(DrivingLogWriteBehindService.GROUP), any(String[].class))).thenReturn(1L);

        assertTrue(service.flushOneBatch());

        InOrder order = inOrder(writer, streams);
        order.verify(writer).persist(anyList());
        order.verify(streams).acknowledge(eq(DrivingLogWriteBehindService.STREAM_KEY),
                eq(DrivingLogWriteBehindService.GROUP), any(String[].class));
        verify(streams).delete(eq(DrivingLogWriteBehindService.STREAM_KEY), any(String[].class));
    }

    @Test
    void databaseFailureLeavesRecordPending() {
        var record = telemetry();
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class),
                org.mockito.ArgumentMatchers.<StreamOffset<String>>any()))
                .thenReturn(List.of(record));
        doThrow(new IllegalStateException("database unavailable")).when(writer).persist(anyList());

        assertThrows(IllegalStateException.class, service::flushOneBatch);

        verify(streams, never()).acknowledge(
                eq(DrivingLogWriteBehindService.STREAM_KEY),
                eq(DrivingLogWriteBehindService.GROUP), any(String[].class));
        verify(streams, never()).delete(
                eq(DrivingLogWriteBehindService.STREAM_KEY), any(String[].class));
    }

    @Test
    void rejectsUnexpectedSequenceOverflowInsteadOfRiskingKeyCollision() {
        MapRecord<String, String, String> record = telemetry();
        when(record.getId()).thenReturn(RecordId.of("1760760000000-1000000"));
        assertThrows(IllegalArgumentException.class,
                () -> BufferedDrivingLog.fromRecord(record));
    }
}
