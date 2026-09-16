package com.copenhagen.merchantmock.checkout;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.copenhagen.contract.internalapi.PaymentStatus;

/** DB 없이 돌리는 저장소. 조건부 UPDATE 의 version 규칙을 JDBC 구현과 같게 흉내 낸다. */
final class InMemoryCheckoutRepository implements CheckoutRepository {

    private final Map<String, CheckoutSession> rows = new ConcurrentHashMap<>();
    private final Map<String, PaymentRow> payments = new ConcurrentHashMap<>();

    /** checkout_sessions 의 결제 컬럼 셋. CheckoutSession 에는 아직 없어 옆에 둔다(179). */
    record PaymentRow(PaymentStatus status, String payPaymentId, OffsetDateTime requestedAt) {
    }

    @Override
    public boolean insert(CheckoutSession session) {
        return rows.putIfAbsent(session.id(), session) == null;
    }

    @Override
    public Optional<CheckoutSession> findById(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public boolean update(CheckoutSession session, long expectedVersion) {
        CheckoutSession current = rows.get(session.id());
        if (current == null || current.version() != expectedVersion) {
            return false;
        }
        rows.put(session.id(), session.withVersion(expectedVersion + 1));
        return true;
    }

    @Override
    public boolean cancel(String id) {
        CheckoutSession current = rows.get(id);
        if (current == null || !current.status().modifiable()) {
            return false;
        }
        rows.put(id, current.withStatus(CheckoutStatus.CANCELED));
        return true;
    }

    @Override
    public int expireReady(OffsetDateTime now, int limit) {
        List<CheckoutSession> due = rows.values().stream()
                .filter(r -> r.status() == CheckoutStatus.READY_FOR_COMPLETE)
                .filter(r -> r.expiresAt() != null && r.expiresAt().isBefore(now))
                .sorted(Comparator.comparing(CheckoutSession::expiresAt))
                .limit(limit)
                .toList();
        due.forEach(r -> rows.put(r.id(), r.withStatus(CheckoutStatus.CANCELED)));
        return due.size();
    }

    @Override
    public boolean beginPayment(String id, OffsetDateTime requestedAt, String credentialId,
            OffsetDateTime firstRecheckAt) {
        CheckoutSession current = rows.get(id);
        if (current == null || payments.containsKey(id)) {
            return false;
        }
        payments.put(id, new PaymentRow(PaymentStatus.PROCESSING, null, requestedAt));
        rows.put(id, current.withVersion(current.version() + 1));
        return true;
    }

    @Override
    public List<PaymentRecheck> claimUnknownDue(OffsetDateTime now, OffsetDateTime leaseUntil,
            int maxAttempts, int limit) {
        return List.of();
    }

    @Override
    public boolean resolvePayment(String id, PaymentStatus status, String payPaymentId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean scheduleNextRecheck(String id, OffsetDateTime nextAttemptAt) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean finishPayment(String id, PaymentStatus status, String payPaymentId) {
        PaymentRow payment = payments.get(id);
        CheckoutSession current = rows.get(id);
        if (current == null || payment == null || payment.status() != PaymentStatus.PROCESSING) {
            return false;
        }
        payments.put(id, new PaymentRow(status,
                payPaymentId == null ? payment.payPaymentId() : payPaymentId, payment.requestedAt()));
        rows.put(id, current.withVersion(current.version() + 1));
        return true;
    }

    /** 테스트가 결제 컬럼을 읽을 때 쓴다. 결제가 없으면 null. */
    PaymentRow paymentRow(String id) {
        return payments.get(id);
    }

    int size() {
        return rows.size();
    }

    /** 테스트가 completed 같은 상태를 직접 심을 때 쓴다. */
    void put(CheckoutSession session) {
        rows.put(session.id(), session);
    }
}
