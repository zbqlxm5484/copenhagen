package com.copenhagen.merchantmock.security;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 서명 대상 범위 테스트(DESIGN.md 6.2절).
 *
 * <p>범위 자체는 Core 와 미확정이다(11절). 뒤집히면 이 파일의 기대값이 바뀐다.
 */
class CheckoutSigningScopeTest {

    @Test
    @DisplayName("최상위 com.* 확장 키를 뺀다")
    void withoutExtensions_topLevelExtension_isRemoved() {
        // given
        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("id", "checkout-1");
        checkout.put("status", "ready_for_complete");
        checkout.put("com.copenhagen.payment.ap2_like", Map.of("merchant_authorization", "eyJ"));

        // when
        Map<String, Object> scoped = CheckoutSigningScope.withoutExtensions(checkout);

        // then
        assertEquals(2, scoped.size());
        assertTrue(scoped.containsKey("id"));
        assertTrue(scoped.containsKey("status"));
        assertFalse(scoped.containsKey("com.copenhagen.payment.ap2_like"));
    }

    @Test
    @DisplayName("중첩된 객체 안의 com.* 는 건드리지 않는다")
    void withoutExtensions_nestedExtension_isKept() {
        // given
        // 최상위만 빼는 이유는 순환 때문이다. 내 서명이 들어가는 자리가 최상위 com.* 하나다.
        // 중첩된 것은 남이 넣은 값이고 서명 대상에 들어가야 한다.
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("com.other.vendor.hint", "keep-me");
        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("id", "checkout-1");
        checkout.put("payment", nested);

        // when
        Map<String, Object> scoped = CheckoutSigningScope.withoutExtensions(checkout);

        // then
        @SuppressWarnings("unchecked")
        Map<String, Object> keptNested = (Map<String, Object>) scoped.get("payment");
        assertTrue(keptNested.containsKey("com.other.vendor.hint"));
    }

    @Test
    @DisplayName("확장 키가 없으면 그대로 둔다")
    void withoutExtensions_noExtension_returnsSameEntries() {
        // given
        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("id", "checkout-1");
        checkout.put("currency", "KRW");

        // when
        Map<String, Object> scoped = CheckoutSigningScope.withoutExtensions(checkout);

        // then
        assertEquals(checkout, scoped);
    }

    @Test
    @DisplayName("원본을 바꾸지 않는다")
    void withoutExtensions_anyInput_doesNotMutateSource() {
        // given
        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("id", "checkout-1");
        checkout.put("com.copenhagen.payment.ap2_like", Map.of());

        // when
        CheckoutSigningScope.withoutExtensions(checkout);

        // then
        assertEquals(2, checkout.size());
        assertTrue(checkout.containsKey("com.copenhagen.payment.ap2_like"));
    }

    @Test
    @DisplayName("com 으로 시작하되 확장 접두어가 아닌 키는 남긴다")
    void withoutExtensions_keyStartingWithComButNotPrefix_isKept() {
        // given
        // completed_at 은 com 으로 시작하지만 확장 키가 아니다. 판정은 점까지 본다.
        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("completed_at", "2026-09-15T17:00:00+09:00");
        checkout.put("comment", "no");

        // when
        Map<String, Object> scoped = CheckoutSigningScope.withoutExtensions(checkout);

        // then
        assertEquals(2, scoped.size());
    }

    @Test
    @DisplayName("null 은 거절한다")
    void withoutExtensions_null_throws() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> CheckoutSigningScope.withoutExtensions(null));
    }
}
