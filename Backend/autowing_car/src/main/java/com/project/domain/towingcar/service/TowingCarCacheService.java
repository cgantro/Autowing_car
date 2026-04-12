package com.project.domain.towingcar.service;

import com.project.domain.towingcar.dto.TowingCarStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TowingCarCacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CAR_KEY_PREFIX = "towing_car:latest:";
    private static final String CAR_INDEX_KEY = "towing_car:index";
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    public void saveCarStatus(String carCode, TowingCarStatusResponse statusResponse) {
        String key = carStateKey(carCode);
        try {
            redisTemplate.opsForValue().set(key, statusResponse, CACHE_TTL);
            redisTemplate.opsForSet().add(CAR_INDEX_KEY, carCode);
            log.debug("💾 [Redis] Saved Car Status: {}", carCode);
        } catch (Exception e) {
            log.error("❌ [Redis] Failed to save car status for {}: {}", carCode, e.getMessage());
        }
    }

    public TowingCarStatusResponse getCarStatus(String carCode) {
        try {
            Object data = redisTemplate.opsForValue().get(carStateKey(carCode));
            if (data instanceof TowingCarStatusResponse statusResponse) {
                return statusResponse;
            }
        } catch (Exception e) {
            log.error("❌ [Redis] Failed to get car status for {}: {}", carCode, e.getMessage());
        }
        return null;
    }

    public List<TowingCarStatusResponse> getAllCars() {
        List<TowingCarStatusResponse> list = new ArrayList<>();
        try {
            Set<Object> members = redisTemplate.opsForSet().members(CAR_INDEX_KEY);
            if (members == null || members.isEmpty()) {
                return list;
            }

            List<String> keys = members.stream().map(member -> carStateKey(String.valueOf(member))).toList();
            List<Object> values = redisTemplate.opsForValue().multiGet(keys);
            if (values != null) {
                for (Object val : values) {
                    if (val instanceof TowingCarStatusResponse statusResponse) {
                        list.add(statusResponse);
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ [Redis] Failed to get all cars: {}", e.getMessage());
        }
        return list;
    }

    public void deleteCarStatus(String carCode) {
        redisTemplate.delete(carStateKey(carCode));
        redisTemplate.opsForSet().remove(CAR_INDEX_KEY, carCode);
    }

    private String carStateKey(String carCode) {
        return CAR_KEY_PREFIX + carCode;
    }
}
