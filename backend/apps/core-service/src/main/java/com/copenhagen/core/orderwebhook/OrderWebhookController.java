package com.copenhagen.core.orderwebhook;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform Profile이 광고하는 Order Webhook 수신 경로.
 *
 * <p>주문 상태 projection은 후속 Order 도메인이 소유한다. 이 경계는 경로 계약을 먼저 고정하고,
 * Merchant가 동기 재시도를 불필요하게 만들도록 수신 성공만 응답한다.
 */
@RestController
public class OrderWebhookController {

    @PostMapping(path = "/webhooks/v1/ucp/orders")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void receive(@RequestBody String payload) {
        // 후속 Order lifecycle 구현에서 Webhook-Id 멱등성과 Merchant JWK 서명 검증·상태 반영을 연결한다.
    }
}
