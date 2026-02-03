package com.project.global.auth.guard;

import com.project.domain.common.UserRole;
import org.springframework.security.core.Authentication;

final class GuardSupport {
    private GuardSupport() {}

    static boolean hasRole(Authentication authentication, UserRole role) {
        if (authentication == null || !authentication.isAuthenticated()) return false;

        String roleName = role.name();
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> roleName.equals(authority.getAuthority())
                        || ("ROLE_" + roleName).equals(authority.getAuthority()));
    }
}
