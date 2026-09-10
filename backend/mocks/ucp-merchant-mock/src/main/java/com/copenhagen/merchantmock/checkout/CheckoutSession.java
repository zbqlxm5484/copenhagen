package com.copenhagen.merchantmock.checkout;

import java.time.OffsetDateTime;

/**
 * checkout_sessions 한 행 중 172 가 다루는 컬럼(DESIGN.md 5.4절).
 *
 * <p>서명·멱등·결제 컬럼은 175·176·179 가 붙일 때 늘린다. 스냅샷(canonicalCheckout)이 체크아웃 항목과
 * 금액의 원본이고 나머지는 조회와 스캔을 위한 캐시다.
 */
public record CheckoutSession(
        String id,
        CheckoutStatus status,
        String currency,
        Long totalAmount,
        String fulfillmentAddressJson,
        String messagesJson,
        String canonicalCheckout,
        OffsetDateTime expiresAt,
        long version) {

    CheckoutSession withVersion(long newVersion) {
        return new CheckoutSession(id, status, currency, totalAmount, fulfillmentAddressJson,
                messagesJson, canonicalCheckout, expiresAt, newVersion);
    }
}
