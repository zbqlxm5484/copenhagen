package com.copenhagen.core.payment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPaymentCompletionRepository implements PaymentCompletionRepository {

    private static final String INSERT_ORDER_SQL = """
            INSERT INTO orders
                (public_id, checkout_snapshot_id, user_id, merchant_order_id, status, ordered_at, created_at)
            VALUES (?, ?, ?, ?, 'CREATED', ?, ?)
            RETURNING id
            """;

    private static final String INSERT_PAID_TRANSACTION_SQL = """
            INSERT INTO payment_transactions
                (checkout_snapshot_id, order_id, authorization_session_id, pay_transaction_id, status,
                    payment_amount, currency, payment_label, approved_at, paid_at, created_at)
            VALUES (?, ?, ?, ?, 'PAID', ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_FAILED_TRANSACTION_SQL = """
            INSERT INTO payment_transactions
                (checkout_snapshot_id, authorization_session_id, pay_transaction_id, status,
                    payment_amount, currency, failure_code, failed_at, created_at)
            VALUES (?, ?, ?, 'FAILED', ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcPaymentCompletionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void recordCompleted(
            long checkoutSnapshotId,
            long userId,
            String authorizationSessionId,
            String merchantOrderId,
            String payTransactionId,
            long amountMinor,
            String currency,
            String paymentLabel,
            Instant now) {
        Long orderId = jdbcTemplate.queryForObject(
                INSERT_ORDER_SQL,
                Long.class,
                UUID.randomUUID(),
                checkoutSnapshotId,
                userId,
                merchantOrderId,
                Timestamp.from(now),
                Timestamp.from(now));
        jdbcTemplate.update(
                INSERT_PAID_TRANSACTION_SQL,
                checkoutSnapshotId,
                orderId,
                authorizationSessionId,
                payTransactionId,
                amountMinor,
                currency,
                paymentLabel,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    @Override
    public void recordFailed(
            long checkoutSnapshotId,
            String authorizationSessionId,
            String payTransactionId,
            long amountMinor,
            String currency,
            String failureCode,
            Instant now) {
        jdbcTemplate.update(
                INSERT_FAILED_TRANSACTION_SQL,
                checkoutSnapshotId,
                authorizationSessionId,
                payTransactionId,
                amountMinor,
                currency,
                failureCode,
                Timestamp.from(now),
                Timestamp.from(now));
    }
}
