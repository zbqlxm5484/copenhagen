package com.copenhagen.merchantmock.checkout;

import java.time.OffsetDateTime;

/**
 * checkout_sessions 한 행 중 172 가 다루는 컬럼(DESIGN.md 5.4절).
 *
 * <p>lineSkusJson 은 선택된 옵션이다. canonical_checkout 에 자리가 없어 따로 남긴다(180).
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
        String lineSkusJson,
        String canonicalCheckout,
        OffsetDateTime expiresAt,
        long version) {

    CheckoutSession withVersion(long newVersion) {
        return new CheckoutSession(id, status, currency, totalAmount, fulfillmentAddressJson,
                messagesJson, lineSkusJson, canonicalCheckout, expiresAt, newVersion);
    }

    /** 상태 전이. canonicalCheckout 은 그대로 둔다(DESIGN.md 7.1절). */
    CheckoutSession withStatus(CheckoutStatus newStatus) {
        return new CheckoutSession(id, newStatus, currency, totalAmount, fulfillmentAddressJson,
                messagesJson, lineSkusJson, canonicalCheckout, expiresAt, version + 1);
    }
}
