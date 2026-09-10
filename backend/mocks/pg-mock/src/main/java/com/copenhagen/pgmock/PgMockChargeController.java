package com.copenhagen.pgmock;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pay가 호출하는 무상태 PG 시뮬레이터입니다.
 *
 * <p>실제 카드정보나 결제 원장은 보관하지 않으며, 설정된 결과만 반환합니다. TIMEOUT은
 * 응답을 지연시켜 Pay가 응답 불명으로 분류할 수 있도록 합니다.
 */
@RestController
@RequestMapping("/internal/v1/charges")
public class PgMockChargeController {

    private final PgMockProperties properties;

    public PgMockChargeController(PgMockProperties properties) {
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<ChargeResponse> charge(@RequestBody ChargeRequest request) {
        if (request == null
                || isBlank(request.paymentId())
                || isBlank(request.pgRequestId())
                || isBlank(request.paymentMethodRef())
                || request.amountMinor() < 0
                || !"KRW".equals(request.currency())) {
            return ResponseEntity.badRequest().build();
        }

        if (properties.outcome() == PgMockOutcome.TIMEOUT) {
            delay(properties.responseDelayMillis() == 0 ? 5_000 : properties.responseDelayMillis());
            return ResponseEntity.ok(new ChargeResponse("UNKNOWN", null, "TIMEOUT"));
        }
        if (properties.outcome() == PgMockOutcome.FAILED) {
            return ResponseEntity.ok(new ChargeResponse("FAILED", null, "DECLINED"));
        }
        return ResponseEntity.ok(new ChargeResponse(
                "SUCCESS",
                "pg-" + request.pgRequestId(),
                "APPROVED"));
    }

    private void delay(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ChargeRequest(
            String paymentId,
            String pgRequestId,
            String paymentMethodRef,
            long amountMinor,
            String currency) {
    }

    public record ChargeResponse(
            String status,
            String pgTransactionId,
            String responseCode) {
    }
}
