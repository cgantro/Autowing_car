package com.project.domain.towingcar.service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a batch in one DB transaction.
 * Redis ACK happens only after this proxied method returns (transaction committed).
 */
@Repository
@RequiredArgsConstructor
public class DrivingLogBatchWriter {

    private static final String INSERT_SQL = """
            INSERT INTO driving_log
              (log_id, created_at, towing_car_id, mission_id, posx, posy,
               heading, velocity, battery, car_status, mission_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (created_at, log_id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void persist(List<BufferedDrivingLog> logs) {
        if (logs.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, logs, logs.size(), this::bind);
    }

    private void bind(PreparedStatement statement, BufferedDrivingLog log) throws SQLException {
        statement.setLong(1, log.logId());
        statement.setTimestamp(2, Timestamp.valueOf(log.createdAt()));
        statement.setLong(3, log.towingCarId());
        if (log.missionId() == null) {
            statement.setNull(4, Types.BIGINT);
        } else {
            statement.setLong(4, log.missionId());
        }
        statement.setDouble(5, log.posX());
        statement.setDouble(6, log.posY());
        statement.setDouble(7, log.heading());
        statement.setDouble(8, log.velocity());
        statement.setInt(9, log.battery());
        statement.setString(10, log.carStatus());
        statement.setString(11, log.missionStatus());
    }
}
