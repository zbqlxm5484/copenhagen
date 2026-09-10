package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthorizationSessionRepository
        implements AuthorizationSessionRepository, AuthorizationSessionStore {

    private static final String FIND_STATUS_SQL = """
            SELECT status
            FROM authorization_sessions
            WHERE id = ?
            """;

    private static final String TRANSITION_STATUS_SQL = """
            UPDATE authorization_sessions
            SET status = ?, version = version + 1
            WHERE id = ? AND status = ?
            """;

    private static final String APPROVE_SQL = """
            UPDATE authorization_sessions
            SET status = 'APPROVED', auth_method = ?, approved_at = ?, updated_at = ?,
                version = version + 1
            WHERE id = ? AND user_id = ? AND status = 'PENDING' AND expires_at > ?
            """;

    private static final String REJECT_SQL = """
            UPDATE authorization_sessions
            SET status = 'REJECTED', updated_at = ?, version = version + 1
            WHERE id = ? AND user_id = ?
              AND status IN ('PAYMENT_METHOD_REQUIRED', 'PENDING')
              AND expires_at > ?
            """;

    private static final String SET_REQUIRED_ACTION_SQL = """
            UPDATE authorization_sessions
            SET required_action = ?, updated_at = ?, version = version + 1
            WHERE id = ? AND user_id = ? AND status = ? AND expires_at > ?
            """;

    private static final String SELECT_BASE_SQL = """
            SELECT s.id, s.purchase_request_id, s.user_id, s.merchant_account_id,
                   m.merchant_id, m.display_name AS merchant_name,
                   s.external_checkout_id, s.checkout_digest,
                   s.display_snapshot::text AS display_snapshot_json,
                   s.amount_minor, s.currency, s.selected_payment_method_id,
                   s.status, s.idempotency_key, s.request_hash, s.required_action,
                   s.expires_at, s.approved_at, s.auth_method,
                   s.created_at, s.updated_at, s.version
            FROM authorization_sessions s
            LEFT JOIN merchant_accounts m ON m.id = s.merchant_account_id
            """;

    private static final String FIND_BY_IDEMPOTENCY_SQL = SELECT_BASE_SQL + """
            WHERE s.merchant_account_id = ? AND s.idempotency_key = ?
            """;

    private static final String FIND_ACTIVE_BY_PURCHASE_REQUEST_SQL = SELECT_BASE_SQL + """
            WHERE s.merchant_account_id = ?
              AND s.purchase_request_id = ?
              AND s.status IN ('PAYMENT_METHOD_REQUIRED', 'PENDING', 'APPROVED')
            """;

    private static final String INSERT_SQL = """
            INSERT INTO authorization_sessions
                (id, purchase_request_id, user_id, merchant_account_id,
                 external_checkout_id, checkout_digest, display_snapshot,
                 amount_minor, currency, selected_payment_method_id, status,
                 idempotency_key, request_hash, required_action, expires_at,
                 approved_at, auth_method, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private static final String FIND_BY_USER_SQL = SELECT_BASE_SQL + """
            WHERE s.user_id = ?
              AND s.status IN ('PAYMENT_METHOD_REQUIRED', 'PENDING', 'APPROVED')
            ORDER BY s.created_at DESC
            """;

    private static final String FIND_BY_USER_AND_STATUS_SQL = SELECT_BASE_SQL + """
            WHERE s.user_id = ? AND s.status = ?
            ORDER BY s.created_at DESC
            """;

    private static final String FIND_BY_ID_AND_USER_SQL = SELECT_BASE_SQL + """
            WHERE s.id = ? AND s.user_id = ?
            """;

    private static final String EXPIRE_OVERDUE_BY_USER_SQL = """
            UPDATE authorization_sessions
            SET status = 'EXPIRED', version = version + 1
            WHERE user_id = ?
              AND status IN ('PAYMENT_METHOD_REQUIRED', 'PENDING', 'APPROVED')
              AND expires_at <= ?
            """;

    private static final String EXPIRE_OVERDUE_BY_ID_AND_USER_SQL = """
            UPDATE authorization_sessions
            SET status = 'EXPIRED', version = version + 1
            WHERE id = ?
              AND user_id = ?
              AND status IN ('PAYMENT_METHOD_REQUIRED', 'PENDING', 'APPROVED')
              AND expires_at <= ?
            """;

    private static final RowMapper<AuthorizationSession> ROW_MAPPER =
            JdbcAuthorizationSessionRepository::mapAuthorizationSession;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAuthorizationSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<AuthorizationSessionStatus> findStatus(String authorizationSessionId) {
        try {
            String status = jdbcTemplate.queryForObject(
                    FIND_STATUS_SQL,
                    String.class,
                    authorizationSessionId);
            return Optional.of(AuthorizationSessionStatus.valueOf(status));
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public boolean transitionStatus(
            String authorizationSessionId,
            AuthorizationSessionStatus expectedStatus,
            AuthorizationSessionStatus targetStatus) {
        int updated = jdbcTemplate.update(
                TRANSITION_STATUS_SQL,
                targetStatus.name(),
                authorizationSessionId,
                expectedStatus.name());
        return updated == 1;
    }

    @Override
    public Optional<AuthorizationSession> findByMerchantAndIdempotencyKey(
            String merchantAccountId,
            String idempotencyKey) {
        return jdbcTemplate.query(
                        FIND_BY_IDEMPOTENCY_SQL,
                        ROW_MAPPER,
                        merchantAccountId,
                        idempotencyKey)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<AuthorizationSession> findActiveByMerchantAndPurchaseRequest(
            String merchantAccountId,
            String purchaseRequestId) {
        return jdbcTemplate.query(
                        FIND_ACTIVE_BY_PURCHASE_REQUEST_SQL,
                        ROW_MAPPER,
                        merchantAccountId,
                        purchaseRequestId)
                .stream()
                .findFirst();
    }

    @Override
    public boolean insert(AuthorizationSession session) {
        int inserted = jdbcTemplate.update(
                INSERT_SQL,
                session.id(),
                session.purchaseRequestId(),
                session.userId(),
                session.merchantAccountId(),
                session.externalCheckoutId(),
                session.checkoutDigest(),
                session.displaySnapshotJson(),
                session.amountMinor(),
                session.currency(),
                session.selectedPaymentMethodId(),
                session.status().name(),
                session.idempotencyKey(),
                session.requestHash(),
                session.requiredAction(),
                session.expiresAt(),
                session.approvedAt(),
                session.authMethod(),
                session.createdAt(),
                session.updatedAt(),
                session.version());
        return inserted == 1;
    }

    @Override
    public List<AuthorizationSession> findByUserId(
            String userId,
            AuthorizationSessionStatus status) {
        if (status == null) {
            return jdbcTemplate.query(FIND_BY_USER_SQL, ROW_MAPPER, userId);
        }
        return jdbcTemplate.query(
                FIND_BY_USER_AND_STATUS_SQL,
                ROW_MAPPER,
                userId,
                status.name());
    }

    @Override
    public Optional<AuthorizationSession> findByIdAndUserId(
            String authorizationSessionId,
            String userId) {
        return jdbcTemplate.query(
                        FIND_BY_ID_AND_USER_SQL,
                        ROW_MAPPER,
                        authorizationSessionId,
                        userId)
                .stream()
                .findFirst();
    }

    @Override
    public int expireOverdueForUser(String userId, Instant now) {
        return jdbcTemplate.update(EXPIRE_OVERDUE_BY_USER_SQL, userId, now);
    }

    @Override
    public int expireOverdueByIdAndUser(
            String authorizationSessionId,
            String userId,
            Instant now) {
        return jdbcTemplate.update(
                EXPIRE_OVERDUE_BY_ID_AND_USER_SQL,
                authorizationSessionId,
                userId,
                now);
    }

    @Override
    public boolean approve(
            String authorizationSessionId,
            String userId,
            String authMethod,
            Instant approvedAt) {
        return jdbcTemplate.update(
                APPROVE_SQL,
                authMethod,
                approvedAt,
                approvedAt,
                authorizationSessionId,
                userId,
                approvedAt) == 1;
    }

    @Override
    public boolean reject(
            String authorizationSessionId,
            String userId,
            Instant rejectedAt) {
        return jdbcTemplate.update(
                REJECT_SQL,
                rejectedAt,
                authorizationSessionId,
                userId,
                rejectedAt) == 1;
    }

    @Override
    public boolean setRequiredAction(
            String authorizationSessionId,
            String userId,
            AuthorizationSessionStatus expectedStatus,
            String requiredAction,
            Instant now) {
        return jdbcTemplate.update(
                SET_REQUIRED_ACTION_SQL,
                requiredAction,
                now,
                authorizationSessionId,
                userId,
                expectedStatus.name(),
                now) == 1;
    }

    private static AuthorizationSession mapAuthorizationSession(
            ResultSet resultSet,
            int rowNumber) throws SQLException {
        return new AuthorizationSession(
                resultSet.getString("id"),
                resultSet.getString("purchase_request_id"),
                resultSet.getString("user_id"),
                resultSet.getString("merchant_account_id"),
                resultSet.getString("merchant_id"),
                resultSet.getString("merchant_name"),
                resultSet.getString("external_checkout_id"),
                resultSet.getString("checkout_digest"),
                resultSet.getString("display_snapshot_json"),
                resultSet.getLong("amount_minor"),
                resultSet.getString("currency"),
                resultSet.getString("selected_payment_method_id"),
                AuthorizationSessionStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("idempotency_key"),
                resultSet.getString("request_hash"),
                resultSet.getString("required_action"),
                resultSet.getObject("expires_at", Instant.class),
                resultSet.getObject("approved_at", Instant.class),
                resultSet.getString("auth_method"),
                resultSet.getObject("created_at", Instant.class),
                resultSet.getObject("updated_at", Instant.class),
                resultSet.getInt("version"));
    }
}
