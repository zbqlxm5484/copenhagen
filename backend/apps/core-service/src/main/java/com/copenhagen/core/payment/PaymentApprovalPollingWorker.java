package com.copenhagen.core.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.copenhagen.contract.internalapi.PaymentApprovalStatusResponse;
import com.copenhagen.core.checkout.MerchantCheckoutRepository;
import com.copenhagen.core.checkout.MerchantCheckoutRepository.AwaitingApprovalCheckout;

/**
 * Pay 승인 세션 폴링(이슈 410). Front·Pay 양쪽 다 Core에 알리는 새 호출이 없어도 되도록, Core가 직접
 * 승인 상태를 확인해 확정 즉시 Merchant Complete Checkout으로 이어간다.
 *
 * <p>기본은 비활성화다({@code core.payment.worker.enabled=false}) — Pay·Merchant Mock이 없는 환경(단위
 * 테스트 등)에서 스케줄이 조용히 실패 로그를 쌓지 않게 한다. 배포 환경에서만 켠다.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(
        prefix = "core.payment.worker",
        name = "enabled",
        havingValue = "true")
public class PaymentApprovalPollingWorker {

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovalPollingWorker.class);
    private static final String REASON_USER_REJECTED = "USER_REJECTED";

    private final MerchantCheckoutRepository merchantCheckoutRepository;
    private final PaymentApprovalClient paymentApprovalClient;
    private final PaymentCompletionService completionService;
    private final Clock clock;

    public PaymentApprovalPollingWorker(
            MerchantCheckoutRepository merchantCheckoutRepository,
            PaymentApprovalClient paymentApprovalClient,
            PaymentCompletionService completionService,
            Clock coreClock) {
        this.merchantCheckoutRepository = merchantCheckoutRepository;
        this.paymentApprovalClient = paymentApprovalClient;
        this.completionService = completionService;
        this.clock = coreClock;
    }

    @Scheduled(
            fixedDelayString = "${core.payment.worker.poll-interval-seconds:3}000",
            initialDelayString = "${core.payment.worker.poll-interval-seconds:3}000")
    public void poll() {
        List<AwaitingApprovalCheckout> rows = merchantCheckoutRepository.findAwaitingApproval();
        for (AwaitingApprovalCheckout row : rows) {
            try {
                pollOne(row);
            } catch (RuntimeException exception) {
                log.warn("payment.approval.poll.failed checkoutId={}, approvalId={}",
                        row.id(), row.paymentApprovalId(), exception);
            }
        }
    }

    private void pollOne(AwaitingApprovalCheckout row) {
        Instant now = clock.instant();
        if (row.paymentApprovalExpiresAt().isBefore(now)) {
            merchantCheckoutRepository.markExpired(row.id(), now);
            return;
        }

        PaymentApprovalStatusResponse status = paymentApprovalClient.status(row.paymentApprovalId());
        switch (status.approvalStatus()) {
            case APPROVED -> handleApproved(row, status);
            case REJECTED -> merchantCheckoutRepository.markCancelled(row.id(), REASON_USER_REJECTED, now);
            case EXPIRED, INVALIDATED -> merchantCheckoutRepository.markExpired(row.id(), now);
            default -> {
                // PAYMENT_METHOD_REQUIRED/PENDING/COMPLETED: 아직 승인 대기 중이거나(다음 tick 재시도),
                // 이미 다른 경로(재시도 등)로 COMPLETED가 됐다 — 여기서는 손대지 않는다.
            }
        }
    }

    private void handleApproved(AwaitingApprovalCheckout row, PaymentApprovalStatusResponse status) {
        String credentialId = status.credentialId();
        if (credentialId == null || credentialId.isBlank()) {
            // 승인은 됐지만 Payment Credential이 아직 발급 전이다 — 다음 tick에 다시 확인한다.
            return;
        }
        if (!merchantCheckoutRepository.beginCompleting(row.id(), clock.instant())) {
            // 다른 워커 인스턴스가 먼저 집었다.
            return;
        }

        PaymentCompletionService.Outcome outcome = completionService.complete(row, credentialId);
        Instant now = clock.instant();
        switch (outcome) {
            case COMPLETED -> merchantCheckoutRepository.markCompleted(row.id(), now);
            case FAILED -> merchantCheckoutRepository.markFailed(row.id(), "PAYMENT_FAILED", now);
            case IN_PROGRESS -> {
                // COMPLETING에 그대로 둔다. 최종 확정은 이번 작업 범위 밖(순서30 Order Webhook 후속)이다.
            }
        }
    }
}
