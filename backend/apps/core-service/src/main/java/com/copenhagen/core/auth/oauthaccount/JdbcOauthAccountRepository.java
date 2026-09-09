package com.copenhagen.core.auth.oauthaccount;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOauthAccountRepository implements OauthAccountRepository {

    private static final String INSERT_SQL = """
            INSERT INTO oauth_account
                (user_id, provider, provider_subject, email, email_verified, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String FIND_BY_PROVIDER_AND_SUBJECT_SQL = """
            SELECT id, user_id, provider, provider_subject, email, email_verified,
                last_login_at, created_at, updated_at
            FROM oauth_account
            WHERE provider = ? AND provider_subject = ?
            """;

    private static final String FIND_ALL_BY_USER_ID_SQL = """
            SELECT id, user_id, provider, provider_subject, email, email_verified,
                last_login_at, created_at, updated_at
            FROM oauth_account
            WHERE user_id = ?
            """;

    private static final RowMapper<OauthAccount> ROW_MAPPER = JdbcOauthAccountRepository::mapOauthAccount;

    private final JdbcTemplate jdbcTemplate;

    public JdbcOauthAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long insert(
            long userId,
            OauthProvider provider,
            String providerSubject,
            String email,
            boolean emailVerified,
            Instant now) {
        return jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                userId,
                provider.value(),
                providerSubject,
                email,
                emailVerified,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    @Override
    public Optional<OauthAccount> findByProviderAndProviderSubject(OauthProvider provider, String providerSubject) {
        try {
            OauthAccount account = jdbcTemplate.queryForObject(
                    FIND_BY_PROVIDER_AND_SUBJECT_SQL,
                    ROW_MAPPER,
                    provider.value(),
                    providerSubject);
            return Optional.ofNullable(account);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public List<OauthAccount> findAllByUserId(long userId) {
        return jdbcTemplate.query(FIND_ALL_BY_USER_ID_SQL, ROW_MAPPER, userId);
    }

    private static OauthAccount mapOauthAccount(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp lastLoginAt = resultSet.getTimestamp("last_login_at");
        return new OauthAccount(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                OauthProvider.from(resultSet.getString("provider")),
                resultSet.getString("provider_subject"),
                resultSet.getString("email"),
                resultSet.getBoolean("email_verified"),
                lastLoginAt == null ? null : lastLoginAt.toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
