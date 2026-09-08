package com.copenhagen.pay.payment.reconciliation;

import java.time.Instant;
import java.util.Optional;

import com.copenhagen.pay.payment.policy.ReconciliationJobStatus;

public interface ReconciliationJobRepository {

    Optional<ReconciliationJob> claimNext(Instant now, Instant leaseExpiresAt);

    Optional<ReconciliationJob> findById(String jobId);

    boolean complete(
            String jobId,
            ReconciliationJobStatus targetStatus,
            Instant nextRunAt,
            Instant completedAt,
            String lastError);
}
