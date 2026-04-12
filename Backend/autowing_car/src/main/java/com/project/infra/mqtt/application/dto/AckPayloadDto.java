package com.project.infra.mqtt.application.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AckPayloadDto {
    private String carCode;
    private String commandType;
    private String commandId;
    private Long sequence;

    public static AckPayloadDto from(String carCode, JsonNode payload) {
        Long seq = payload.has("sequence") ? payload.path("sequence").asLong() : null;
        String cmdType = payload.path("commandType").asText(payload.path("cmd").asText(null));
        String cmdId = payload.path("commandId").asText(payload.path("msgId").asText(null));

        return AckPayloadDto.builder()
                .carCode(carCode)
                .commandType(cmdType)
                .commandId(cmdId)
                .sequence(seq)
                .build();
    }
}
