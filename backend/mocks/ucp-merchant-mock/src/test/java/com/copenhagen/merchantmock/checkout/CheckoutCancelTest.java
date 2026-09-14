package com.copenhagen.merchantmock.checkout;

import com.copenhagen.merchantmock.checkout.CheckoutMessages.CancelResult;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Checkout;
import com.copenhagen.merchantmock.checkout.CheckoutTestSupport.Harness;
import com.copenhagen.merchantmock.support.ApiException;
import org.junit.jupiter.api.Test;

import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.harness;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.runningShoesToSeoul;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 체크아웃 취소(Jira 182). 전이는 DESIGN.md 7.1절이 소유한다.
 *
 * <p>Jira 완료 조건 중 "취소된 체크아웃은 GET 에서 canceled 로 응답한다" 가 여기 있다.
 * 스냅샷을 건드리지 않고 응답에서만 덮는 방식이라 그 두 가지를 같이 단언한다.
 */
class CheckoutCancelTest {

    private static String cancelableId(Harness harness, String key) {
        return harness.service().create(runningShoesToSeoul(), key).id();
    }

    @Test
    void 취소하면_두_필드만_돌려준다() {
        Harness harness = harness();
        String id = cancelableId(harness, "checkout-create-1");

        CancelResult result = harness.service().cancel(id);

        assertEquals(id, result.id());
        assertEquals("canceled", result.status());
    }

    @Test
    void 취소된_체크아웃은_GET_에서_canceled_다() {
        Harness harness = harness();
        String id = cancelableId(harness, "checkout-create-1");
        harness.service().cancel(id);

        assertEquals("canceled", harness.service().get(id).status());
    }

    @Test
    void 취소해도_저장된_스냅샷_문자열은_그대로다() {
        Harness harness = harness();
        String id = cancelableId(harness, "checkout-create-1");
        String before = harness.repository().findById(id).orElseThrow().canonicalCheckout();

        harness.service().cancel(id);

        CheckoutSession after = harness.repository().findById(id).orElseThrow();
        assertEquals(before, after.canonicalCheckout());
        assertEquals(CheckoutStatus.CANCELED, after.status());
    }

    @Test
    void 재취소는_오류가_아니라_같은_응답을_재생한다() {
        Harness harness = harness();
        String id = cancelableId(harness, "checkout-create-1");

        CancelResult first = harness.service().cancel(id);
        CancelResult second = harness.service().cancel(id);

        assertEquals(first, second);
        assertEquals(1L, harness.repository().findById(id).orElseThrow().version());
    }

    @Test
    void incomplete_도_취소할_수_있다() {
        Harness harness = harness();
        Checkout created = harness.service().create(
                CheckoutTestSupport.request(null, CheckoutTestSupport.line("product-101", "product-101-270", 1)),
                "checkout-create-1");
        assertEquals("incomplete", created.status());

        assertEquals("canceled", harness.service().cancel(created.id()).status());
    }

    @Test
    void 완료된_체크아웃_취소는_409_다() {
        Harness harness = harness();
        String id = cancelableId(harness, "checkout-create-1");
        CheckoutSession current = harness.repository().findById(id).orElseThrow();
        harness.repository().put(current.withStatus(CheckoutStatus.COMPLETED));

        ApiException e = assertThrows(ApiException.class, () -> harness.service().cancel(id));

        assertEquals("CHECKOUT_NOT_MODIFIABLE", e.errorCode());
        assertEquals(409, e.status().value());
        assertEquals("completed", e.currentState());
    }

    @Test
    void 진행_중인_체크아웃_취소도_409_다() {
        Harness harness = harness();
        String id = cancelableId(harness, "checkout-create-1");
        CheckoutSession current = harness.repository().findById(id).orElseThrow();
        harness.repository().put(current.withStatus(CheckoutStatus.COMPLETE_IN_PROGRESS));

        ApiException e = assertThrows(ApiException.class, () -> harness.service().cancel(id));

        assertEquals("CHECKOUT_NOT_MODIFIABLE", e.errorCode());
        assertEquals("complete_in_progress", e.currentState());
    }

    @Test
    void 취소된_체크아웃은_수정도_거절한다() {
        Harness harness = harness();
        String id = cancelableId(harness, "checkout-create-1");
        harness.service().cancel(id);

        ApiException e = assertThrows(ApiException.class,
                () -> harness.service().update(id, runningShoesToSeoul()));

        assertEquals("CHECKOUT_NOT_MODIFIABLE", e.errorCode());
        assertEquals("canceled", e.currentState());
    }

    @Test
    void 없는_체크아웃_취소는_404_다() {
        ApiException e = assertThrows(ApiException.class, () -> harness().service().cancel("nope"));

        assertEquals("CHECKOUT_NOT_FOUND", e.errorCode());
        assertEquals(404, e.status().value());
    }
}
