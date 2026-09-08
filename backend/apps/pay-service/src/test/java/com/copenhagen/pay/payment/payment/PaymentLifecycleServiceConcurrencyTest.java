package com.copenhagen.pay.payment.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.payment.authorization.AuthorizationSessionLifecycleService;
import com.copenhagen.pay.payment.authorization.AuthorizationSessionRepository;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PaymentLifecycleServiceConcurrencyTest {

    @Test
    void concurrentSuccessResolutionAllowsOnlyOnePaymentTransition() throws Exception {
        InMemoryPaymentRepository paymentRepository = new InMemoryPaymentRepository();
        InMemoryAuthorizationSessionRepository sessionRepository =
                new InMemoryAuthorizationSessionRepository();
        PaymentLifecycleService service = new PaymentLifecycleService(
                paymentRepository,
                new AuthorizationSessionLifecycleService(sessionRepository));
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> resolve(service, start));
            Future<Boolean> second = executor.submit(() -> resolve(service, start));
            start.countDown();

            assertTrue(first.get(5, TimeUnit.SECONDS) ^ second.get(5, TimeUnit.SECONDS));
        }

        assertEquals(PaymentStatus.SUCCESS, paymentRepository.status.get());
        assertEquals(AuthorizationSessionStatus.COMPLETED, sessionRepository.status.get());
    }

    private boolean resolve(PaymentLifecycleService service, CountDownLatch start)
            throws InterruptedException {
        start.await();
        try {
            service.resolveSuccess("payment-1");
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static final class InMemoryPaymentRepository implements PaymentRepository {

        private final AtomicReference<PaymentStatus> status =
                new AtomicReference<>(PaymentStatus.PROCESSING);

        @Override
        public Optional<PaymentStatus> findStatus(String paymentId) {
            return Optional.of(status.get());
        }

        @Override
        public Optional<String> findAuthorizationSessionId(String paymentId) {
            return Optional.of("session-1");
        }

        @Override
        public boolean existsLiveByPurchaseRequest(String merchantAccountId, String purchaseRequestId) {
            return status.get().isLive();
        }

        @Override
        public boolean transitionStatus(
                String paymentId,
                PaymentStatus expectedStatus,
                PaymentStatus targetStatus) {
            return status.compareAndSet(expectedStatus, targetStatus);
        }
    }

    private static final class InMemoryAuthorizationSessionRepository
            implements AuthorizationSessionRepository {

        private final AtomicReference<AuthorizationSessionStatus> status =
                new AtomicReference<>(AuthorizationSessionStatus.APPROVED);

        @Override
        public Optional<AuthorizationSessionStatus> findStatus(String authorizationSessionId) {
            return Optional.of(status.get());
        }

        @Override
        public boolean transitionStatus(
                String authorizationSessionId,
                AuthorizationSessionStatus expectedStatus,
                AuthorizationSessionStatus targetStatus) {
            return status.compareAndSet(expectedStatus, targetStatus);
        }
    }
}
