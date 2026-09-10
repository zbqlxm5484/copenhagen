package com.copenhagen.merchantmock.checkout;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 멱등 키에서 체크아웃 id 를 만드는 규칙(DESIGN.md 8.1절). */
class CheckoutIdsTest {

    @Test
    void 같은_키면_같은_id_다() {
        assertEquals(
                CheckoutIds.fromIdempotencyKey("checkout-create-abc"),
                CheckoutIds.fromIdempotencyKey("checkout-create-abc"));
        assertEquals(
                CheckoutIds.fromIdempotencyKey("checkout-create-abc"),
                CheckoutIds.fromIdempotencyKey("  checkout-create-abc  "), "앞뒤 공백은 무시한다");
    }

    @Test
    void 다른_키면_다른_id_다() {
        assertNotEquals(
                CheckoutIds.fromIdempotencyKey("checkout-create-abc"),
                CheckoutIds.fromIdempotencyKey("checkout-create-abd"));
    }

    @Test
    void 키가_없으면_매번_다른_랜덤_id_다() {
        assertNotEquals(CheckoutIds.fromIdempotencyKey(null), CheckoutIds.fromIdempotencyKey(null));
        assertNotEquals(CheckoutIds.fromIdempotencyKey(""), CheckoutIds.fromIdempotencyKey(" "));
    }

    @Test
    void UUIDv5_형식이다() {
        UUID id = UUID.fromString(CheckoutIds.fromIdempotencyKey("checkout-create-abc"));

        assertEquals(5, id.version());
        assertEquals(2, id.variant(), "RFC 4122 variant");
    }

    @Test
    void RFC_4122_부록_벡터와_맞는다() {
        // RFC 4122 의 DNS 네임스페이스로 "www.example.com" 을 v5 하면 이 값이다. 공식 벡터라 구현 회귀를 잡는다.
        UUID dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

        assertEquals("2ed6657d-e927-568b-95e1-2665a8aea6a2", CheckoutIds.v5(dns, "www.example.com").toString());
    }
}
