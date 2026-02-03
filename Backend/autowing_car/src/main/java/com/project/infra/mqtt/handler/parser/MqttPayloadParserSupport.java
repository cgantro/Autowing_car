package com.project.infra.mqtt.handler.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.global.error.domain.mqtt.MalformedMqttPayloadException;

/** Shared payload-field parsing for MQTT topic handlers. */
final class MqttPayloadParserSupport {

    private MqttPayloadParserSupport() {
    }

    static String requiredCarCode(JsonNode payload, String messageType) {
        if (payload.has("car_code")) {
            return payload.get("car_code").asText();
        }
        if (payload.has("carId")) {
            return payload.get("carId").asText();
        }
        throw new MalformedMqttPayloadException(messageType, new String[] { "car_code", "carId" });
    }
}
