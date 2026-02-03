package com.project.domain.towingcar.service;

import java.util.List;

import org.springframework.stereotype.Component;
import com.project.domain.common.CarStatus;
import com.project.domain.towingcar.entity.DrivingLog;
import com.project.domain.towingcar.entity.TowingCar;
import com.project.domain.towingcar.repository.DrivingLogRepository;
import com.project.domain.towingcar.repository.TowingCarRepository;

import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Transactional
public class TowingCarDBAdaptor {
    private final TowingCarRepository towingCarRepository;
    private final DrivingLogRepository drivingLogRepository;

    public boolean isCarAvailableById(Long carId) {
        return towingCarRepository.findById(carId)
                .map(car -> (boolean) car.getCarStatus().equals(CarStatus.IDLE))
                .orElse(false);
    }

    public boolean isCarAvailableByCode(String carCode) {
        return towingCarRepository.findByCode(carCode)
                .map(car -> (boolean) car.getCarStatus().equals(CarStatus.IDLE))
                .orElse(false);
    }

    public TowingCar getCarByCode(String carCode) {
        return towingCarRepository.findByCode(carCode)
                .orElseThrow(() -> new IllegalArgumentException("차량 코드를 찾을 수 없습니다: " + carCode));
    }

    public TowingCar getCarById(Long carId) {
        return towingCarRepository.findById(carId)
                .orElseThrow(() -> new IllegalArgumentException("차량 ID를 찾을 수 없습니다: " + carId));
    }

    // 2. 가용 차량(IDLE) 찾기 (배터리 많은 순)
    public TowingCar findFirstByCarStatusOrderByBatteryDesc(CarStatus idle) {
        return towingCarRepository.findFirstByCarStatusOrderByBatteryDesc(idle)
                .orElseThrow(() -> new IllegalStateException("현재 가용한 토잉카가 없습니다."));
    }

    public List<DrivingLog> findAll() {
        return drivingLogRepository.findAll();
    }

    public void save(TowingCar car) {
        towingCarRepository.save(car);
    }

    public TowingCar update(TowingCar car) {
        return towingCarRepository.save(car);
    }

    public DrivingLog updateDrivingLog(DrivingLog log) {
        return drivingLogRepository.save(log);
    }

    public List<DrivingLog> findAllDrivingLogsByCarId(Long carId) {
        return drivingLogRepository.findAllByTowingCarIdOrderByCreatedAtDesc(carId);
    }

    public List<TowingCar> findAllCars() {
        return towingCarRepository.findAll();
    }
}
