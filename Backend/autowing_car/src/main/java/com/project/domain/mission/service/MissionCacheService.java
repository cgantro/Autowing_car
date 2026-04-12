package com.project.domain.mission.service;

import com.project.domain.mission.dto.MissionWebSocketDtos.MissionResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class MissionCacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String MISSION_KEY_PREFIX = "mission:latest:";
    private static final Duration CACHE_TTL = Duration.ofDays(1);

    public void saveMission(MissionResponseDto missionDto) {
        String key = missionKey(missionDto.getMissionId());
        try {
            redisTemplate.opsForValue().set(key, missionDto, CACHE_TTL);
            log.debug("💾 [Redis] Saved Mission: {}", missionDto.getMissionId());
        } catch (Exception e) {
            log.error("❌ [Redis] Failed to save mission {}: {}", missionDto.getMissionId(), e.getMessage());
        }
    }

    public MissionResponseDto getMission(Long missionId) {
        try {
            Object data = redisTemplate.opsForValue().get(missionKey(missionId));
            if (data instanceof MissionResponseDto missionResponseDto) {
                return missionResponseDto;
            }
        } catch (Exception e) {
            log.error("❌ [Redis] Failed to get mission {}: {}", missionId, e.getMessage());
        }
        return null;
    }

    public void deleteMission(Long missionId) {
        redisTemplate.delete(missionKey(missionId));
    }

    private String missionKey(Long missionId) {
        return MISSION_KEY_PREFIX + missionId;
    }
}
