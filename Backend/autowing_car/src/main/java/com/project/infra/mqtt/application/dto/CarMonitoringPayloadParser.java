package com.project.infra.mqtt.application.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.domain.common.CarStatus;
import org.springframework.stereotype.Component;

@Component
public class CarMonitoringPayloadParser {

    public CarMonitoringDto parse(String carCode, JsonNode payload) {
        return CarMonitoringDto.builder()
                .carCode(carCode)
                .x(payload.path("x").asDouble())
                .y(payload.path("y").asDouble())
                .heading(payload.path("yaw").asDouble())
                .velocity(payload.path("v").asDouble())
                .battery(payload.path("battery").asInt())
                .status(CarStatus.from(payload.path("mode").asText()))
                .build();
    }
}
