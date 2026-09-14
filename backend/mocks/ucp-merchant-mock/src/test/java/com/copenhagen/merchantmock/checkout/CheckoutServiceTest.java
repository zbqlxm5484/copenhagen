package com.copenhagen.merchantmock.checkout;

import java.util.List;
import java.util.stream.Collectors;

import com.copenhagen.merchantmock.checkout.CheckoutMessages.Checkout;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Message;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Total;
import com.copenhagen.merchantmock.checkout.CheckoutTestSupport.Harness;
import com.copenhagen.merchantmock.scenario.Scenario;
import com.copenhagen.merchantmock.support.ApiException;
import org.junit.jupiter.api.Test;

import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.EXPECTED_EXPIRES_AT;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.SEOUL;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.harness;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.line;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.request;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.runningShoesToSeoul;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 체크아웃 생성·조회·수정과 INCOMPLETE 판정(Jira 172). DB 없이 시드와 인메모리 저장소로 돌린다.
 *
 * <p>Jira 172 완료 조건 셋이 여기 있다. 배송지 없이 만들면 incomplete + ADDRESS_REQUIRED,
 * 배송지를 PUT 하면 ready_for_complete, 가격 변경 시나리오에서 CHECKOUT_PRICE_CHANGED.
 */
class CheckoutServiceTest {

    private static List<String> codes(Checkout checkout) {
        return checkout.messages() == null
                ? List.of()
                : checkout.messages().stream().map(Message::code).collect(Collectors.toList());
    }

    private static long total(Checkout checkout, String type) {
        return checkout.totals().stream().filter(t -> type.equals(t.type())).mapToLong(Total::amount).sum();
    }

    @Test
    void 조건이_다_차면_ready_for_complete_이고_금액을_확정한다() {
        Checkout checkout = harness().service().create(runningShoesToSeoul(), "checkout-create-1");

        assertEquals("ready_for_complete", checkout.status());
        assertEquals("KRW", checkout.currency());
        assertEquals(1, checkout.lineItems().size());
        assertEquals("line-1", checkout.lineItems().get(0).id());
        assertEquals("product-101", checkout.lineItems().get(0).item().id());
        assertEquals("러닝화 A", checkout.lineItems().get(0).item().title());
        assertEquals(89000L, checkout.lineItems().get(0).item().price());
        assertEquals("available", checkout.fulfillment().status());
        assertEquals(3000L, checkout.fulfillment().amount());
        assertEquals(89000L, total(checkout, "subtotal"));
        assertEquals(3000L, total(checkout, "fulfillment"));
        assertEquals(92000L, total(checkout, "total"));
        assertNull(checkout.messages(), "문제가 없으면 messages 키를 생략한다");
        assertEquals(EXPECTED_EXPIRES_AT, checkout.expiresAt());
    }

    @Test
    void 배송지가_없으면_incomplete_와_ADDRESS_REQUIRED_다() {
        Checkout checkout = harness().service().create(
                request(null, line("product-101", "product-101-270", 1)), "checkout-create-2");

        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("ADDRESS_REQUIRED"), codes(checkout));
        assertEquals("fulfillment.destination", checkout.messages().get(0).target());
        assertNull(checkout.fulfillment(), "배송지가 없으면 fulfillment 키를 생략한다");
        assertEquals(89000L, total(checkout, "total"), "배송비를 모르니 총액은 소계와 같다");
        assertNull(checkout.expiresAt(), "서명 전이라 만료 시각이 없다");
    }

    @Test
    void 옵션이_여럿인데_고르지_않으면_incomplete_다() {
        Checkout checkout = harness().service().create(
                request(SEOUL, line("product-101", null, 1)), "checkout-create-3");

        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("CHECKOUT_INCOMPLETE"), codes(checkout));
        assertEquals("line_items[0].option_value_ids", checkout.messages().get(0).target());
    }

    @Test
    void 옵션이_하나뿐인_상품은_생략해도_된다() {
        Checkout checkout = harness().service().create(
                request(SEOUL, line("product-104", null, 2)), "checkout-create-4");

        assertEquals("ready_for_complete", checkout.status());
        assertEquals(27000L * 2 + 3000L, total(checkout, "total"));
    }

    @Test
    void 옵션_추가금이_단가에_붙는다() {
        Checkout checkout = harness().service().create(
                request(SEOUL, line("product-102", "product-102-xl-black", 1)), "checkout-create-5");

        assertEquals(134000L, checkout.lineItems().get(0).item().price(), "129000 + XL 추가금 5000");
    }

    @Test
    void 상품에_없는_옵션이면_incomplete_다() {
        Checkout checkout = harness().service().create(
                request(SEOUL, line("product-101", "product-102-m-black", 1)), "checkout-create-6");

        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("CHECKOUT_INCOMPLETE"), codes(checkout));
    }

    @Test
    void 없는_상품이면_체크아웃을_만들지_않고_404_다() {
        Harness h = harness();

        ApiException thrown = assertThrows(ApiException.class, () -> h.service().create(
                request(SEOUL, line("product-999", null, 1)), "checkout-create-7"));

        assertEquals("PRODUCT_NOT_FOUND", thrown.errorCode());
        assertEquals(0, h.repository().size(), "계산서 자체를 만들지 않는다");
    }

    @Test
    void 단종_상품도_404_다() {
        ApiException thrown = assertThrows(ApiException.class, () -> harness().service().create(
                request(SEOUL, line("product-105", "product-105-navy", 1)), "checkout-create-8"));

        assertEquals("PRODUCT_NOT_FOUND", thrown.errorCode());
    }

    @Test
    void 재고가_0인_옵션은_CHECKOUT_STOCK_INSUFFICIENT_다() {
        Checkout checkout = harness().service().create(
                request(SEOUL, line("product-101", "product-101-280", 1)), "checkout-create-9");

        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("CHECKOUT_STOCK_INSUFFICIENT"), codes(checkout));
    }

    @Test
    void 수량이_재고를_넘으면_CHECKOUT_STOCK_INSUFFICIENT_다() {
        Checkout checkout = harness().service().create(
                request(SEOUL, line("product-101", "product-101-270", 8)), "checkout-create-10");

        assertEquals(List.of("CHECKOUT_STOCK_INSUFFICIENT"), codes(checkout), "270 사이즈 재고는 7");
    }

    @Test
    void 수량이_0이면_incomplete_다() {
        Checkout checkout = harness().service().create(
                request(SEOUL, line("product-101", "product-101-270", 0)), "checkout-create-11");

        assertEquals("incomplete", checkout.status());
        assertTrue(codes(checkout).contains("CHECKOUT_INCOMPLETE"));
    }

    @Test
    void 시나리오_OUT_OF_STOCK_은_재고가_있어도_품절이다() {
        Checkout checkout = harness(Scenario.OUT_OF_STOCK).service().create(
                runningShoesToSeoul(), "checkout-create-12");

        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("CHECKOUT_STOCK_INSUFFICIENT"), codes(checkout));
    }

    @Test
    void 시나리오_ADDRESS_NOT_SERVICEABLE_은_배송_불가다() {
        Checkout checkout = harness(Scenario.ADDRESS_NOT_SERVICEABLE).service().create(
                runningShoesToSeoul(), "checkout-create-13");

        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("ADDRESS_NOT_SERVICEABLE"), codes(checkout));
        assertEquals("unavailable", checkout.fulfillment().status());
        assertNull(checkout.fulfillment().amount(), "배송 못 하는데 배송비를 매기지 않는다");
        assertEquals(89000L, total(checkout, "total"));
    }

    @Test
    void 시나리오_REQUIRES_ESCALATION_은_requires_escalation_이다() {
        Checkout checkout = harness(Scenario.REQUIRES_ESCALATION).service().create(
                runningShoesToSeoul(), "checkout-create-14");

        assertEquals("requires_escalation", checkout.status());
        assertEquals(List.of("MERCHANT_HANDOFF_REQUIRED"), codes(checkout));
        assertNull(checkout.expiresAt());
    }

    @Test
    void 시나리오_PRICE_CHANGED_는_단가에_1000원을_더한다() {
        Checkout checkout = harness(Scenario.PRICE_CHANGED).service().create(
                runningShoesToSeoul(), "checkout-create-15");

        assertEquals(90000L, checkout.lineItems().get(0).item().price());
        assertEquals("ready_for_complete", checkout.status(), "생성 시점에는 비교 대상이 없어 경고가 없다");
    }

    @Test
    void 같은_멱등_키로_다시_만들면_같은_체크아웃을_돌려준다() {
        Harness h = harness();

        Checkout first = h.service().create(runningShoesToSeoul(), "checkout-create-16");
        Checkout second = h.service().create(runningShoesToSeoul(), "checkout-create-16");

        assertEquals(first.id(), second.id());
        assertEquals(first, second);
        assertEquals(1, h.repository().size());
    }

    @Test
    void 멱등_키가_다르면_다른_체크아웃이다() {
        Harness h = harness();

        Checkout first = h.service().create(runningShoesToSeoul(), "checkout-create-17");
        Checkout second = h.service().create(runningShoesToSeoul(), "checkout-create-18");
        Checkout third = h.service().create(runningShoesToSeoul(), null);

        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.id(), third.id());
        assertEquals(3, h.repository().size());
    }

    @Test
    void 조회는_저장된_스냅샷을_그대로_돌려준다() {
        Harness h = harness();
        Checkout created = h.service().create(runningShoesToSeoul(), "checkout-create-19");

        Checkout fetched = h.service().get(created.id());

        assertEquals(created, fetched);
    }

    @Test
    void 없는_체크아웃_조회는_CHECKOUT_NOT_FOUND_다() {
        ApiException thrown = assertThrows(ApiException.class, () -> harness().service().get("nope"));

        assertEquals("CHECKOUT_NOT_FOUND", thrown.errorCode());
        assertEquals(404, thrown.status().value());
    }

    @Test
    void 배송지를_PUT_하면_incomplete_가_ready_for_complete_로_간다() {
        Harness h = harness();
        Checkout created = h.service().create(
                request(null, line("product-101", "product-101-270", 1)), "checkout-create-20");
        assertEquals("incomplete", created.status());

        Checkout updated = h.service().update(created.id(), runningShoesToSeoul());

        assertEquals("ready_for_complete", updated.status());
        assertNull(updated.messages());
        assertEquals(92000L, total(updated, "total"));
        assertEquals(EXPECTED_EXPIRES_AT, updated.expiresAt());
        assertEquals(1, h.repository().findById(created.id()).orElseThrow().version(), "조건부 UPDATE 로 version 이 오른다");
        assertEquals(updated, h.service().get(created.id()), "조회도 새 스냅샷을 준다");
    }

    @Test
    void PUT_에서_단가가_바뀌면_CHECKOUT_PRICE_CHANGED_경고를_얹고_200_이다() {
        Harness h = harness();
        Checkout created = h.service().create(runningShoesToSeoul(), "checkout-create-21");
        h.scenario().set(Scenario.PRICE_CHANGED);

        Checkout updated = h.service().update(created.id(), runningShoesToSeoul());

        assertEquals(List.of("CHECKOUT_PRICE_CHANGED"), codes(updated));
        assertEquals("warning", updated.messages().get(0).severity());
        assertEquals(90000L, updated.lineItems().get(0).item().price(), "새 가격이 담긴다");
        assertEquals("ready_for_complete", updated.status(), "경고는 완료를 막지 않는다. 재승인은 Core 와 Pay 의 몫");
    }

    @Test
    void PUT_은_전체_교체다() {
        Harness h = harness();
        Checkout created = h.service().create(runningShoesToSeoul(), "checkout-create-22");

        Checkout updated = h.service().update(created.id(), request(SEOUL,
                line("product-104", null, 3), line("product-103", "product-103-black", 1)));

        assertEquals(2, updated.lineItems().size());
        assertEquals("product-104", updated.lineItems().get(0).item().id());
        assertEquals("product-103", updated.lineItems().get(1).item().id());
        assertEquals(27000L * 3 + 219000L + 3000L, total(updated, "total"));
    }

    @Test
    void completed_상태에서_PUT_하면_CHECKOUT_NOT_MODIFIABLE_이다() {
        Harness h = harness();
        Checkout created = h.service().create(runningShoesToSeoul(), "checkout-create-23");
        CheckoutSession row = h.repository().findById(created.id()).orElseThrow();
        h.repository().put(new CheckoutSession(row.id(), CheckoutStatus.COMPLETED, row.currency(),
                row.totalAmount(), row.fulfillmentAddressJson(), row.messagesJson(), row.canonicalCheckout(),
                row.expiresAt(), row.version()));

        ApiException thrown = assertThrows(ApiException.class,
                () -> h.service().update(created.id(), runningShoesToSeoul()));

        assertEquals("CHECKOUT_NOT_MODIFIABLE", thrown.errorCode());
        assertEquals(409, thrown.status().value());
    }

    @Test
    void 없는_체크아웃_PUT_은_CHECKOUT_NOT_FOUND_다() {
        ApiException thrown = assertThrows(ApiException.class,
                () -> harness().service().update("nope", runningShoesToSeoul()));

        assertEquals("CHECKOUT_NOT_FOUND", thrown.errorCode());
    }

    @Test
    void 저장된_컬럼_캐시가_스냅샷과_맞는다() {
        Harness h = harness();
        Checkout created = h.service().create(runningShoesToSeoul(), "checkout-create-24");

        CheckoutSession row = h.repository().findById(created.id()).orElseThrow();

        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, row.status());
        assertEquals("KRW", row.currency());
        assertEquals(92000L, row.totalAmount());
        assertNotNull(row.fulfillmentAddressJson());
        assertTrue(row.fulfillmentAddressJson().contains("\"postal_code\":\"06236\""), "배송지도 snake_case 로 저장");
        assertNull(row.messagesJson());
        assertEquals(EXPECTED_EXPIRES_AT,
                row.expiresAt().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        assertTrue(row.canonicalCheckout().contains("\"line_items\""), "스냅샷은 응답 JSON 그대로");
        assertTrue(row.canonicalCheckout().contains("\"expires_at\":\"" + EXPECTED_EXPIRES_AT + "\""));
    }

    @Test
    void 스냅샷_JSON_에는_null_키가_없다() {
        Harness h = harness();
        Checkout created = h.service().create(
                request(null, line("product-101", "product-101-270", 1)), "checkout-create-25");

        String json = h.repository().findById(created.id()).orElseThrow().canonicalCheckout();

        assertTrue(json.contains("\"messages\""));
        assertTrue(!json.contains("\"fulfillment\""), "배송지가 없으면 fulfillment 키 자체가 없다");
        assertTrue(!json.contains("\"expires_at\""));
        assertTrue(!json.contains("null"), "값이 없으면 키를 생략한다(DESIGN.md 17절)");
    }
}
