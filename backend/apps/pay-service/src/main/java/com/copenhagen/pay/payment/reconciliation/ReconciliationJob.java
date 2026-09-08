package com.copenhagen.pay.payment.reconciliation;

import com.copenhagen.pay.payment.policy.ReconciliationJobStatus;
import java.time.Instant;

public record ReconciliationJob(
        String id,
        String paymentAttemptId,
        ReconciliationJobStatus status,
        int attemptCount,
        Instant leaseExpiresAt) {
}
