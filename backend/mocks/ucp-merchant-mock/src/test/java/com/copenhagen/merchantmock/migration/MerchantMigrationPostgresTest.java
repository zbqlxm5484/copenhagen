package com.copenhagen.merchantmock.migration;

import java.util.List;

import javax.sql.DataSource;

import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.merchantmock.support.EmbeddedPostgresSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 마이그레이션 검증(Jira 294). 운영과 같은 SQL 파일을 진짜 PostgreSQL 에 적용한다.
 *
 * <p>이 모듈의 다른 테스트는 Spring 컨텍스트를 안 띄워 Flyway 가 한 번도 돌지 않는다. 빌드 초록이
 * SQL 정상을 뜻하지 않는 상태였고 그 구멍을 메우는 것이 이 클래스다.
 *
 * <p>두 경우를 모두 본다. 빈 DB 에 전체를 적용하는 경우와, 직전 공유 버전이 깔린 DB 에 새 마이그레이션을
 * 얹는 경우다. 후자가 배포 스모크 3단계(develop 데이터 위 이미지 교체)가 잡던 상황이다(DESIGN.md 25.5절).
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantMigrationPostgresTest {

    private static final List<String> TABLES =
            List.of("products", "checkout_sessions", "orders", "order_items", "webhook_deliveries");

    private EmbeddedPostgresSupport postgres;

    @BeforeAll
    void startPostgres() throws Exception {
        this.postgres = EmbeddedPostgresSupport.start();
    }

    @AfterAll
    void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    @DisplayName("빈 DB 에 V1~V7 를 전부 적용한다")
    void migrate_emptyDatabase_appliesAllVersions() {
        // given
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);

        // when
        int applied = postgres.flywayFor(dataSource, schema, null).migrate().migrationsExecuted;

        // then
        JdbcTemplate jdbc = postgres.jdbcFor(dataSource);
        assertEquals(7, applied);
        assertEquals("7", currentVersion(jdbc, schema));
        TABLES.forEach(table -> assertTrue(tableExists(jdbc, schema, table), table + " 가 없다"));
    }

    @Test
    @DisplayName("V3 까지 적용된 DB 위에 V4·V5·V6·V7 가 올라간다")
    void migrate_databaseAtV3_appliesRemainingVersions() {
        // given
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, "3").migrate();
        JdbcTemplate jdbc = postgres.jdbcFor(dataSource);
        assertEquals("3", currentVersion(jdbc, schema));
        assertEquals(5, seededProductCount(jdbc));

        // when
        int applied = postgres.flywayFor(dataSource, schema, null).migrate().migrationsExecuted;

        // then
        assertEquals(4, applied);
        assertEquals("7", currentVersion(jdbc, schema));
        assertTrue(tableExists(jdbc, schema, "order_items"));
        assertEquals(5, seededProductCount(jdbc), "기존 시드 데이터가 살아 있어야 한다");
    }

    @Test
    @DisplayName("V4 가 쓰지 않는 checkout_sessions 컬럼을 걷어낸다")
    void migrate_afterV4_dropsUnusedCheckoutColumns() {
        // given
        Migrated migrated = migrated();

        // when & then
        List.of("buyer_ref", "completion_key", "completion_request_hash", "checkout_digest",
                        "signing_kid", "processor_transaction_id", "payment_error_code")
                .forEach(column -> assertFalse(columnExists(migrated, column),
                        column + " 은 V4 에서 지웠어야 한다"));
        assertTrue(columnExists(migrated, "canonical_checkout"));
        assertTrue(columnExists(migrated, "completion_response_json"));
    }

    @Test
    @DisplayName("만료 스윕 워커가 쓰는 인덱스가 있다")
    void migrate_always_createsExpirySweepIndex() {
        // given
        Migrated migrated = migrated();

        // when
        List<String> indexes = migrated.jdbc().queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = 'checkout_sessions'",
                String.class, migrated.schema());

        // then
        assertTrue(indexes.contains("ix_checkout_expiry"), indexes.toString());
        assertTrue(indexes.contains("ix_checkout_payment"), indexes.toString());
    }

    @Test
    @DisplayName("상태 CHECK 제약이 UCP 여섯 값만 받는다")
    void checkoutStatusCheck_unknownValue_isRejected() {
        // given
        JdbcTemplate jdbc = migrated().jdbc();

        // when & then
        jdbc.update("INSERT INTO checkout_sessions (id, status) VALUES (?, ?)", "ok-1", "READY_FOR_COMPLETE");
        assertThrows(RuntimeException.class,
                () -> jdbc.update("INSERT INTO checkout_sessions (id, status) VALUES (?, ?)", "bad-1", "EXPIRED"));
    }

    @Test
    @DisplayName("주문 상세는 주문과 상품을 모두 참조한다")
    void orderItems_missingParent_isRejected() {
        // given
        JdbcTemplate jdbc = migrated().jdbc();

        // when & then
        assertThrows(RuntimeException.class, () -> jdbc.update("""
                INSERT INTO order_items (id, order_id, line_no, product_id, sku, title, unit_amount,
                                         quantity, line_amount)
                VALUES (?, ?, 1, ?, 'sku-1', '러닝화 A', 89000, 1, 89000)
                """, "item-1", "11111111-1111-4111-8111-000000000000",
                "11111111-1111-4111-8111-111111111111"));
    }

    @Test
    @DisplayName("V5 까지 적용된 DB 위에 V6·V7 이 올라가고 기존 체크아웃 행이 살아 있다")
    void migrate_databaseAtV5_addsLineSkusColumn() {
        // given
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, "5").migrate();
        JdbcTemplate jdbc = postgres.jdbcFor(dataSource);
        jdbc.update("INSERT INTO checkout_sessions (id, status) VALUES (?, ?)",
                "before-v6", "READY_FOR_COMPLETE");

        // when
        int applied = postgres.flywayFor(dataSource, schema, null).migrate().migrationsExecuted;

        // then
        assertEquals(2, applied);
        assertEquals("7", currentVersion(jdbc, schema));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM checkout_sessions WHERE id = 'before-v6'", Integer.class));
        assertNull(jdbc.queryForObject(
                "SELECT line_skus_json::text FROM checkout_sessions WHERE id = 'before-v6'", String.class),
                "V6 이전 행은 비어 있어야 한다");
    }

    @Test
    @DisplayName("line_skus_json 은 jsonb 이고 객체를 그대로 담는다")
    void lineSkusJson_jsonObject_roundTrips() {
        // given
        JdbcTemplate jdbc = migrated().jdbc();

        // when
        jdbc.update("""
                INSERT INTO checkout_sessions (id, status, line_skus_json)
                VALUES (?, 'READY_FOR_COMPLETE', ?::jsonb)
                """, "skus-1", "{\"line-1\":\"product-101-270\",\"line-2\":\"product-104-single\"}");

        // then
        assertEquals("product-101-270", jdbc.queryForObject("""
                SELECT line_skus_json ->> 'line-1' FROM checkout_sessions WHERE id = 'skus-1'
                """, String.class));
        assertThrows(RuntimeException.class, () -> jdbc.update("""
                INSERT INTO checkout_sessions (id, status, line_skus_json)
                VALUES (?, 'READY_FOR_COMPLETE', ?::jsonb)
                """, "skus-2", "{깨진 JSON"));
    }

    @Test
    @DisplayName("V6 까지 적용된 DB 의 REQUESTED 결제 행이 V7 에서 PROCESSING 으로 옮겨진다")
    void migrate_databaseAtV6_movesRequestedPaymentsToProcessing() {
        // given
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, "6").migrate();
        JdbcTemplate jdbc = postgres.jdbcFor(dataSource);
        jdbc.update("INSERT INTO checkout_sessions (id, status, payment_status) VALUES (?, ?, ?)",
                "requested", "COMPLETE_IN_PROGRESS", "REQUESTED");
        jdbc.update("INSERT INTO checkout_sessions (id, status, payment_status) VALUES (?, ?, ?)",
                "success", "COMPLETED", "SUCCESS");
        jdbc.update("INSERT INTO checkout_sessions (id, status) VALUES (?, ?)", "none", "READY_FOR_COMPLETE");

        // when
        int applied = postgres.flywayFor(dataSource, schema, null).migrate().migrationsExecuted;

        // then
        assertEquals(1, applied);
        assertEquals("7", currentVersion(jdbc, schema));
        assertEquals("PROCESSING", paymentStatus(jdbc, "requested"), "옛 값은 뜻이 같은 PROCESSING 으로 옮긴다");
        assertEquals("SUCCESS", paymentStatus(jdbc, "success"), "다른 값은 건드리지 않는다");
        assertNull(paymentStatus(jdbc, "none"));
    }

    @Test
    @DisplayName("결제 상태 CHECK 가 공유 enum 다섯 값만 받고 REQUESTED 는 거절한다")
    void paymentStatusCheck_sharedEnumValues_acceptedAndRequestedRejected() {
        // given
        JdbcTemplate jdbc = migrated().jdbc();

        // when & then
        for (PaymentStatus status : PaymentStatus.values()) {
            jdbc.update("INSERT INTO checkout_sessions (id, status, payment_status) VALUES (?, ?, ?)",
                    "ok-" + status, "COMPLETE_IN_PROGRESS", status.name());
        }
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "INSERT INTO checkout_sessions (id, status, payment_status) VALUES (?, ?, ?)",
                "bad-1", "COMPLETE_IN_PROGRESS", "REQUESTED"));
    }

    private static String paymentStatus(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("SELECT payment_status FROM checkout_sessions WHERE id = ?", String.class, id);
    }

    /**
     * 최신까지 적용된 새 스키마 하나.
     *
     * <p>스키마를 같이 들고 다니는 이유는 information_schema 와 pg_indexes 가 DB 전체를 보기 때문이다.
     * 스키마로 거르지 않으면 다른 테스트가 만든 스키마의 테이블까지 세어 단언이 흔들린다.
     */
    private record Migrated(JdbcTemplate jdbc, String schema) {
    }

    private Migrated migrated() {
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        Flyway flyway = postgres.flywayFor(dataSource, schema, null);
        flyway.migrate();
        return new Migrated(postgres.jdbcFor(dataSource), schema);
    }

    private static String currentVersion(JdbcTemplate jdbc, String schema) {
        return jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class);
    }

    private static int seededProductCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM products", Integer.class);
    }

    private static boolean tableExists(JdbcTemplate jdbc, String schema, String table) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = ? AND table_name = ?
                """, Integer.class, schema, table) == 1;
    }

    private static boolean columnExists(Migrated migrated, String column) {
        return migrated.jdbc().queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = ? AND table_name = 'checkout_sessions' AND column_name = ?
                """, Integer.class, migrated.schema(), column) == 1;
    }
}
