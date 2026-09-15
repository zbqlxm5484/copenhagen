package com.copenhagen.merchantmock.order;

import java.time.OffsetDateTime;

/**
 * orders 한 행(DESIGN.md 5.4절).
 *
 * <p>배송 다섯은 주문 시점 사본이다. 체크아웃의 {@code fulfillment_address} 를 조인해 읽지 않는다.
 * 같은 사람이라도 주문마다 수령인과 주소가 다를 수 있다.
 *
 * <p>{@code recipientPhone} 은 당분간 비어 있다. Core 가 보내는 것이 수령인·우편번호·주소·상세주소
 * 넷뿐이라 채울 값이 없다. 필드 추가는 11절 Core 합의 항목이다.
 */
public record Order(
        String id,
        String checkoutSessionId,
        OrderStatus status,
        long totalAmount,
        String currency,
        String rawOrderJson,
        String recipientName,
        String recipientPhone,
        String postalCode,
        String addressLine1,
        String addressLine2,
        long version,
        OffsetDateTime updatedAt) {
}
