package com.project.infra.mqtt.application;

import com.project.infra.mqtt.application.handler.MqttMessageHandler;
import com.project.infra.mqtt.config.MqttIncomingMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class MqttMessageRoutingService {

    private final Map<Class<?>, MqttMessageHandler<?>> handlers = new HashMap<>();

    public MqttMessageRoutingService(List<MqttMessageHandler<?>> handlerList) {
        for (MqttMessageHandler<?> handler : handlerList) {
            handlers.put(handler.supports(), handler);
        }
    }

    @SuppressWarnings("unchecked")
    public void route(MqttIncomingMessage message) {
        MqttMessageHandler<MqttIncomingMessage> handler =
                (MqttMessageHandler<MqttIncomingMessage>) handlers.get(message.getClass());

        if (handler == null) {
            log.warn("[MQTT] No message handler for type={}", message.getClass().getSimpleName());
            return;
        }

        handler.handle(message);
    }
}
