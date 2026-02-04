package com.project.domain.towingcar.service;

import com.project.domain.common.CarStatus;
import com.project.domain.towingcar.dto.TowingCarStatusResponse;
import com.project.domain.towingcar.entity.TowingCar;
import com.project.domain.towingcar.mapper.TowingCarMapper;
import com.project.domain.towingcar.service.TowingCarCacheService;
import com.project.domain.mission.service.MissionDBAdaptor;
import com.project.domain.flight.service.FlightDBAdaptor;
import com.project.domain.map.service.MapDBAdaptor;
import com.project.domain.map.service.MapService;
import com.project.domain.operation.MissionTowingStateCoordinator;
import com.project.domain.user.service.UserDBAdaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TowingCarServiceReadConsistencyTest {
    @Mock private UserDBAdaptor userDBAdaptor;
    @Mock private TowingCarDBAdaptor towingCarDBAdaptor;
    @Mock private MissionDBAdaptor missionDBAdaptor;
    @Mock private FlightDBAdaptor flightDBAdaptor;
    @Mock private MapDBAdaptor mapDBAdaptor;
    @Mock private MapService mapService;
    @Mock private TowingCarMqttService towingCarMqttService;
    @Mock private TowingCarWebSocketService towingCarWebSocketService;
    @Mock private TowingCarCacheService towingCarCacheService;
    @Mock private DrivingLogStreamService drivingLogStreamService;
    @Mock private TowingCarMapper towingCarMapper;
    @Mock private MissionTowingStateCoordinator stateCoordinator;
    @InjectMocks private TowingCarService service;

    @Test
    void fullVehicleListUsesDatabaseInventoryEvenWhenRedisContainsOnlyPartOfIt() {
        TowingCar first = car(1L, "TC01");
        TowingCar second = car(2L, "TC02");
        TowingCarStatusResponse firstResponse = TowingCarStatusResponse.builder().code("TC01").status("IDLE").build();
        TowingCarStatusResponse secondResponse = TowingCarStatusResponse.builder().code("TC02").status("IDLE").build();
        when(towingCarDBAdaptor.findAllCars()).thenReturn(List.of(first, second));
        when(towingCarMapper.toResponseDTO(first)).thenReturn(firstResponse);
        when(towingCarMapper.toResponseDTO(second)).thenReturn(secondResponse);

        List<TowingCarStatusResponse> result = service.getAllTowingCars();

        assertEquals(List.of(firstResponse, secondResponse), result);
        verify(towingCarCacheService, never()).getAllCars();
    }

    private static TowingCar car(long id, String code) {
        return TowingCar.builder().id(id).code(code).battery(90).carStatus(CarStatus.IDLE)
                .lastPosX(0.0).lastPosY(0.0).lastHeading(0.0).lastVelocity(0.0).build();
    }
}
