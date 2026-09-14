package com.copenhagen.merchantmock.checkout;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import com.copenhagen.merchantmock.checkout.CheckoutTestSupport.Harness;
import org.junit.jupiter.api.Test;

import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.NOW;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.harness;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.line;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.request;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.runningShoesToSeoul;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 만료 스윕 워커(Jira 182). 전이는 DESIGN.md 7.1절이 소유한다.
 *
 * <p>체크아웃은 NOW 시각에 만들어지고 ready_for_complete 면 expires_at 이 NOW + 5분이다.
 * 워커의 시계를 그보다 뒤로 돌려 만료를 만든다.
 */
class CheckoutExpirySweeperTest {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private static CheckoutExpirySweeper sweeperAt(Harness harness, Duration afterNow, int batchSize) {
        Clock clock = Clock.fixed(NOW.plus(afterNow), SEOUL_ZONE);
        return new CheckoutExpirySweeper(harness.repository(), clock, batchSize);
    }

    @Test
    void 시한이_지난_ready_for_complete_를_canceled_로_닫는다() {
        Harness harness = harness();
        String id = harness.service().create(runningShoesToSeoul(), "checkout-create-1").id();

        sweeperAt(harness, Duration.ofMinutes(6), 100).sweep();

        assertEquals(CheckoutStatus.CANCELED, harness.repository().findById(id).orElseThrow().status());
        assertEquals("canceled", harness.service().get(id).status());
    }

    @Test
    void 아직_시한_안에_있으면_건드리지_않는다() {
        Harness harness = harness();
        String id = harness.service().create(runningShoesToSeoul(), "checkout-create-1").id();

        sweeperAt(harness, Duration.ofMinutes(4), 100).sweep();

        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, harness.repository().findById(id).orElseThrow().status());
    }

    @Test
    void incomplete_는_시한이_없어_스윕_대상이_아니다() {
        Harness harness = harness();
        String id = harness.service()
                .create(request(null, line("product-101", "product-101-270", 1)), "checkout-create-1").id();

        sweeperAt(harness, Duration.ofDays(1), 100).sweep();

        assertEquals(CheckoutStatus.INCOMPLETE, harness.repository().findById(id).orElseThrow().status());
    }

    @Test
    void 결제가_시작된_건은_집지_않는다() {
        Harness harness = harness();
        String id = harness.service().create(runningShoesToSeoul(), "checkout-create-1").id();
        CheckoutSession current = harness.repository().findById(id).orElseThrow();
        harness.repository().put(current.withStatus(CheckoutStatus.COMPLETE_IN_PROGRESS));

        sweeperAt(harness, Duration.ofMinutes(6), 100).sweep();

        assertEquals(CheckoutStatus.COMPLETE_IN_PROGRESS,
                harness.repository().findById(id).orElseThrow().status());
    }

    @Test
    void 이미_취소된_건은_다시_닫지_않는다() {
        Harness harness = harness();
        String id = harness.service().create(runningShoesToSeoul(), "checkout-create-1").id();
        harness.service().cancel(id);
        long versionAfterCancel = harness.repository().findById(id).orElseThrow().version();

        sweeperAt(harness, Duration.ofMinutes(6), 100).sweep();

        assertEquals(versionAfterCancel, harness.repository().findById(id).orElseThrow().version());
    }

    @Test
    void 배치_크기보다_많으면_나눠_닫는다() {
        Harness harness = harness();
        for (int i = 1; i <= 3; i++) {
            harness.service().create(runningShoesToSeoul(), "checkout-create-" + i);
        }

        CheckoutExpirySweeper sweeper = sweeperAt(harness, Duration.ofMinutes(6), 2);
        sweeper.sweep();

        assertEquals(1, readyCount(harness));
        sweeper.sweep();
        assertEquals(0, readyCount(harness));
    }

    @Test
    void 배치_크기가_0_이하면_기동에서_막는다() {
        Harness harness = harness();
        Clock clock = Clock.fixed(NOW, SEOUL_ZONE);

        assertThrows(IllegalArgumentException.class,
                () -> new CheckoutExpirySweeper(harness.repository(), clock, 0));
    }

    /** 시드 셋 중 아직 ready_for_complete 로 남은 건수. */
    private static int readyCount(Harness harness) {
        int count = 0;
        for (int i = 1; i <= 3; i++) {
            String id = CheckoutIds.fromIdempotencyKey("checkout-create-" + i);
            if (harness.repository().findById(id).orElseThrow().status() == CheckoutStatus.READY_FOR_COMPLETE) {
                count++;
            }
        }
        return count;
    }
}
