package com.project.infra.mqtt.handler;

import com.project.infra.mqtt.application.MqttMessageRoutingService;
import com.project.infra.mqtt.config.MqttIncomingMessage;
import com.project.infra.mqtt.handler.parser.MqttIncomingMessageParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MqttSignalProcessorImpl implements MqttSignalProcessor {

    private final MqttIncomingMessageParser parser;
    private final MqttMessageRoutingService routingService;

    @Override
    public void process(String topic, String payload) {
        MqttIncomingMessage message = parser.parse(topic, payload);
        routingService.route(message);
    }
}
