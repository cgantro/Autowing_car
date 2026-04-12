package com.project.domain.towingcar.service;

import com.project.domain.towingcar.constant.CarCommand;
import com.project.infra.mqtt.constant.MqttTopics;
import com.project.infra.mqtt.service.MqttService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class TowingCarMqttService {

    private final MqttService mqttService;
    private final CommandAckService commandAckService;
    private final Map<String, AtomicLong> sequencePerVehicle = new ConcurrentHashMap<>();

    private void publishControl(String carCode, CarCommand carCommand, Map<String, Object> payload) {
        String commandId = UUID.randomUUID().toString();
        long sequence = sequencePerVehicle.computeIfAbsent(carCode, key -> new AtomicLong(0L)).incrementAndGet();

        Map<String, Object> envelope = new java.util.HashMap<>(payload);
        envelope.put("commandId", commandId);
        envelope.put("sequence", sequence);
        envelope.put("commandType", carCommand.getCmd());

        commandAckService.registerPending(new CommandAckService.PendingCommand(carCode, carCommand.getCmd(), sequence, commandId));
        mqttService.publish(MqttTopics.cmdControl(carCode), envelope);
    }

    public void connectCar(String carCode, Long flightId) {
        publishControl(carCode, CarCommand.CONNECT, Map.of("cmd", CarCommand.CONNECT.getCmd(), "flightId", flightId));
    }

    public void disconnectCar(String carCode, Long flightId) {
        publishControl(carCode, CarCommand.DISCONNECT, Map.of("cmd", CarCommand.DISCONNECT.getCmd(), "flightId", flightId));
    }

    public void emergencyStop(String carCode) {
        publishControl(carCode, CarCommand.EMERGENCY_STOP, Map.of("cmd", CarCommand.EMERGENCY_STOP.getCmd()));
    }

    public void setMode(String carCode, String mode) {
        publishControl(carCode, CarCommand.SET_MODE, Map.of("cmd", CarCommand.SET_MODE.getCmd(), "mode", mode));
    }

    public void moveCar(String carCode, String type) {
        publishControl(carCode, CarCommand.MOVE, Map.of("cmd", CarCommand.MOVE.getCmd(), "type", type));
    }

    public void sendDriveCommand(String carCode, Object payload) {
        mqttService.publish(MqttTopics.cmdDrive(carCode), payload);
    }

    public void resumeCar(String carCode) {
        publishControl(carCode, CarCommand.RESUME, Map.of("cmd", CarCommand.RESUME.getCmd()));
    }
}
