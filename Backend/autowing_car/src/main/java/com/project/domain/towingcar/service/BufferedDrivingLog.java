package com.project.domain.towingcar.service;

import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.data.redis.connection.stream.MapRecord;

/**
 * Immutable log snapshot stored in a Redis Stream.
 * A stable negative log_id derived from the stream record ID makes DB retries idempotent.
 * Existing JPA sequence-generated log IDs remain positive.
 */
record BufferedDrivingLog(
        long logId,
        LocalDateTime createdAt,
        long towingCarId,
        Long missionId,
        double posX,
        double posY,
        double heading,
        double velocity,
        int battery,
        String carStatus,
        String missionStatus) {

    private static final long SEQUENCE_MULTIPLIER = 1_000_000L;

    static BufferedDrivingLog fromRecord(MapRecord<String, String, String> record) {
        Map<String, String> data = record.getValue();
        String[] parts = record.getId().getValue().split("-", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid Redis stream record ID: " + record.getId());
        }
        long millis = Long.parseLong(parts[0]);
        long sequence = Long.parseLong(parts[1]);
        if (sequence < 0 || sequence >= SEQUENCE_MULTIPLIER) {
            throw new IllegalArgumentException("Redis stream sequence exceeds supported range: " + sequence);
        }
        long stableId = -Math.addExact(Math.multiplyExact(millis, SEQUENCE_MULTIPLIER), sequence);
        String mission = data.get("missionId");
        return new BufferedDrivingLog(
                stableId,
                LocalDateTime.parse(required(data, "createdAt")),
                Long.parseLong(required(data, "towingCarId")),
                mission == null || mission.isBlank() ? null : Long.parseLong(mission),
                Double.parseDouble(required(data, "posX")),
                Double.parseDouble(required(data, "posY")),
                Double.parseDouble(required(data, "heading")),
                Double.parseDouble(required(data, "velocity")),
                Integer.parseInt(required(data, "battery")),
                required(data, "carStatus"),
                required(data, "missionStatus"));
    }

    private static String required(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing driving log stream field: " + key);
        }
        return value;
    }
}
