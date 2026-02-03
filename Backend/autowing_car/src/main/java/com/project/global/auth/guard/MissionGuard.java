package com.project.global.auth.guard;

import com.project.domain.common.UserRole;
import com.project.domain.flight.entity.Flight;
import com.project.domain.flight.service.FlightDBAdaptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component("missionGuard")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionGuard {

    private final FlightDBAdaptor flightDBAdaptor;

    /**
     * [미션 요청 권한 검증]
     * 1. 사용자가 PILOT 권한을 가지고 있는가?
     * 2. 요청한 Flight의 담당 기장이 본인인가?
     */
    public boolean checkRequestOwnership(Authentication authentication, Long flightId) {
        // 1. PILOT 권한 체크
        if (!GuardSupport.hasRole(authentication, UserRole.PILOT)) {
            log.warn("🚨 [Security Block] PILOT 권한 없음: {}", authentication.getName());
            return false;
        }

        if (flightId == null)
            return false;

        // 2. Flight 소유권 체크
        Flight flight = flightDBAdaptor.getFlightById(flightId);
        if (flight == null || flight.getPilot() == null) {
            log.warn("🚨 [Security] 유효하지 않은 항공편 접근: ID={}", flightId);
            return false;
        }

        String requestPilotId = authentication.getName();
        String assignedPilotId = flight.getPilot().getUsername();

        if (!assignedPilotId.equals(requestPilotId)) {
            log.warn("🚨 [Security Block] 내 항공편 아님! Req={}, Owner={}", requestPilotId, assignedPilotId);
            return false;
        }

        return true;
    }

    /**
     * [미션 승인 권한 검증]
     * 1. 사용자가 ATC(관제사) 권한을 가지고 있는가?
     */
    public boolean checkApprovePermission(Authentication authentication) {
        if (!GuardSupport.hasRole(authentication, UserRole.ATC)) {
            log.warn("🚨 [Security Block] ATC(관제사) 권한 없음: {}", authentication.getName());
            return false;
        }
        return true;
    }

}
