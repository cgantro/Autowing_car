package com.project.infra.mqtt.application.handler;

import com.project.infra.mqtt.config.MqttIncomingMessage;

public interface MqttMessageHandler<T extends MqttIncomingMessage> {
    Class<T> supports();

    void handle(T message);
}
