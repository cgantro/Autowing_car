package com.project.infra.mqtt.application.handler;

import com.project.domain.map.service.MapWebSocketService;
import com.project.infra.mqtt.config.MqttIncomingMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MapMqttMessageHandler implements MqttMessageHandler<MqttIncomingMessage.MapData> {

    private final MapWebSocketService mapWebSocketService;

    @Override
    public Class<MqttIncomingMessage.MapData> supports() {
        return MqttIncomingMessage.MapData.class;
    }

    @Override
    public void handle(MqttIncomingMessage.MapData message) {
        mapWebSocketService.broadcastMapInfo(message.getPayload());
    }
}
