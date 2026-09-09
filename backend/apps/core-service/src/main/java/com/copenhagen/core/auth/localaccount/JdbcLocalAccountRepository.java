package com.copenhagen.core.auth.localaccount;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLocalAccountRepository implements LocalAccountRepository {

    private static final String EXISTS_BY_LOGIN_ID_SQL = """
            SELECT EXISTS (
                SELECT 1 FROM local_account WHERE login_id = ?
            )
            """;

    private static final String FIND_BY_LOGIN_ID_SQL = """
            SELECT id, user_id, login_id, password_hash, password_changed_at,
                failed_login_attempts, last_failed_login_at, locked_until, last_login_at,
                created_at, updated_at
            FROM local_account
            WHERE login_id = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO local_account
                (user_id, login_id, password_hash, password_changed_at,
                 failed_login_attempts, created_at, updated_at)
            VALUES (?, ?, ?, ?, 0, ?, ?)
            RETURNING id
            """;

    private static final RowMapper<LocalAccount> ROW_MAPPER = JdbcLocalAccountRepository::mapLocalAccount;

    private final JdbcTemplate jdbcTemplate;

    public JdbcLocalAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean existsByLoginId(String loginId) {
        Boolean exists = jdbcTemplate.queryForObject(EXISTS_BY_LOGIN_ID_SQL, Boolean.class, loginId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<LocalAccount> findByLoginId(String loginId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(FIND_BY_LOGIN_ID_SQL, ROW_MAPPER, loginId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public long insert(long userId, String loginId, String passwordHash, Instant now) {
        return jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                userId,
                loginId,
                passwordHash,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    private static LocalAccount mapLocalAccount(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp lastFailedLoginAt = resultSet.getTimestamp("last_failed_login_at");
        Timestamp lockedUntil = resultSet.getTimestamp("locked_until");
        Timestamp lastLoginAt = resultSet.getTimestamp("last_login_at");
        return new LocalAccount(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                resultSet.getString("login_id"),
                resultSet.getString("password_hash"),
                resultSet.getTimestamp("password_changed_at").toInstant(),
                resultSet.getInt("failed_login_attempts"),
                lastFailedLoginAt == null ? null : lastFailedLoginAt.toInstant(),
                lockedUntil == null ? null : lockedUntil.toInstant(),
                lastLoginAt == null ? null : lastLoginAt.toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
