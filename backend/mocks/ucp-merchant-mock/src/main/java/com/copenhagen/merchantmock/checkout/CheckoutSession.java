package com.copenhagen.merchantmock.checkout;

import java.time.OffsetDateTime;

import com.copenhagen.contract.internalapi.PaymentStatus;

/**
 * checkout_sessions 한 행 중 서비스가 읽고 쓰는 컬럼(DESIGN.md 5.4절).
 *
 * <p>스냅샷(canonicalCheckout)이 체크아웃 항목과 금액의 원본이고 나머지는 조회와 스캔을 위한 캐시다.
 * lineSkusJson 은 선택된 옵션이다. canonical_checkout 에 자리가 없어 따로 남긴다(180).
 *
 * <p>서명(merchantAuthorizationJws)은 발급값 그대로다(불변 조건 6번). 완료 응답(completionResponseJson)은
 * 같은 키 재요청에 그대로 재생된다(8.1절). 결제 둘은 조회 응답에 {@code payment} 를 얹는 데 쓰고, 갱신은
 * 이 record 가 아니라 조건부 UPDATE 메서드가 한다(7.2절). 재조회 컬럼 셋은 {@link PaymentRecheck} 가 따로 든다.
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
        String merchantAuthorizationJws,
        OffsetDateTime expiresAt,
        String completionResponseJson,
        PaymentStatus paymentStatus,
        String payPaymentId,
        long version) {

    /** 172 컬럼 열 개 생성자. 서명·완료·결제 컬럼은 비운다. 테스트와 판정 직후 조립이 쓴다. */
    public CheckoutSession(String id, CheckoutStatus status, String currency, Long totalAmount,
            String fulfillmentAddressJson, String messagesJson, String lineSkusJson, String canonicalCheckout,
            OffsetDateTime expiresAt, long version) {
        this(id, status, currency, totalAmount, fulfillmentAddressJson, messagesJson, lineSkusJson,
                canonicalCheckout, null, expiresAt, null, null, null, version);
    }

    CheckoutSession withVersion(long newVersion) {
        return new CheckoutSession(id, status, currency, totalAmount, fulfillmentAddressJson,
                messagesJson, lineSkusJson, canonicalCheckout, merchantAuthorizationJws, expiresAt,
                completionResponseJson, paymentStatus, payPaymentId, newVersion);
    }

    /** 상태 전이. canonicalCheckout 은 그대로 둔다(DESIGN.md 7.1절). */
    CheckoutSession withStatus(CheckoutStatus newStatus) {
        return new CheckoutSession(id, newStatus, currency, totalAmount, fulfillmentAddressJson,
                messagesJson, lineSkusJson, canonicalCheckout, merchantAuthorizationJws, expiresAt,
                completionResponseJson, paymentStatus, payPaymentId, version + 1);
    }

    CheckoutSession withMerchantAuthorizationJws(String jws) {
        return new CheckoutSession(id, status, currency, totalAmount, fulfillmentAddressJson,
                messagesJson, lineSkusJson, canonicalCheckout, jws, expiresAt,
                completionResponseJson, paymentStatus, payPaymentId, version);
    }

    /** 인메모리 저장소가 결제 컬럼을 흉내 낼 때 쓴다. */
    CheckoutSession withPayment(PaymentStatus newPaymentStatus, String newPayPaymentId) {
        return new CheckoutSession(id, status, currency, totalAmount, fulfillmentAddressJson,
                messagesJson, lineSkusJson, canonicalCheckout, merchantAuthorizationJws, expiresAt,
                completionResponseJson, newPaymentStatus, newPayPaymentId, version);
    }

    CheckoutSession withCompletionResponse(String json) {
        return new CheckoutSession(id, status, currency, totalAmount, fulfillmentAddressJson,
                messagesJson, lineSkusJson, canonicalCheckout, merchantAuthorizationJws, expiresAt,
                json, paymentStatus, payPaymentId, version);
    }
}
