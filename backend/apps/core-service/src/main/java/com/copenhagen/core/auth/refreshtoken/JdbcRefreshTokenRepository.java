package com.copenhagen.core.auth.refreshtoken;

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
public class JdbcRefreshTokenRepository implements RefreshTokenRepository {

    private static final String INSERT_SQL = """
            INSERT INTO refresh_token (user_id, token_hash, expires_at, created_at)
            VALUES (?, ?, ?, ?)
            RETURNING id
            """;

    private static final String FIND_BY_TOKEN_HASH_SQL = """
            SELECT id, user_id, token_hash, expires_at, created_at
            FROM refresh_token
            WHERE token_hash = ?
            """;

    private static final String DELETE_BY_TOKEN_HASH_SQL = """
            DELETE FROM refresh_token
            WHERE token_hash = ?
            """;

    private static final RowMapper<RefreshToken> ROW_MAPPER = JdbcRefreshTokenRepository::mapRefreshToken;

    private final JdbcTemplate jdbcTemplate;

    public JdbcRefreshTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long insert(long userId, String tokenHash, Instant expiresAt, Instant now) {
        return jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                userId,
                tokenHash,
                Timestamp.from(expiresAt),
                Timestamp.from(now));
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(FIND_BY_TOKEN_HASH_SQL, ROW_MAPPER, tokenHash));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public int deleteByTokenHash(String tokenHash) {
        return jdbcTemplate.update(DELETE_BY_TOKEN_HASH_SQL, tokenHash);
    }

    private static RefreshToken mapRefreshToken(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RefreshToken(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                resultSet.getString("token_hash"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
