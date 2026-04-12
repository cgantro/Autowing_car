package com.project.infra.mqtt.application.handler;

import com.project.infra.mqtt.config.MqttIncomingMessage;
import com.project.infra.mqtt.application.dto.CarMonitoringPayloadParser;
import com.project.domain.towingcar.service.TowingCarService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MonitoringMqttMessageHandler implements MqttMessageHandler<MqttIncomingMessage.CarData> {

    private final CarMonitoringPayloadParser parser;
    private final TowingCarService towingCarService;

    @Override
    public Class<MqttIncomingMessage.CarData> supports() {
        return MqttIncomingMessage.CarData.class;
    }

    @Override
    public void handle(MqttIncomingMessage.CarData message) {
        towingCarService.processCarMonitoring(parser.parse(message.getCarCode(), message.getPayload()));
    }
}
