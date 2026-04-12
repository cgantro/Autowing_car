package com.project.domain.operation;

import com.project.domain.common.CarStatus;
import com.project.domain.common.MissionStatus;
import com.project.domain.mission.domain.MissionStateMachine;
import com.project.domain.mission.entity.Mission;
import com.project.domain.towingcar.domain.TowingCarStateMachine;
import com.project.domain.towingcar.entity.TowingCar;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MissionTowingStateCoordinator {

    private final TowingCarStateMachine towingCarStateMachine;
    private final MissionStateMachine missionStateMachine;

    public CarStatus resolveCarStatus(TowingCar car, CarStatus reportedStatus) {
        return towingCarStateMachine.resolve(car.getCarStatus(), reportedStatus);
    }

    public boolean canPauseMission(Mission mission) {
        return mission != null && missionStateMachine.canTransition(mission.getStatus(), MissionStatus.PAUSED);
    }

    public boolean canResumeMission(Mission mission) {
        return mission != null && missionStateMachine.canTransition(mission.getStatus(), MissionStatus.RUNNING);
    }

    public boolean canCompleteMission(Mission mission) {
        return mission != null && missionStateMachine.canTransition(mission.getStatus(), MissionStatus.COMPLETED);
    }
}
