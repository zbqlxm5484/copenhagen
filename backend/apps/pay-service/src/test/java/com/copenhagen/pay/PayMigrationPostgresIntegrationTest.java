package com.copenhagen.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayMigrationPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final String MERCHANT_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000002";
    private static final String VALID_SESSION_ID = "00000000-0000-0000-0000-000000000003";

    private EmbeddedPostgres postgres;
    private Flyway flyway;
    private DataSource dataSource;
    private String schema;
    private JdbcTemplate jdbc;

    @BeforeAll
    void startPostgres() throws IOException {
        postgres = EmbeddedPostgres.builder()
                .setServerConfig("listen_addresses", "127.0.0.1")
                .start();
    }

    @AfterAll
    void stopPostgres() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void setUp() {
        schema = "pay_migration_" + UUID.randomUUID().toString().replace("-", "");
        dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:" + postgres.getPort()
                        + "/postgres?currentSchema=" + schema,
                "postgres",
                "postgres");
        flyway = configure(dataSource, schema, null);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void tearDown() {
        if (flyway != null) {
            flyway.clean();
        }
    }

    @Test
    void appliesEveryPayMigrationToAnEmptyDatabase() {
        assertEquals(9, flyway.migrate().migrationsExecuted);

        assertEquals(
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"),
                appliedVersions());
        assertTrue(tableExists("pay_idempotency_records"));
        assertTrue(columnExists("authorization_sessions", "display_snapshot"));
        assertTrue(columnExists("biometric_challenges", "challenge_value"));
        assertTrue(columnExists("pay_verified_checkout_evidence", "merchant_key_id"));
    }

    @Test
    void upgradesAnExistingPaySchemaToTheLatestVersion() {
        Flyway versionEight = configure(dataSource, schema, "8");
        assertEquals(8, versionEight.migrate().migrationsExecuted);
        assertEquals("8", currentVersion());
        assertFalse(tableExists("pay_idempotency_records"));

        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals("9", currentVersion());
        assertEquals(9, appliedVersions().size());
    }

    @Test
    void enforcesUniqueCheckAndForeignKeyConstraintsInPostgres() {
        flyway.migrate();
        insertMerchant(MERCHANT_ACCOUNT_ID, "merchant-a");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertMerchant("00000000-0000-0000-0000-000000000011", "merchant-a"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        """
                        INSERT INTO payment_methods
                            (id, user_id, provider, provider_token_ref, status, is_default)
                        VALUES (?, ?, 'PG_MOCK', 'provider-token-invalid-status', 'BROKEN', FALSE)
                        """,
                        "00000000-0000-0000-0000-000000000012",
                        USER_ID));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAuthorizationSession(
                        "00000000-0000-0000-0000-000000000013",
                        "00000000-0000-0000-0000-000000000014",
                        "00000000-0000-0000-0000-000000000099",
                        1000));

        jdbc.update(
                """
                INSERT INTO payment_methods
                    (id, user_id, provider, provider_token_ref, status, is_default)
                VALUES (?, ?, 'PG_MOCK', 'provider-token-valid', 'ACTIVE', FALSE)
                """,
                "00000000-0000-0000-0000-000000000015",
                USER_ID);
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAuthorizationSession(
                        VALID_SESSION_ID,
                        "00000000-0000-0000-0000-000000000016",
                        MERCHANT_ACCOUNT_ID,
                        -1));
    }

    private Flyway configure(DataSource dataSource, String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private List<String> appliedVersions() {
        return jdbc.queryForList(
                """
                SELECT version
                FROM flyway_schema_history
                WHERE success AND version IS NOT NULL
                ORDER BY installed_rank
                """,
                String.class);
    }

    private String currentVersion() {
        return jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class);
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL",
                Boolean.class,
                tableName));
    }

    private boolean columnExists(String tableName, String columnName) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = ?
                      AND column_name = ?
                )
                """,
                Boolean.class,
                tableName,
                columnName));
    }

    private void insertMerchant(String id, String merchantId) {
        jdbc.update(
                """
                INSERT INTO merchant_accounts (id, merchant_id, display_name, auth_type, status)
                VALUES (?, ?, 'Test Merchant', 'TOKEN', 'ACTIVE')
                """,
                id,
                merchantId);
    }

    private void insertAuthorizationSession(
            String sessionId,
            String purchaseRequestId,
            String merchantAccountId,
            long amountMinor) {
        jdbc.update(
                """
                INSERT INTO authorization_sessions
                    (id, purchase_request_id, user_id, merchant_account_id,
                     external_checkout_id, checkout_digest, display_snapshot,
                     amount_minor, currency, selected_payment_method_id, status,
                     idempotency_key, request_hash, required_action, expires_at,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'checkout-constraint', ?, '{}'::jsonb, ?, 'KRW',
                        NULL, 'PAYMENT_METHOD_REQUIRED', ?, ?, 'BIOMETRIC', ?, ?, ?, 0)
                """,
                sessionId,
                purchaseRequestId,
                USER_ID,
                merchantAccountId,
                "a".repeat(64),
                amountMinor,
                "approval-" + sessionId,
                "b".repeat(64),
                java.sql.Timestamp.from(NOW.plusSeconds(300)),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
    }
}
