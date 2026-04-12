package com.project.infra.mqtt.application.dto;

import com.project.domain.common.CarStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CarMonitoringDto {
    private String carCode;
    private double x;
    private double y;
    private double heading;
    private double velocity;
    private int battery;
    private CarStatus status;
}
