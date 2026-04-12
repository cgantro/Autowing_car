package com.project.domain.towingcar.domain;

import com.project.domain.common.CarStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
public class TowingCarStateMachine {

    private final Map<CarStatus, Set<CarStatus>> transitions = new EnumMap<>(CarStatus.class);

    public TowingCarStateMachine() {
        transitions.put(CarStatus.IDLE, EnumSet.of(CarStatus.MOVING_TO_GATE, CarStatus.ERROR));
        transitions.put(CarStatus.MOVING_TO_GATE, EnumSet.of(CarStatus.DOCKING, CarStatus.STOP, CarStatus.ERROR));
        transitions.put(CarStatus.DOCKING, EnumSet.of(CarStatus.TOWING, CarStatus.STOP, CarStatus.ERROR));
        transitions.put(CarStatus.TOWING, EnumSet.of(CarStatus.UNDOCKING, CarStatus.STOP, CarStatus.ERROR));
        transitions.put(CarStatus.UNDOCKING, EnumSet.of(CarStatus.WAITING_FOR_RETURN, CarStatus.STOP, CarStatus.ERROR));
        transitions.put(CarStatus.WAITING_FOR_RETURN, EnumSet.of(CarStatus.RETURNING, CarStatus.STOP, CarStatus.ERROR));
        transitions.put(CarStatus.RETURNING, EnumSet.of(CarStatus.IDLE, CarStatus.STOP, CarStatus.ERROR));
        transitions.put(CarStatus.STOP, EnumSet.of(CarStatus.MOVING_TO_GATE, CarStatus.TOWING, CarStatus.RETURNING, CarStatus.ERROR));
        transitions.put(CarStatus.ERROR, EnumSet.of(CarStatus.IDLE));
    }

    public boolean canTransition(CarStatus from, CarStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        return transitions.getOrDefault(from, EnumSet.noneOf(CarStatus.class)).contains(to);
    }

    public CarStatus resolve(CarStatus current, CarStatus requested) {
        return canTransition(current, requested) ? requested : current;
    }
}
