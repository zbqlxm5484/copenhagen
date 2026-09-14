package com.copenhagen.merchantmock.checkout;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** checkout_sessions 를 읽고 쓴다. JdbcTemplate 만 쓴다(DESIGN.md 4절). */
@Repository
public class JdbcCheckoutRepository implements CheckoutRepository {

    private static final String INSERT = """
            INSERT INTO checkout_sessions
                (id, status, currency, total_amount, fulfillment_address, messages_json,
                 canonical_checkout, expires_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
            """;

    private static final String SELECT = """
            SELECT id, status, currency, total_amount, fulfillment_address::text, messages_json::text,
                   canonical_checkout, expires_at, version
              FROM checkout_sessions
             WHERE id = ?
            """;

    private static final String UPDATE = """
            UPDATE checkout_sessions
               SET status = ?, currency = ?, total_amount = ?, fulfillment_address = ?::jsonb,
                   messages_json = ?::jsonb, canonical_checkout = ?, expires_at = ?,
                   version = version + 1, updated_at = now()
             WHERE id = ? AND version = ?
            """;

    private static final String CANCEL = """
            UPDATE checkout_sessions
               SET status = 'CANCELED', version = version + 1, updated_at = now()
             WHERE id = ?
               AND status IN ('INCOMPLETE', 'REQUIRES_ESCALATION', 'READY_FOR_COMPLETE')
            """;

    private static final String EXPIRE = """
            UPDATE checkout_sessions
               SET status = 'CANCELED', version = version + 1, updated_at = now()
             WHERE id IN (SELECT id
                            FROM checkout_sessions
                           WHERE status = 'READY_FOR_COMPLETE'
                             AND expires_at < ?
                           ORDER BY expires_at
                           LIMIT ?)
               AND status = 'READY_FOR_COMPLETE'
            """;

    private static final RowMapper<CheckoutSession> ROW_MAPPER = (rs, rowNum) -> new CheckoutSession(
            rs.getString("id"),
            CheckoutStatus.valueOf(rs.getString("status")),
            rs.getString("currency"),
            rs.getObject("total_amount", Long.class),
            rs.getString("fulfillment_address"),
            rs.getString("messages_json"),
            rs.getString("canonical_checkout"),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getLong("version"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcCheckoutRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean insert(CheckoutSession s) {
        try {
            jdbcTemplate.update(INSERT,
                    s.id(), s.status().name(), s.currency(), s.totalAmount(),
                    s.fulfillmentAddressJson(), s.messagesJson(), s.canonicalCheckout(), s.expiresAt());
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public Optional<CheckoutSession> findById(String id) {
        return jdbcTemplate.query(SELECT, ROW_MAPPER, id).stream().findFirst();
    }

    @Override
    public boolean cancel(String id) {
        return jdbcTemplate.update(CANCEL, id) == 1;
    }

    @Override
    public int expireReady(OffsetDateTime now, int limit) {
        return jdbcTemplate.update(EXPIRE, now, limit);
    }

    @Override
    public boolean update(CheckoutSession s, long expectedVersion) {
        int updated = jdbcTemplate.update(UPDATE,
                s.status().name(), s.currency(), s.totalAmount(), s.fulfillmentAddressJson(),
                s.messagesJson(), s.canonicalCheckout(), s.expiresAt(),
                s.id(), expectedVersion);
        return updated == 1;
    }
}
