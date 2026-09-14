package com.copenhagen.pay.payment.reconciliation;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.payment.PaymentExecutionRecord;
import com.copenhagen.pay.payment.payment.PaymentLifecycleService;
import com.copenhagen.pay.payment.payment.PaymentReceiptService;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeResult.Outcome;
import com.copenhagen.pay.payment.pg.PaymentGateway.StatusResult;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationSettlementService {

    private final ReconciliationJobRepository jobRepository;
    private final ReconciliationJobService jobService;
    private final ReconciliationPaymentRepository paymentRepository;
    private final PaymentLifecycleService lifecycleService;
    private final PaymentReceiptService receiptService;
    private final Clock clock;

    public ReconciliationSettlementService(
            ReconciliationJobRepository jobRepository,
            ReconciliationJobService jobService,
            ReconciliationPaymentRepository paymentRepository,
            PaymentLifecycleService lifecycleService,
            PaymentReceiptService receiptService,
            Clock clock) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.paymentRepository = paymentRepository;
        this.lifecycleService = lifecycleService;
        this.receiptService = receiptService;
        this.clock = clock;
    }

    @Transactional
    public void settle(ReconciliationJob job, StatusResult result) {
        Instant now = clock.instant();
        if (!jobRepository.lockOwned(job, now)) {
            return;
        }
        PaymentExecutionRecord payment = paymentRepository.findByAttemptIdForUpdate(job.paymentAttemptId())
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
        if (payment.status() != PaymentStatus.UNKNOWN) {
            requireUpdated(jobService.markCancelled(job));
            return;
        }
        if (!job.paymentAttemptId().equals(payment.attemptId())
                || !"UNKNOWN".equals(payment.attemptStatus())
                || !"UNKNOWN".equals(payment.pgTransactionStatus())) {
            throw new PayException(PayErrorCode.STATE_CONFLICT);
        }

        StatusResult normalized = normalize(result, payment);
        Outcome outcome = normalized.outcome();
        String code = switch (outcome) {
            case SUCCESS -> "PG_APPROVED";
            case FAILED -> "PG_DECLINED";
            case UNKNOWN -> "PG_LOOKUP_UNCONFIRMED";
        };
        requireUpdated(paymentRepository.updateUnknownTransaction(
                payment.attemptId(), outcome.name(), normalized.pgTransactionId(), code, now));
        if (outcome == Outcome.UNKNOWN) {
            requireUpdated(jobService.markFailed(job));
            return;
        }
        requireUpdated(paymentRepository.resolveUnknownAttempt(
                payment.attemptId(), outcome.name(), outcome == Outcome.SUCCESS ? null : code, now));
        if (outcome == Outcome.SUCCESS) {
            lifecycleService.resolveSuccess(payment.id());
            receiptService.issueIfAbsent(payment, normalized.pgTransactionId(), now);
        } else {
            lifecycleService.resolveDefinitiveFailure(payment.id());
        }
        requireUpdated(jobService.markSucceeded(job));
    }

    private StatusResult normalize(StatusResult result, PaymentExecutionRecord payment) {
        if (result == null || result.outcome() == null) {
            return StatusResult.unknown("PG_INVALID_RESPONSE");
        }
        if (result.outcome() == Outcome.SUCCESS
                && (result.pgTransactionId() == null || result.pgTransactionId().isBlank())) {
            return StatusResult.unknown("PG_INVALID_SUCCESS_RESPONSE");
        }
        if (payment.pgTransactionId() != null && result.pgTransactionId() != null
                && !payment.pgTransactionId().equals(result.pgTransactionId())) {
            return StatusResult.unknown("PG_LOOKUP_TRANSACTION_MISMATCH");
        }
        return result;
    }

    private void requireUpdated(boolean updated) {
        if (!updated) {
            throw new PayException(PayErrorCode.STATE_CONFLICT);
        }
    }
}
