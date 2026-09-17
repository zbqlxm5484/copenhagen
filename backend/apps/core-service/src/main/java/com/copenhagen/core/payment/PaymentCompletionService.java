package com.copenhagen.core.payment;

import java.time.Clock;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.checkout.MerchantCheckoutClient;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.CompleteResult;
import com.copenhagen.core.checkout.MerchantCheckoutRepository.AwaitingApprovalCheckout;

/**
 * Merchant Complete Checkout 호출과 결과 반영(이슈 410, technical-flow.md §7). 승인이 확인된 뒤
 * {@link PaymentApprovalPollingWorker}가 한 행에 대해 한 번 부른다.
 */
@Service
public class PaymentCompletionService {

    private static final String IDEMPOTENCY_KEY_PREFIX = "checkout-complete-";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_READY_FOR_COMPLETE = "ready_for_complete";

    private static final Logger log = LoggerFactory.getLogger(PaymentCompletionService.class);

    private final CartMerchants cartMerchants;
    private final Map<String, MerchantCheckoutClient> merchantCheckoutClients;
    private final PaymentCompletionRepository repository;
    private final Clock clock;

    public PaymentCompletionService(
            CartMerchants cartMerchants,
            Map<String, MerchantCheckoutClient> merchantCheckoutClients,
            PaymentCompletionRepository repository,
            Clock coreClock) {
        this.cartMerchants = cartMerchants;
        this.merchantCheckoutClients = merchantCheckoutClients;
        this.repository = repository;
        this.clock = coreClock;
    }

    public Outcome complete(AwaitingApprovalCheckout row, String credentialId) {
        String merchantId = cartMerchants.merchantId(row.merchantRefId());
        MerchantCheckoutClient client = merchantCheckoutClients.get(merchantId);
        if (client == null) {
            log.warn("Complete Checkout 대상 merchant 클라이언트 없음: merchantId={}, checkoutId={}",
                    merchantId, row.merchantCheckoutId());
            return Outcome.IN_PROGRESS;
        }

        CompleteResult result = client.complete(
                row.merchantCheckoutId(),
                credentialId,
                IDEMPOTENCY_KEY_PREFIX + row.merchantCheckoutId());

        if (STATUS_COMPLETED.equals(result.status())) {
            repository.recordCompleted(
                    row.checkoutSnapshotId(),
                    row.userId(),
                    row.paymentApprovalId(),
                    result.order().id(),
                    result.payment().paymentId(),
                    row.totalAmountMinor(),
                    row.currency(),
                    clock.instant());
            return Outcome.COMPLETED;
        }
        if (STATUS_READY_FOR_COMPLETE.equals(result.status())) {
            repository.recordFailed(
                    row.checkoutSnapshotId(),
                    row.paymentApprovalId(),
                    result.payment() == null ? null : result.payment().paymentId(),
                    row.totalAmountMinor(),
                    row.currency(),
                    result.payment() == null ? null : result.payment().errorCode(),
                    clock.instant());
            return Outcome.FAILED;
        }
        // complete_in_progress: Merchant가 PG 응답을 아직 못 받았다. merchant_checkouts는 COMPLETING에
        // 남기고 이번 tick에서는 아무것도 쓰지 않는다 — 최종 확정은 순서30 Order Webhook 후속 구현의 몫이다.
        return Outcome.IN_PROGRESS;
    }

    public enum Outcome {
        COMPLETED,
        FAILED,
        IN_PROGRESS
    }
}
