package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentApprovalStatusRepository implements PaymentApprovalStatusRepository {

    private static final String FIND_STATUS_SQL = """
            SELECT s.id AS approval_id,
                   s.status AS approval_status,
                   p.id AS payment_id,
                   p.status AS payment_status,
                   s.amount_minor,
                   s.currency
            FROM authorization_sessions s
            LEFT JOIN payment_credential_tokens c
              ON c.authorization_session_id = s.id
            LEFT JOIN payments p
              ON p.credential_token_id = c.id
            WHERE s.id = ?
            """;

    private static final RowMapper<PaymentApprovalStatusView> ROW_MAPPER =
            JdbcPaymentApprovalStatusRepository::mapStatus;

    private final JdbcTemplate jdbcTemplate;

    public JdbcPaymentApprovalStatusRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PaymentApprovalStatusView> findByApprovalId(String approvalId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    FIND_STATUS_SQL,
                    ROW_MAPPER,
                    approvalId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private static PaymentApprovalStatusView mapStatus(
            ResultSet resultSet,
            int rowNumber) throws SQLException {
        String paymentStatus = resultSet.getString("payment_status");
        return new PaymentApprovalStatusView(
                resultSet.getString("approval_id"),
                AuthorizationSessionStatus.valueOf(resultSet.getString("approval_status")),
                resultSet.getString("payment_id"),
                paymentStatus == null ? null : PaymentStatus.valueOf(paymentStatus),
                resultSet.getLong("amount_minor"),
                resultSet.getString("currency"));
    }
}
