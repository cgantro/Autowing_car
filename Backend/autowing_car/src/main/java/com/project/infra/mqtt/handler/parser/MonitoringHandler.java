package com.project.infra.mqtt.handler.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.infra.mqtt.config.MqttIncomingMessage;
import org.springframework.stereotype.Component;

@Component
public class MonitoringHandler implements MqttTopicHandler {

    @Override
    public String getTopicType() {
        return "monitoring";
    }

    @Override
    public MqttIncomingMessage parse(String topic, JsonNode payload) {
        String carCode = MqttPayloadParserSupport.requiredCarCode(payload, "Monitoring");

        return MqttIncomingMessage.CarData.builder()
                .carCode(carCode)
                .messageType(getTopicType())
                .payload(payload)
                .build();
    }
}
