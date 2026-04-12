package com.project.infra.mqtt.application.handler;

import com.project.domain.towingcar.service.CommandAckService;
import com.project.infra.mqtt.application.dto.AckPayloadDto;
import com.project.infra.mqtt.config.MqttIncomingMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AckMqttMessageHandler implements MqttMessageHandler<MqttIncomingMessage.Ack> {

    private final CommandAckService commandAckService;

    @Override
    public Class<MqttIncomingMessage.Ack> supports() {
        return MqttIncomingMessage.Ack.class;
    }

    @Override
    public void handle(MqttIncomingMessage.Ack message) {
        AckPayloadDto ack = AckPayloadDto.from(message.getCarCode(), message.getPayload());
        if (ack.getCommandType() == null || ack.getSequence() == null || ack.getCommandId() == null) {
            log.warn("[ACK] Missing identifiers. car={}, payload={}", message.getCarCode(), message.getPayload());
            return;
        }

        CommandAckService.AckResult result = commandAckService.acknowledge(
                ack.getCarCode(), ack.getCommandType(), ack.getSequence(), ack.getCommandId());
        log.info("[ACK] result={}, car={}, commandType={}, seq={}, commandId={}",
                result, ack.getCarCode(), ack.getCommandType(), ack.getSequence(), ack.getCommandId());
    }
}
