package com.copenhagen.merchantmock.webhook;

import java.time.OffsetDateTime;
import java.util.List;

import javax.sql.DataSource;

import com.copenhagen.merchantmock.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JdbcWebhookRepository 의 SQL 검증(Jira 181, 294 방식). 두 UPDATE 의 {@code status = 'PENDING'} 조건과
 * jsonb 왕복이 핵심이다.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcWebhookRepositoryPostgresTest {

    private static final OffsetDateTime NOW = WebhookTestSupport.NOW_SEOUL;
    private static final WebhookDelivery.Signed SIGNED =
            new WebhookDelivery.Signed("sig1=(...);created=1;keyid=\"k\"", "sig1=:abc:", "sha-256=:d:");

    private EmbeddedPostgresSupport postgres;
    private JdbcWebhookRepository repository;
    private JdbcTemplate jdbc;

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

    @BeforeEach
    void setUp() {
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, null).migrate();
        this.jdbc = postgres.jdbcFor(dataSource);
        this.repository = new JdbcWebhookRepository(jdbc);
        jdbc.update("INSERT INTO checkout_sessions (id, status) VALUES (?, 'COMPLETED')",
                WebhookTestSupport.CHECKOUT_ID);
        jdbc.update("""
                INSERT INTO orders (id, checkout_session_id, status, total_amount, currency, raw_order_json)
                VALUES (?, ?, 'CREATED', 92000, 'KRW', ?::jsonb)
                """, WebhookTestSupport.ORDER_ID, WebhookTestSupport.CHECKOUT_ID, WebhookTestSupport.RAW_ORDER_JSON);
    }

    @Test
    @DisplayName("넣고 다시 읽으면 값이 그대로고 페이로드는 두 번 읽어도 같은 바이트다")
    void insertAndFindById_newRow_roundTripsAndPayloadIsStable() {
        // given
        WebhookDelivery delivery = WebhookTestSupport.pendingCreated("webhook-1");

        // when
        repository.insert(delivery);
        WebhookDelivery first = repository.findById("webhook-1").orElseThrow();
        WebhookDelivery second = repository.findById("webhook-1").orElseThrow();

        // then
        assertEquals(WebhookTestSupport.ORDER_ID, first.orderId());
        assertEquals(WebhookEventType.ORDER_CREATED, first.eventType());
        assertEquals(WebhookStatus.PENDING, first.status());
        assertEquals(0, first.attemptCount());
        assertNull(first.signed());
        assertNull(first.responseStatus());
        assertEquals(NOW.toInstant(), first.nextAttemptAt().toInstant());
        assertTrue(first.payloadJson().contains("\"order\""));
        assertEquals(first.payloadJson(), second.payloadJson(), "재전송은 같은 바이트를 보낸다(불변 조건 8번)");
    }

    @Test
    @DisplayName("전달 표시는 PENDING 행에만 되고 두 번째는 false 다")
    void markDelivered_pendingRow_closesOnce() {
        // given
        repository.insert(WebhookTestSupport.pendingCreated("webhook-1"));

        // when
        boolean first = repository.markDelivered("webhook-1", 204, SIGNED);
        boolean again = repository.markDelivered("webhook-1", 200, SIGNED);

        // then
        assertTrue(first);
        assertFalse(again);
        WebhookDelivery stored = repository.findById("webhook-1").orElseThrow();
        assertEquals(WebhookStatus.DELIVERED, stored.status());
        assertEquals(204, stored.responseStatus());
        assertEquals(1, stored.attemptCount());
        assertNull(stored.nextAttemptAt());
        assertEquals(SIGNED, stored.signed());
    }

    @Test
    @DisplayName("재시도 표시는 횟수를 올리고 다음 시각을 적되 PENDING 을 유지한다")
    void markRetry_pendingRow_incrementsAndKeepsPending() {
        // given
        repository.insert(WebhookTestSupport.pendingCreated("webhook-1"));

        // when
        boolean first = repository.markRetry("webhook-1", 503, NOW.plusSeconds(10), SIGNED);
        boolean second = repository.markRetry("webhook-1", null, NOW.plusSeconds(30), SIGNED);

        // then
        assertTrue(first);
        assertTrue(second);
        WebhookDelivery stored = repository.findById("webhook-1").orElseThrow();
        assertEquals(WebhookStatus.PENDING, stored.status());
        assertEquals(2, stored.attemptCount());
        assertNull(stored.responseStatus(), "연결 실패는 응답 코드가 없다");
        assertEquals(NOW.plusSeconds(30).toInstant(), stored.nextAttemptAt().toInstant());
    }

    @Test
    @DisplayName("전달된 행에 재시도 표시를 하면 false 다")
    void markRetry_deliveredRow_returnsFalse() {
        // given
        repository.insert(WebhookTestSupport.pendingCreated("webhook-1"));
        repository.markDelivered("webhook-1", 204, SIGNED);

        // when & then
        assertFalse(repository.markRetry("webhook-1", 500, NOW.plusSeconds(10), SIGNED));
        assertEquals(WebhookStatus.DELIVERED, repository.findById("webhook-1").orElseThrow().status());
    }

    @Test
    @DisplayName("주문별 이력은 생성 순서다")
    void findByOrderId_multipleRows_orderedByCreation() {
        // given
        repository.insert(WebhookTestSupport.pendingCreated("webhook-a"));
        repository.insert(new WebhookDelivery("webhook-b", WebhookTestSupport.ORDER_ID,
                WebhookEventType.ORDER_UPDATED, "{\"order\":{\"id\":\"order-1\"}}", null, WebhookStatus.PENDING,
                0, NOW.plusMinutes(1), null, NOW.plusMinutes(1)));

        // when
        List<WebhookDelivery> rows = repository.findByOrderId(WebhookTestSupport.ORDER_ID);

        // then
        assertEquals(List.of("webhook-a", "webhook-b"), rows.stream().map(WebhookDelivery::webhookId).toList());
        assertNotEquals(rows.get(0).eventType(), rows.get(1).eventType());
    }

    @Test
    @DisplayName("없는 주문이나 모르는 이벤트 종류는 DB 가 거절한다")
    void insert_constraintViolations_rejected() {
        // when & then
        assertThrows(RuntimeException.class, () -> repository.insert(WebhookDelivery.pending(
                "webhook-x", "no-such-order", WebhookEventType.ORDER_CREATED, "{}", NOW)));
        assertThrows(RuntimeException.class, () -> jdbc.update("""
                INSERT INTO webhook_deliveries (webhook_id, order_id, event_type, payload_json)
                VALUES ('webhook-y', ?, 'order.deleted', '{}'::jsonb)
                """, WebhookTestSupport.ORDER_ID));
    }

    @Test
    @DisplayName("재전송 워커가 쓰는 인덱스를 실행 계획이 탄다")
    void pendingScan_query_usesPendingIndex() {
        // given
        jdbc.execute("SET enable_seqscan = off");

        // when
        List<String> plan = jdbc.queryForList("""
                EXPLAIN SELECT webhook_id FROM webhook_deliveries
                 WHERE status = 'PENDING' AND next_attempt_at <= ?
                 ORDER BY next_attempt_at LIMIT 100
                """, String.class, NOW);

        // then
        assertTrue(String.join(System.lineSeparator(), plan).contains("ix_webhook_pending"), plan.toString());
    }
}
