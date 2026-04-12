package com.project.domain.towingcar.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class CommandAckService {

    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();
    private final Set<String> acknowledgedKeys = ConcurrentHashMap.newKeySet();

    public void registerPending(PendingCommand command) {
        pendingCommands.put(command.pendingKey(), command);
    }

    public AckResult acknowledge(String vehicleId, String commandType, Long sequence, String commandId) {
        String key = pendingKey(vehicleId, commandType, sequence, commandId);

        if (acknowledgedKeys.contains(key)) {
            return AckResult.DUPLICATE;
        }

        PendingCommand pending = pendingCommands.remove(key);
        if (pending == null) {
            return AckResult.OUT_OF_ORDER_OR_UNKNOWN;
        }

        acknowledgedKeys.add(key);
        return AckResult.MATCHED;
    }

    public static String pendingKey(String vehicleId, String commandType, Long sequence, String commandId) {
        return vehicleId + "|" + commandType + "|" + sequence + "|" + commandId;
    }

    public enum AckResult {
        MATCHED,
        DUPLICATE,
        OUT_OF_ORDER_OR_UNKNOWN
    }

    @Getter
    public static class PendingCommand {
        private final String vehicleId;
        private final String commandType;
        private final Long sequence;
        private final String commandId;
        private final Instant createdAt;

        public PendingCommand(String vehicleId, String commandType, Long sequence, String commandId) {
            this.vehicleId = vehicleId;
            this.commandType = commandType;
            this.sequence = sequence;
            this.commandId = commandId;
            this.createdAt = Instant.now();
        }

        public String pendingKey() {
            return pendingKey(vehicleId, commandType, sequence, commandId);
        }
    }
}
