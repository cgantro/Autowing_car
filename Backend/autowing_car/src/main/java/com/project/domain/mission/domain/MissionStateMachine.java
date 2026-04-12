package com.project.domain.mission.domain;

import com.project.domain.common.MissionStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
public class MissionStateMachine {

    private final Map<MissionStatus, Set<MissionStatus>> transitions = new EnumMap<>(MissionStatus.class);

    public MissionStateMachine() {
        transitions.put(MissionStatus.RUNNING, EnumSet.of(MissionStatus.PAUSED, MissionStatus.COMPLETED));
        transitions.put(MissionStatus.PAUSED, EnumSet.of(MissionStatus.RUNNING, MissionStatus.COMPLETED));
        transitions.put(MissionStatus.COMPLETED, EnumSet.noneOf(MissionStatus.class));
    }

    public boolean canTransition(MissionStatus from, MissionStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        return transitions.getOrDefault(from, EnumSet.noneOf(MissionStatus.class)).contains(to);
    }
}
