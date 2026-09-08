package com.copenhagen.pay.payment.reconciliation;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.policy.ReconciliationJobPolicy;
import com.copenhagen.pay.payment.policy.ReconciliationJobStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationJobService {

    private final ReconciliationJobRepository repository;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration leaseDuration;

    @Autowired
    public ReconciliationJobService(
            ReconciliationJobRepository repository,
            @Value("${pay.reconciliation.max-attempts:5}") int maxAttempts,
            @Value("${pay.reconciliation.lease-duration-seconds:60}") long leaseDurationSeconds) {
        this(repository, Clock.systemUTC(), maxAttempts, Duration.ofSeconds(leaseDurationSeconds));
    }

    ReconciliationJobService(
            ReconciliationJobRepository repository,
            Clock clock,
            int maxAttempts,
            Duration leaseDuration) {
        if (maxAttempts <= 0 || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("reconciliation policy must be positive");
        }
        this.repository = repository;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.leaseDuration = leaseDuration;
    }

    @Transactional
    public Optional<ReconciliationJob> claimNext() {
        Instant now = clock.instant();
        return repository.claimNext(now, now.plus(leaseDuration));
    }

    @Transactional
    public void markSucceeded(String jobId) {
        Instant now = clock.instant();
        complete(jobId, ReconciliationJobStatus.SUCCEEDED, null, now, now);
    }

    @Transactional
    public void markFailed(String jobId, String errorCode, Instant nextRunAt) {
        Objects.requireNonNull(nextRunAt, "nextRunAt");
        ReconciliationJob job = findRunningJob(jobId);
        ReconciliationJobStatus targetStatus = ReconciliationJobPolicy.isExhausted(
                job.attemptCount(),
                maxAttempts)
                ? ReconciliationJobStatus.EXHAUSTED
                : ReconciliationJobStatus.FAILED;
        complete(jobId, targetStatus, errorCode, nextRunAt, clock.instant());
    }

    private void complete(
            String jobId,
            ReconciliationJobStatus targetStatus,
            String lastError,
            Instant nextRunAt,
            Instant completedAt) {
        ReconciliationJob job = findRunningJob(jobId);
        if (!com.copenhagen.pay.payment.policy.PayStateTransitionPolicy.canTransition(
                job.status(),
                targetStatus)) {
            throw stateConflict(job.status());
        }
        if (!repository.complete(jobId, targetStatus, nextRunAt, completedAt, lastError)) {
            throw stateConflict(job.status());
        }
    }

    private ReconciliationJob findRunningJob(String jobId) {
        ReconciliationJob job = repository.findById(jobId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
        if (job.status() != ReconciliationJobStatus.RUNNING) {
            throw stateConflict(job.status());
        }
        return job;
    }

    private PayException stateConflict(ReconciliationJobStatus currentStatus) {
        return new PayException(
                PayErrorCode.STATE_CONFLICT,
                PayErrorCode.STATE_CONFLICT.defaultMessage(),
                currentStatus.name(),
                PayErrorCode.STATE_CONFLICT.defaultNextAction());
    }
}
