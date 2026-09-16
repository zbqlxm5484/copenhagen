package com.copenhagen.merchantmock.paymenthandler;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 멱등 키 형식(DESIGN.md 8.2절)과 본문 순서. */
class PaymentRequestTest {

    @Test
    @DisplayName("멱등 키는 merchant-payment-{credentialId} 다")
    void idempotencyKey_anyRequest_isDerivedFromCredentialId() {
        // given
        PaymentRequest request = new PaymentRequest("merchant-a", "checkout-1", "sha256:abc", 92000L, "KRW",
                "3f2b6a8e-1c2d-4e5f-8a9b-0c1d2e3f4a5b");

        // when & then
        assertEquals("merchant-payment-3f2b6a8e-1c2d-4e5f-8a9b-0c1d2e3f4a5b", request.idempotencyKey());
    }

    @Test
    @DisplayName("본문 키 순서는 Pay 컨트롤러의 record 순서와 같다")
    void toBody_anyRequest_keepsPayFieldOrder() {
        // given
        PaymentRequest request = new PaymentRequest("merchant-a", "checkout-1", "sha256:abc", 0L, "KRW", "cred-1");

        // when & then
        assertEquals(List.of("merchantId", "businessCheckoutId", "checkoutHash", "amountMinor", "currency",
                "credentialId"), List.copyOf(request.toBody().keySet()));
    }

    @Test
    @DisplayName("빈 필드나 음수 금액은 만들 때 거절한다")
    void constructor_invalidValues_throws() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentRequest("merchant-a", "checkout-1", "", 92000L, "KRW", "cred-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentRequest("merchant-a", "checkout-1", "sha256:abc", 92000L, "KRW", null));
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentRequest("merchant-a", "checkout-1", "sha256:abc", -1L, "KRW", "cred-1"));
    }
}
