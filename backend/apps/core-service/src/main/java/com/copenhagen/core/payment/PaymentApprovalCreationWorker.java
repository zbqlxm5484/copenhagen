package com.copenhagen.core.payment;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Checkout;
import com.copenhagen.core.checkout.MerchantCheckoutRepository;
import com.copenhagen.core.checkout.MerchantCheckoutRepository.CreatingApprovalCheckout;
import com.copenhagen.core.payment.PaymentApprovalMessages.ApprovalCreatedResponse;
import com.copenhagen.core.payment.PaymentApprovalMessages.CreateApprovalRequest;
import com.copenhagen.core.product.MerchantsProperties;
import tools.jackson.databind.ObjectMapper;

/** 저장된 승인 요청을 재전송한다. HTTP 응답 유실도 Pay의 같은 멱등키로 복구한다. */
@Component
@ConditionalOnProperty(prefix = "core.payment.worker", name = "enabled", havingValue = "true")
public class PaymentApprovalCreationWorker {

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovalCreationWorker.class);

    private final MerchantCheckoutRepository repository;
    private final PaymentApprovalClient client;
    private final CartMerchants merchants;
    private final MerchantsProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PaymentApprovalCreationWorker(MerchantCheckoutRepository repository, PaymentApprovalClient client,
            CartMerchants merchants, MerchantsProperties properties, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.client = client;
        this.merchants = merchants;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${core.payment.worker.poll-interval-seconds:3}000",
            initialDelayString = "${core.payment.worker.poll-interval-seconds:3}000")
    public void retryPending() {
        for (CreatingApprovalCheckout row : repository.findCreatingApprovals(clock.instant())) {
            try {
                retry(row);
            } catch (RuntimeException exception) {
                // 요청 본문에는 Merchant Authorization이 있으므로 예외 원문이나 본문은 남기지 않는다.
                log.warn("payment.approval.retry.failed checkoutId={}, error={}",
                        row.id(), exception.getClass().getSimpleName());
            }
        }
    }

    private void retry(CreatingApprovalCheckout row) {
        Instant now = clock.instant();
        if (!repository.claimApprovalCreation(row.id(), now)) {
            return;
        }
        String requestJson = row.approvalRequest();
        if (requestJson == null) {
            String merchantId = merchants.merchantId(row.merchantRefId());
            String name = properties.merchants().stream().filter(config -> config.id().equals(merchantId))
                    .map(MerchantsProperties.MerchantConfig::displayName).findFirst().orElse(merchantId);
            CreateApprovalRequest reconstructed = PaymentApprovalRequests.create(row.purchaseRequestId(),
                    row.userId(), merchantId, name, row.checkoutDigest(),
                    mapper.readValue(row.checkoutPayload(), Checkout.class));
            requestJson = repository.saveApprovalRequestIfAbsent(row.id(), mapper.writeValueAsString(reconstructed));
        }
        CreateApprovalRequest request = mapper.readValue(requestJson, CreateApprovalRequest.class);
        try {
            // 만료 뒤에도 최초 응답이 유실된 승인 세션은 같은 키로 먼저 복구한다.
            ApprovalCreatedResponse response = client.create(request,
                    PaymentApprovalRequests.idempotencyKey(request.purchaseRequestId(), request.merchantId()));
            repository.markAwaitingApproval(row.id(), response.approvalId(), response.expiresAt(), clock.instant());
        } catch (com.copenhagen.core.common.error.CoreException exception) {
            if (row.expiresAt() != null && !now.isBefore(row.expiresAt())
                    && exception.getCause() instanceof org.springframework.web.client.HttpClientErrorException http
                    && (http.getStatusCode().value() == 400 || http.getStatusCode().value() == 422)) {
                repository.markExpired(row.id(), now);
                return;
            }
            throw exception;
        }
    }
}
