package com.copenhagen.core.auth.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserRepository implements UserRepository {

    private static final String INSERT_SQL = """
            INSERT INTO users (public_id, name, status, email, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT id, public_id, name, status, email, email_verified, phone_number,
                deleted_at, pw_changed_at, last_login_at, created_at, updated_at
            FROM users
            WHERE id = ?
            """;

    private static final String FIND_BY_PUBLIC_ID_SQL = """
            SELECT id, public_id, name, status, email, email_verified, phone_number,
                deleted_at, pw_changed_at, last_login_at, created_at, updated_at
            FROM users
            WHERE public_id = ?
            """;

    private static final RowMapper<User> ROW_MAPPER = JdbcUserRepository::mapUser;

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long insert(UUID publicId, String name, UserStatus status, String email, Instant now) {
        return jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                publicId,
                name,
                status.name(),
                email,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    @Override
    public Optional<User> findById(long id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(FIND_BY_ID_SQL, ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<User> findByPublicId(UUID publicId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(FIND_BY_PUBLIC_ID_SQL, ROW_MAPPER, publicId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private static User mapUser(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp deletedAt = resultSet.getTimestamp("deleted_at");
        Timestamp pwChangedAt = resultSet.getTimestamp("pw_changed_at");
        Timestamp lastLoginAt = resultSet.getTimestamp("last_login_at");
        return new User(
                resultSet.getLong("id"),
                resultSet.getObject("public_id", UUID.class),
                resultSet.getString("name"),
                UserStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("email"),
                resultSet.getBoolean("email_verified"),
                resultSet.getString("phone_number"),
                deletedAt == null ? null : deletedAt.toInstant(),
                pwChangedAt == null ? null : pwChangedAt.toInstant(),
                lastLoginAt == null ? null : lastLoginAt.toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
