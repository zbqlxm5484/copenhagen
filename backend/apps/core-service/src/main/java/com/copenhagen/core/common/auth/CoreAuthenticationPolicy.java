package com.copenhagen.core.common.auth;

/**
 * 보호 대상 라우트를 정의한다. {@code /auth/signup}·{@code /auth/login}·{@code /auth/token/refresh}·
 * {@code /auth/logout}은 공개 API다. {@code GET /api/core/v1/users/me}(순서 10)부터 보호 대상이 생긴다.
 */
public final class CoreAuthenticationPolicy {

    private static final String USERS_ME_PATH = "/api/core/v1/users/me";

    private CoreAuthenticationPolicy() {
    }

    public static boolean requiresAuthentication(String path) {
        return USERS_ME_PATH.equals(path);
    }
}
