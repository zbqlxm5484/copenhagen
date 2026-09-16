package com.copenhagen.core.common.auth;

import java.util.UUID;

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

    @Test
    @DisplayName("배송지 라우트(목록/등록, 경로 변수 포함)는 모두 인증이 필요하다")
    void requiresAuthentication_addressRoutes_true() {
        UUID addressId = UUID.randomUUID();
        assertTrue(CoreAuthenticationPolicy.requiresAuthentication("/api/core/v1/addresses"));
        assertTrue(CoreAuthenticationPolicy.requiresAuthentication("/api/core/v1/addresses/" + addressId));
        assertTrue(CoreAuthenticationPolicy.requiresAuthentication(
                "/api/core/v1/addresses/" + addressId + "/default"));
    }

    @Test
    @DisplayName("배송지 경로를 prefix로만 포함하는 다른 경로는 인증 대상이 아니다")
    void requiresAuthentication_addressLikePrefix_false() {
        assertFalse(CoreAuthenticationPolicy.requiresAuthentication("/api/core/v1/addresses-history"));
    }

    @Test
    @DisplayName("상품 검색·상세조회 라우트는 모두 인증이 필요하다")
    void requiresAuthentication_productRoutes_true() {
        assertTrue(CoreAuthenticationPolicy.requiresAuthentication("/api/core/v1/products/search"));
        assertTrue(CoreAuthenticationPolicy.requiresAuthentication("/api/core/v1/products/some-product-id"));
    }

    @Test
    @DisplayName("상품 경로를 prefix로만 포함하는 다른 경로는 인증 대상이 아니다")
    void requiresAuthentication_productLikePrefix_false() {
        assertFalse(CoreAuthenticationPolicy.requiresAuthentication("/api/core/v1/products"));
        assertFalse(CoreAuthenticationPolicy.requiresAuthentication("/api/core/v1/products-history"));
    }
}
