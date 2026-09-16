package com.copenhagen.merchantmock.paymenthandler;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.merchantmock.checkout.CheckoutRepository;
import com.copenhagen.merchantmock.checkout.PaymentRecheck;
import com.copenhagen.merchantmock.checkout.CheckoutSession;
import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 청구 서비스의 상태 전이(DESIGN.md 7.2절, 8.5절). 저장소와 클라이언트는 둘 다 가짜다.
 *
 * <p>저장소 가짜는 결제 컬럼 둘만 흉내 낸다. SQL 조건이 맞는지는 JdbcCheckoutRepositoryPostgresTest 가 본다.
 */
class PaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T04:00:00Z");

    private RecordingRepository repository;
    private StubPayClient client;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        repository = new RecordingRepository();
        client = new StubPayClient();
        service = new PaymentService(repository, client, MerchantPropertiesFixture.defaults(),
                new PayProperties("http://pay-service:8080", "test-token", null, null, null, 0),
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    }

    @Test
    @DisplayName("성공하면 PROCESSING 을 거쳐 SUCCESS 와 paymentId 가 남는다")
    void charge_success_marksProcessingThenSuccess() {
        // given
        client.result = new PaymentResult(PaymentStatus.SUCCESS, "pay-1", "pg-1", null, "CREATE_ORDER");

        // when
        PaymentResult result = service.charge(charge());

        // then
        assertSame(client.result, result);
        assertEquals(List.of("begin:checkout-1@" + OffsetDateTime.ofInstant(NOW, ZoneId.of("Asia/Seoul")),
                "finish:checkout-1:SUCCESS:pay-1"), repository.calls);
        assertEquals(PaymentStatus.SUCCESS, repository.status);
        assertEquals("pay-1", repository.payPaymentId);
    }

    @Test
    @DisplayName("결과가 UNKNOWN 이면 그대로 남기고 재청구하지 않는다")
    void charge_unknown_storesUnknownWithoutRetry() {
        // given
        client.result = PaymentResult.unknown("PAY_UNREACHABLE");

        // when
        PaymentResult result = service.charge(charge());

        // then
        assertEquals(PaymentStatus.UNKNOWN, result.status());
        assertEquals(1, client.requests.size(), "UNKNOWN 은 재청구 사유가 아니다");
        assertEquals(PaymentStatus.UNKNOWN, repository.status);
        assertNull(repository.payPaymentId);
    }

    @Test
    @DisplayName("보내는 요청에 merchant.id 와 credentialId 기반 멱등 키가 실린다")
    void charge_anyCharge_buildsRequestFromMerchantIdAndCredential() {
        // given
        client.result = new PaymentResult(PaymentStatus.SUCCESS, "pay-1", "pg-1", null, null);

        // when
        service.charge(charge());

        // then
        PaymentRequest sent = client.requests.get(0);
        assertEquals("merchant-a", sent.merchantId());
        assertEquals("checkout-1", sent.businessCheckoutId());
        assertEquals("sha256:abc", sent.checkoutHash());
        assertEquals(92000L, sent.amountMinor());
        assertEquals("KRW", sent.currency());
        assertEquals("merchant-payment-cred-1", sent.idempotencyKey());
    }

    @Test
    @DisplayName("이미 결제가 시작된 체크아웃이면 예외이고 Pay 를 부르지 않는다")
    void charge_paymentAlreadyStarted_throwsWithoutCallingPay() {
        // given
        repository.beginResult = false;

        // when & then
        assertThrows(IllegalStateException.class, () -> service.charge(charge()));
        assertTrue(client.requests.isEmpty(), "재진입에서 Pay 를 부르면 이중 청구다");
        assertEquals(List.of("begin:checkout-1@" + OffsetDateTime.ofInstant(NOW, ZoneId.of("Asia/Seoul"))),
                repository.calls);
    }

    @Test
    @DisplayName("클라이언트가 약속을 깨고 예외를 내면 UNKNOWN 으로 닫고 다시 던진다")
    void charge_clientThrows_closesAsUnknownAndRethrows() {
        // given
        client.failure = new IllegalStateException("bug");

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> service.charge(charge()));

        // then
        assertSame(client.failure, thrown);
        assertEquals(PaymentStatus.UNKNOWN, repository.status, "PROCESSING 으로 영원히 남는 행을 만들지 않는다");
    }

    @Test
    @DisplayName("결과를 반영할 PROCESSING 행이 없으면 예외다")
    void charge_finishFindsNoProcessingRow_throws() {
        // given
        client.result = new PaymentResult(PaymentStatus.SUCCESS, "pay-1", "pg-1", null, null);
        repository.finishResult = false;

        // when & then
        assertThrows(IllegalStateException.class, () -> service.charge(charge()));
    }

    private static PaymentService.Charge charge() {
        return new PaymentService.Charge("checkout-1", "sha256:abc", 92000L, "KRW", "cred-1");
    }

    /** 결제 컬럼 둘만 기억하는 저장소. 나머지 메서드는 이 테스트가 부르지 않는다. */
    private static final class RecordingRepository implements CheckoutRepository {

        final List<String> calls = new ArrayList<>();
        boolean beginResult = true;
        boolean finishResult = true;
        PaymentStatus status;
        String payPaymentId;

        String credentialId;
        OffsetDateTime firstRecheckAt;

        @Override
        public boolean beginPayment(String id, OffsetDateTime requestedAt, String newCredentialId,
                OffsetDateTime newFirstRecheckAt) {
            calls.add("begin:" + id + "@" + requestedAt);
            credentialId = newCredentialId;
            firstRecheckAt = newFirstRecheckAt;
            if (beginResult) {
                status = PaymentStatus.PROCESSING;
            }
            return beginResult;
        }

        @Override
        public boolean finishPayment(String id, PaymentStatus newStatus, String newPayPaymentId) {
            calls.add("finish:" + id + ":" + newStatus + ":" + newPayPaymentId);
            if (finishResult) {
                status = newStatus;
                payPaymentId = newPayPaymentId;
            }
            return finishResult;
        }

        @Override
        public boolean insert(CheckoutSession session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CheckoutSession> findById(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean update(CheckoutSession session, long expectedVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancel(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int expireReady(OffsetDateTime now, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PaymentRecheck> claimUnknownDue(OffsetDateTime now, OffsetDateTime leaseUntil,
                int maxAttempts, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean resolvePayment(String id, PaymentStatus newStatus, String newPayPaymentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean scheduleNextRecheck(String id, OffsetDateTime nextAttemptAt) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubPayClient implements PayClient {

        final List<PaymentRequest> requests = new ArrayList<>();
        PaymentResult result;
        RuntimeException failure;

        @Override
        public PaymentResult lookup(String paymentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PaymentResult charge(PaymentRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
