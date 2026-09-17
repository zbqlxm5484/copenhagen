package com.copenhagen.core.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.copenhagen.contract.internalapi.PaymentApprovalStatusResponse;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.payment.PaymentApprovalMessages.ApprovalCreatedResponse;
import com.copenhagen.core.payment.PaymentApprovalMessages.CreateApprovalRequest;

/**
 * Pay 내부 API 호출(이슈 410). 승인 세션 생성은 Core→Pay 인증(Idempotency-Key)만 있으면 되고, 상태 조회는
 * {@code core-service} 서비스 토큰이 필요하다 — 둘 다 {@link PaymentApprovalConfiguration}이 만든
 * {@link RestClient}가 {@code Authorization} 헤더를 이미 싣고 있다.
 */
public class PaymentApprovalClient {

    private static final String CREATE_PATH = "/internal/v1/payment-approvals";
    private static final String STATUS_PATH = "/internal/v1/payment-approvals/{approvalId}/status";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovalClient.class);

    private final RestClient restClient;

    public PaymentApprovalClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public ApprovalCreatedResponse create(CreateApprovalRequest request, String idempotencyKey) {
        try {
            return restClient.post()
                    .uri(CREATE_PATH)
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .body(request)
                    .retrieve()
                    .body(ApprovalCreatedResponse.class);
        } catch (RestClientException exception) {
            log.warn("Pay 승인 세션 생성 실패: purchaseRequestId={}, merchantId={}",
                    request.purchaseRequestId(), request.merchantId(), exception);
            throw new CoreException(
                    CoreErrorCode.PAYMENT_APPROVAL_UNAVAILABLE,
                    CoreErrorCode.PAYMENT_APPROVAL_UNAVAILABLE.defaultMessage(),
                    null,
                    CoreErrorCode.PAYMENT_APPROVAL_UNAVAILABLE.defaultNextAction(),
                    exception);
        }
    }

    /** 폴링 워커 전용. 실패는 호출자가 다음 tick 재시도로 처리하므로 그대로 던진다. */
    public PaymentApprovalStatusResponse status(String approvalId) {
        return restClient.get()
                .uri(STATUS_PATH, approvalId)
                .retrieve()
                .body(PaymentApprovalStatusResponse.class);
    }
}
