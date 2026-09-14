package com.copenhagen.merchantmock.checkout;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** DB 없이 돌리는 저장소. 조건부 UPDATE 의 version 규칙을 JDBC 구현과 같게 흉내 낸다. */
final class InMemoryCheckoutRepository implements CheckoutRepository {

    private final Map<String, CheckoutSession> rows = new ConcurrentHashMap<>();

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

    int size() {
        return rows.size();
    }

    /** 테스트가 completed 같은 상태를 직접 심을 때 쓴다. */
    void put(CheckoutSession session) {
        rows.put(session.id(), session);
    }
}
