package com.copenhagen.merchantmock.checkout;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JdbcCheckoutRepository 의 SQL 검증(Jira 294). 인메모리 저장소가 흉내 내던 것을 진짜 DB 로 확인한다.
 *
 * <p>{@link InMemoryCheckoutRepository} 는 조건부 UPDATE 를 자바로 흉내 낸다. 그것이 통과한다고
 * SQL 이 맞는 것은 아니다. 특히 182 의 {@code expireReady} 는 서브쿼리에 ORDER BY 와 LIMIT 이 들어가
 * 자바 필터와 모양이 전혀 다르다.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcCheckoutRepositoryPostgresTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 9, 14, 13, 0, 0, 0, ZoneOffset.ofHours(9));

    private EmbeddedPostgresSupport postgres;
    private JdbcCheckoutRepository repository;
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
        this.repository = new JdbcCheckoutRepository(jdbc);
    }

    @Test
    @DisplayName("넣고 다시 읽으면 값이 그대로다")
    void insertAndFindById_newSession_roundTripsAllColumns() {
        // given
        CheckoutSession session = ready("checkout-1", NOW.plusMinutes(5));

        // when
        boolean inserted = repository.insert(session);

        // then
        assertTrue(inserted);
        CheckoutSession stored = repository.findById("checkout-1").orElseThrow();
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, stored.status());
        assertEquals("KRW", stored.currency());
        assertEquals(92000L, stored.totalAmount());
        assertEquals(session.canonicalCheckout(), stored.canonicalCheckout());
        assertEquals(0L, stored.version());
        assertEquals(NOW.plusMinutes(5).toInstant(), stored.expiresAt().toInstant());
    }

    @Test
    @DisplayName("jsonb 컬럼이 문자열로 왕복한다")
    void insertAndFindById_jsonColumns_roundTripAsText() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));

        // when
        CheckoutSession stored = repository.findById("checkout-1").orElseThrow();

        // then
        assertTrue(stored.fulfillmentAddressJson().contains("홍길동"));
        assertTrue(stored.messagesJson().contains("ADDRESS_REQUIRED"));
    }

    @Test
    @DisplayName("같은 id 를 다시 넣으면 false 다")
    void insert_duplicateId_returnsFalse() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));

        // when
        boolean second = repository.insert(ready("checkout-1", NOW.plusMinutes(5)));

        // then
        assertFalse(second);
        assertEquals(1, count());
    }

    @Test
    @DisplayName("없는 id 조회는 빈 값이다")
    void findById_unknownId_returnsEmpty() {
        // when & then
        assertTrue(repository.findById("nope").isEmpty());
    }

    @Test
    @DisplayName("버전이 맞아야 수정되고 버전이 오른다")
    void update_matchingVersion_appliesAndBumpsVersion() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));

        // when
        boolean updated = repository.update(ready("checkout-1", NOW.plusMinutes(10)), 0L);

        // then
        assertTrue(updated);
        assertEquals(1L, repository.findById("checkout-1").orElseThrow().version());
    }

    @Test
    @DisplayName("버전이 어긋나면 아무것도 바뀌지 않는다")
    void update_staleVersion_changesNothing() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));
        repository.update(ready("checkout-1", NOW.plusMinutes(10)), 0L);

        // when
        boolean updated = repository.update(ready("checkout-1", NOW.plusMinutes(30)), 0L);

        // then
        assertFalse(updated);
        assertEquals(1L, repository.findById("checkout-1").orElseThrow().version());
    }

    @Test
    @DisplayName("취소는 세 상태에서만 되고 나머지는 false 다")
    void cancel_byStatus_onlyCancelableStatesSucceed() {
        // given
        List<CheckoutStatus> cancelable =
                List.of(CheckoutStatus.INCOMPLETE, CheckoutStatus.REQUIRES_ESCALATION,
                        CheckoutStatus.READY_FOR_COMPLETE);
        List<CheckoutStatus> rejected =
                List.of(CheckoutStatus.COMPLETE_IN_PROGRESS, CheckoutStatus.COMPLETED, CheckoutStatus.CANCELED);

        // when & then
        for (CheckoutStatus status : cancelable) {
            String id = "ok-" + status;
            repository.insert(withStatus(id, status));
            assertTrue(repository.cancel(id), status + " 는 취소할 수 있어야 한다");
            assertEquals(CheckoutStatus.CANCELED, repository.findById(id).orElseThrow().status());
        }
        for (CheckoutStatus status : rejected) {
            String id = "no-" + status;
            repository.insert(withStatus(id, status));
            assertFalse(repository.cancel(id), status + " 는 취소되면 안 된다");
            assertEquals(status, repository.findById(id).orElseThrow().status());
        }
    }

    @Test
    @DisplayName("만료 스윕이 시한 지난 ready_for_complete 만 닫는다")
    void expireReady_mixedRows_closesOnlyExpiredReadyCheckouts() {
        // given
        // 대상이 아닌 상태에도 지난 expires_at 을 넣는다. 그래야 시각 조건이 아니라 status 조건이 시험된다.
        repository.insert(ready("expired", NOW.minusMinutes(1)));
        repository.insert(ready("alive", NOW.plusMinutes(5)));
        repository.insert(expiredWithStatus("incomplete", CheckoutStatus.INCOMPLETE));
        repository.insert(expiredWithStatus("escalation", CheckoutStatus.REQUIRES_ESCALATION));
        repository.insert(expiredWithStatus("in-progress", CheckoutStatus.COMPLETE_IN_PROGRESS));
        repository.insert(expiredWithStatus("completed", CheckoutStatus.COMPLETED));
        repository.insert(expiredWithStatus("canceled", CheckoutStatus.CANCELED));

        // when
        int closed = repository.expireReady(NOW, 100);

        // then
        assertEquals(1, closed);
        assertEquals(CheckoutStatus.CANCELED, repository.findById("expired").orElseThrow().status());
        assertEquals(1L, repository.findById("expired").orElseThrow().version());
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, repository.findById("alive").orElseThrow().status());
        assertEquals(CheckoutStatus.INCOMPLETE, repository.findById("incomplete").orElseThrow().status());
        assertEquals(CheckoutStatus.REQUIRES_ESCALATION, repository.findById("escalation").orElseThrow().status());
        assertEquals(CheckoutStatus.COMPLETE_IN_PROGRESS,
                repository.findById("in-progress").orElseThrow().status());
        assertEquals(CheckoutStatus.COMPLETED, repository.findById("completed").orElseThrow().status());
        assertEquals(0L, repository.findById("canceled").orElseThrow().version(), "이미 취소된 건은 다시 안 센다");
    }

    @Test
    @DisplayName("만료 스윕이 limit 만큼만 닫고 오래된 것부터 집는다")
    void expireReady_moreThanLimit_closesOldestFirst() {
        // given
        // 삽입 순서와 만료 순서를 일부러 어긋나게 둔다. ORDER BY 가 빠지면 물리 순서로 집혀 실패한다.
        repository.insert(ready("newest", NOW.minusMinutes(1)));
        repository.insert(ready("second", NOW.minusMinutes(10)));
        repository.insert(ready("third", NOW.minusMinutes(20)));
        repository.insert(ready("oldest", NOW.minusMinutes(30)));

        // when
        int closed = repository.expireReady(NOW, 2);

        // then
        assertEquals(2, closed);
        assertEquals(CheckoutStatus.CANCELED, repository.findById("oldest").orElseThrow().status());
        assertEquals(CheckoutStatus.CANCELED, repository.findById("third").orElseThrow().status());
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, repository.findById("second").orElseThrow().status());
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, repository.findById("newest").orElseThrow().status());
    }

    @Test
    @DisplayName("만료 스윕은 expires_at 이 없는 행을 건드리지 않는다")
    void expireReady_nullExpiresAt_leavesRowUntouched() {
        // given
        repository.insert(ready("no-expiry", null));

        // when
        int closed = repository.expireReady(NOW, 100);

        // then
        assertEquals(0, closed);
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, repository.findById("no-expiry").orElseThrow().status());
    }

    @Test
    @DisplayName("만료 스윕이 쓰는 인덱스를 실행 계획이 탄다")
    void expireReady_query_usesExpiryIndex() {
        // given
        jdbc.execute("SET enable_seqscan = off");

        // when
        List<String> plan = jdbc.queryForList("""
                EXPLAIN SELECT id FROM checkout_sessions
                 WHERE status = 'READY_FOR_COMPLETE' AND expires_at < ?
                 ORDER BY expires_at LIMIT 100
                """, String.class, NOW);

        // then
        String rendered = String.join(System.lineSeparator(), plan);
        assertTrue(rendered.contains("ix_checkout_expiry"), rendered);
    }

    @Test
    @DisplayName("동시에 취소하면 한 요청만 행을 바꾼다")
    void cancel_concurrentRequests_onlyOneWins() throws Exception {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Boolean>> tasks = IntStream.range(0, threads)
                .mapToObj(i -> (Callable<Boolean>) () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return repository.cancel("checkout-1");
                })
                .toList();

        // when
        List<Future<Boolean>> results = pool.invokeAll(tasks);
        pool.shutdown();

        // then
        int winners = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                winners++;
            }
        }
        assertEquals(1, winners, "조건부 UPDATE 라 한 요청만 1행을 바꿔야 한다");
        assertEquals(1L, repository.findById("checkout-1").orElseThrow().version());
    }

    private static CheckoutSession ready(String id, OffsetDateTime expiresAt) {
        return new CheckoutSession(
                id,
                CheckoutStatus.READY_FOR_COMPLETE,
                "KRW",
                92000L,
                "{\"recipient\":\"홍길동\",\"address_line1\":\"서울특별시 강남구 테헤란로 1\"}",
                "[{\"code\":\"ADDRESS_REQUIRED\",\"severity\":\"error\"}]",
                "{\"id\":\"" + id + "\",\"status\":\"ready_for_complete\"}",
                expiresAt == null ? null : expiresAt.truncatedTo(ChronoUnit.SECONDS),
                0L);
    }

    /** 시한이 지난 채로 다른 상태에 있는 행. status 조건을 시험하는 데 쓴다. */
    private static CheckoutSession expiredWithStatus(String id, CheckoutStatus status) {
        CheckoutSession base = ready(id, NOW.minusMinutes(5));
        return new CheckoutSession(base.id(), status, base.currency(), base.totalAmount(),
                base.fulfillmentAddressJson(), base.messagesJson(), base.canonicalCheckout(),
                base.expiresAt(), base.version());
    }

    private static CheckoutSession withStatus(String id, CheckoutStatus status) {
        CheckoutSession base = ready(id, null);
        return new CheckoutSession(base.id(), status, base.currency(), base.totalAmount(),
                base.fulfillmentAddressJson(), base.messagesJson(), base.canonicalCheckout(),
                base.expiresAt(), base.version());
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM checkout_sessions", Integer.class);
    }
}
