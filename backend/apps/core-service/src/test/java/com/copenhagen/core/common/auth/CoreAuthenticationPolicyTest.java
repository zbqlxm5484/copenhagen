package com.copenhagen.core.common.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreAuthenticationPolicyTest {

    @Test
    @DisplayName("/users/me만 인증이 필요하다")
    void requiresAuthentication_usersMe_true() {
        assertTrue(CoreAuthenticationPolicy.requiresAuthentication("/api/core/v1/users/me"));
    }

    @Test
    @DisplayName("그 외 기존 공개 라우트는 인증이 필요 없다")
    void requiresAuthentication_publicRoutes_false() {
        assertFalse(CoreAuthenticationPolicy.requiresAuthentication("/api/core/v1/auth/signup"));
        assertFalse(CoreAuthenticationPolicy.requiresAuthentication("/api/core/v1/auth/login"));
        assertFalse(CoreAuthenticationPolicy.requiresAuthentication("/api/core/v1/auth/token/refresh"));
        assertFalse(CoreAuthenticationPolicy.requiresAuthentication("/api/core/v1/auth/logout"));
    }
}
