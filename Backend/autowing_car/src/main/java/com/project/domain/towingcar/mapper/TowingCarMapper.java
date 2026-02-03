package com.project.domain.towingcar.mapper;

import com.project.domain.towingcar.dto.TowingCarStatusResponse;
import com.project.domain.towingcar.dto.TowingCarWebSocketDtos.TowingCarDTO;
import com.project.domain.towingcar.entity.TowingCar;
import org.springframework.stereotype.Component;

@Component
public class TowingCarMapper {

    public TowingCarStatusResponse toResponseDTO(TowingCar car) {
        StatusValues values = toStatusValues(car);
        return TowingCarStatusResponse.builder()
                .code(values.code())
                .posX(values.posX())
                .posY(values.posY())
                .heading(values.heading())
                .velocity(values.velocity())
                .battery(values.battery())
                .status(values.status())
                .build();
    }

    public TowingCarDTO toDTO(TowingCar car) {
        StatusValues values = toStatusValues(car);
        return TowingCarDTO.builder()
                .code(values.code())
                .posX(values.posX())
                .posY(values.posY())
                .heading(values.heading())
                .velocity(values.velocity())
                .battery(values.battery())
                .status(values.status())
                .build();
    }

    private StatusValues toStatusValues(TowingCar car) {
        return new StatusValues(
                car.getCode(),
                car.getLastPosX() != null ? car.getLastPosX() : 0.0,
                car.getLastPosY() != null ? car.getLastPosY() : 0.0,
                car.getLastHeading() != null ? car.getLastHeading() : 0.0,
                car.getLastVelocity() != null ? car.getLastVelocity() : 0.0,
                car.getBattery(),
                car.getCarStatus().name());
    }

    private record StatusValues(
            String code,
            Double posX,
            Double posY,
            Double heading,
            Double velocity,
            Integer battery,
            String status) {}
}
