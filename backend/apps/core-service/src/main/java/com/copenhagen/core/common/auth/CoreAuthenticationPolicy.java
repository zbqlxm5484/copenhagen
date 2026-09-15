package com.copenhagen.core.common.auth;

/**
 * 보호 대상 라우트를 정의한다. 이번 단계(순서 5)에는 보호 대상이 없다 — {@code /auth/signup}·{@code /auth/login}
 * 모두 공개 API다. {@code GET /api/core/v1/users/me}(순서 10)부터 여기에 경로가 추가된다.
 */
public final class CoreAuthenticationPolicy {

    private CoreAuthenticationPolicy() {
    }

    public static boolean requiresAuthentication(String path) {
        return false;
    }
}
