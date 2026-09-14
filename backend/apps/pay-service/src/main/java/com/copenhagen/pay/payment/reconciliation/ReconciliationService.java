package com.copenhagen.pay.payment.reconciliation;

import com.copenhagen.pay.payment.payment.PaymentExecutionRecord;
import com.copenhagen.pay.payment.pg.PaymentGateway;
import com.copenhagen.pay.payment.pg.PaymentGateway.StatusRequest;
import com.copenhagen.pay.payment.pg.PaymentGateway.StatusResult;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import com.copenhagen.pay.payment.policy.ReconciliationJobStatus;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Coordinates short database transactions around a read-only PG network call. */
@Service
public class ReconciliationService {

    private final ReconciliationJobService jobs;
    private final ReconciliationPaymentRepository payments;
    private final PaymentGateway gateway;
    private final ReconciliationSettlementService settlement;
    private final Clock clock;

    public ReconciliationService(
            ReconciliationJobService jobs,
            ReconciliationPaymentRepository payments,
            PaymentGateway gateway,
            ReconciliationSettlementService settlement,
            Clock clock) {
        this.jobs = jobs;
        this.payments = payments;
        this.gateway = gateway;
        this.settlement = settlement;
        this.clock = clock;
    }

    public boolean processNext() {
        Optional<ReconciliationJob> claimed = jobs.claimNext();
        if (claimed.isEmpty()) {
            return false;
        }
        ReconciliationJob job = claimed.orElseThrow();
        if (job.status() != ReconciliationJobStatus.RUNNING
                || job.leaseExpiresAt() == null || !clock.instant().isBefore(job.leaseExpiresAt())) {
            return true;
        }
        Optional<PaymentExecutionRecord> found = payments.findByAttemptId(job.paymentAttemptId());
        if (found.isEmpty()) {
            jobs.markFailed(job);
            return true;
        }
        PaymentExecutionRecord payment = found.orElseThrow();
        if (payment.status() != PaymentStatus.UNKNOWN) {
            jobs.markCancelled(job);
            return true;
        }
        if (!"UNKNOWN".equals(payment.attemptStatus()) || !"UNKNOWN".equals(payment.pgTransactionStatus())) {
            jobs.markFailed(job);
            return true;
        }
        StatusResult result;
        try {
            result = gateway.lookup(new StatusRequest(payment.pgTransactionId(), payment.pgRequestId()));
        } catch (RuntimeException exception) {
            // The external adapter boundary must not leak exception messages or trigger a charge retry.
            result = StatusResult.unknown("PG_LOOKUP_UNAVAILABLE");
        }
        settlement.settle(job, result);
        return true;
    }
}
