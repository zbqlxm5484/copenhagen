package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthorizationSessionRepository implements AuthorizationSessionRepository {

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
        } catch (EmptyResultDataAccessException exception) {
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
}
