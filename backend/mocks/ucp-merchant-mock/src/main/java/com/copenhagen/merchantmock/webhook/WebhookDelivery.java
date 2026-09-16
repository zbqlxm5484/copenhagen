package com.copenhagen.merchantmock.webhook;

import java.time.OffsetDateTime;

/**
 * webhook_deliveries 한 행(DESIGN.md 5.4절). 등기우편 발송 대장이다.
 *
 * <p>{@code webhookId} 와 {@code payloadJson} 은 처음 만든 뒤 바뀌지 않는다(불변 조건 8번). Core 가 그 둘로
 * 중복을 판별한다. 서명 셋은 마지막 발송 기록이고 발송마다 새로 만든다(불변 조건 7번).
 */
public record WebhookDelivery(
        String webhookId,
        String orderId,
        WebhookEventType eventType,
        String payloadJson,
        Signed signed,
        WebhookStatus status,
        int attemptCount,
        OffsetDateTime nextAttemptAt,
        Integer responseStatus,
        OffsetDateTime createdAt) {

    /** 마지막 발송에 붙인 서명 셋. 아직 한 번도 안 보냈으면 null 이다. */
    public record Signed(String signatureInput, String signature, String contentDigest) {
    }

    /** 주문 트랜잭션 안에서 만드는 새 행. 첫 시도는 커밋 직후이므로 {@code nextAttemptAt} 은 지금이다. */
    public static WebhookDelivery pending(String webhookId, String orderId, WebhookEventType eventType,
            String payloadJson, OffsetDateTime now) {
        return new WebhookDelivery(webhookId, orderId, eventType, payloadJson, null, WebhookStatus.PENDING,
                0, now, null, now);
    }
}
