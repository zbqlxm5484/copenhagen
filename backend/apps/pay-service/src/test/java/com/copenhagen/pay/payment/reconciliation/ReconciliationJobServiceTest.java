package com.copenhagen.pay.payment.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.payment.policy.ReconciliationJobStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ReconciliationJobServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void maxAttemptsMovesRunningJobToExhausted() {
        InMemoryReconciliationJobRepository repository =
                new InMemoryReconciliationJobRepository(2);
        ReconciliationJobService service = new ReconciliationJobService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                2,
                Duration.ofSeconds(60));

        service.markFailed("job-1", "PG_TIMEOUT", NOW.plusSeconds(30));

        assertEquals(ReconciliationJobStatus.EXHAUSTED, repository.completedStatus);
        assertEquals("PG_TIMEOUT", repository.lastError);
    }

    @Test
    void successfulCompletionKeepsRequiredNextRunTimestamp() {
        InMemoryReconciliationJobRepository repository =
                new InMemoryReconciliationJobRepository(1);
        ReconciliationJobService service = new ReconciliationJobService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                2,
                Duration.ofSeconds(60));

        service.markSucceeded("job-1");

        assertEquals(ReconciliationJobStatus.SUCCEEDED, repository.completedStatus);
        assertEquals(NOW, repository.completedNextRunAt);
    }

    @Test
    void concurrentClaimsReturnOneJob() throws Exception {
        InMemoryReconciliationJobRepository repository =
                new InMemoryReconciliationJobRepository(1);
        ReconciliationJobService service = new ReconciliationJobService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                3,
                Duration.ofSeconds(60));
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<ReconciliationJob>> first = executor.submit(
                    () -> claim(service, start));
            Future<Optional<ReconciliationJob>> second = executor.submit(
                    () -> claim(service, start));

            start.countDown();
            boolean firstClaimed = first.get(5, TimeUnit.SECONDS).isPresent();
            boolean secondClaimed = second.get(5, TimeUnit.SECONDS).isPresent();
            assertTrue(firstClaimed ^ secondClaimed);
        }
    }

    private Optional<ReconciliationJob> claim(
            ReconciliationJobService service,
            CountDownLatch start) throws InterruptedException {
        start.await();
        return service.claimNext();
    }

    private static final class InMemoryReconciliationJobRepository
            implements ReconciliationJobRepository {

        private final AtomicBoolean claimed = new AtomicBoolean();
        private final ReconciliationJob job;
        private ReconciliationJobStatus completedStatus;
        private Instant completedNextRunAt;
        private String lastError;

        private InMemoryReconciliationJobRepository(int attemptCount) {
            job = new ReconciliationJob(
                    "job-1",
                    "attempt-1",
                    ReconciliationJobStatus.RUNNING,
                    attemptCount,
                    NOW.plusSeconds(60));
        }

        @Override
        public Optional<ReconciliationJob> claimNext(Instant now, Instant leaseExpiresAt) {
            if (claimed.compareAndSet(false, true)) {
                return Optional.of(job);
            }
            return Optional.empty();
        }

        @Override
        public Optional<ReconciliationJob> findById(String jobId) {
            return Optional.of(job);
        }

        @Override
        public boolean complete(
                String jobId,
                ReconciliationJobStatus targetStatus,
                Instant nextRunAt,
                Instant completedAt,
                String lastError) {
            this.completedStatus = targetStatus;
            this.completedNextRunAt = nextRunAt;
            this.lastError = lastError;
            return true;
        }
    }
}
