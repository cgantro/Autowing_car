package com.project.domain.towingcar.entity;

import com.project.domain.common.CarStatus;
import com.project.domain.common.MissionStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@IdClass(DrivingLogId.class) // [NEW] Composite Key
@Table(name = "driving_log", indexes = {
        @Index(name = "idx_driving_log_car_time", columnList = "TOWING_CAR_ID, CREATED_AT"),
        @Index(name = "uk_driving_log_event_time", columnList = "event_id, CREATED_AT", unique = true)
})
public class DrivingLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "log_seq_gen")
    @SequenceGenerator(name = "log_seq_gen", sequenceName = "driving_log_seq", allocationSize = 1)
    @Column(name = "log_id")
    private Long id;

    @Id // [NEW] Part of PK for TimescaleDB
    @Column(nullable = false)
    private LocalDateTime createdAt;

    private Double posX;
    private Double posY;
    private Double heading;
    private Double velocity;
    private Integer battery;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CarStatus carStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MissionStatus missionStatus;

    // ★ FK 없이 ID만 저장
    @Column(name = "TOWING_CAR_ID", nullable = false)
    private Long towingCarId;

    @Column(name = "MISSION_ID")
    private Long missionId;

    @Column(name = "event_id", length = 36)
    private String eventId;

    @Builder
    public DrivingLog(Double posX, Double posY, Double heading, Double velocity, Integer battery,
            CarStatus carStatus, MissionStatus missionStatus, Long towingCarId, Long missionId) {
        this.createdAt = LocalDateTime.now();
        this.posX = posX;
        this.posY = posY;
        this.heading = heading;
        this.velocity = velocity;
        this.battery = battery;
        this.carStatus = carStatus;
        this.missionStatus = missionStatus;
        this.towingCarId = towingCarId;
        this.missionId = missionId;
    }
}
