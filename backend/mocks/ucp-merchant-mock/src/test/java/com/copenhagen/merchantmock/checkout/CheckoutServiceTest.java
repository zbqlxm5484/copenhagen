package com.copenhagen.merchantmock.checkout;

import java.util.List;
import java.util.stream.Collectors;

import com.copenhagen.merchantmock.checkout.CheckoutMessages.Checkout;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Message;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Total;
import com.copenhagen.merchantmock.checkout.CheckoutTestSupport.Harness;
import com.copenhagen.merchantmock.scenario.Scenario;
import com.copenhagen.merchantmock.support.ApiException;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("조건이 다 차면 ready_for_complete 이고 금액을 확정한다")
    void create_allConditionsMet_returnsReadyForCompleteWithTotals() {
        // given & when
        Checkout checkout = harness().service().create(runningShoesToSeoul(), "checkout-create-1");

        // then
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
    @DisplayName("배송지가 없으면 incomplete 와 ADDRESS_REQUIRED 다")
    void create_noAddress_returnsIncompleteWithAddressRequired() {
        // given & when
        Checkout checkout = harness().service().create(
                request(null, line("product-101", "product-101-270", 1)), "checkout-create-2");

        // then
        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("ADDRESS_REQUIRED"), codes(checkout));
        assertEquals("fulfillment.destination", checkout.messages().get(0).target());
        assertNull(checkout.fulfillment(), "배송지가 없으면 fulfillment 키를 생략한다");
        assertEquals(89000L, total(checkout, "total"), "배송비를 모르니 총액은 소계와 같다");
        assertNull(checkout.expiresAt(), "서명 전이라 만료 시각이 없다");
    }

    @Test
    @DisplayName("옵션이 여럿인데 고르지 않으면 incomplete 다")
    void create_noOptionSelectedOnMultiVariant_returnsIncomplete() {
        // given & when
        Checkout checkout = harness().service().create(
                request(SEOUL, line("product-101", null, 1)), "checkout-create-3");

        // then
        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("CHECKOUT_INCOMPLETE"), codes(checkout));
        assertEquals("line_items[0].option_value_ids", checkout.messages().get(0).target());
    }

    @Test
    @DisplayName("옵션이 하나뿐인 상품은 생략해도 된다")
    void create_singleVariantProduct_allowsOmittedOption() {
        // given & when
        Checkout checkout = harness().service().create(
                request(SEOUL, line("product-104", null, 2)), "checkout-create-4");

        // then
        assertEquals("ready_for_complete", checkout.status());
        assertEquals(27000L * 2 + 3000L, total(checkout, "total"));
    }

    @Test
    @DisplayName("옵션 추가금이 단가에 붙는다")
    void create_variantWithPriceDelta_addsDeltaToUnitPrice() {
        // given & when
        Checkout checkout = harness().service().create(
                request(SEOUL, line("product-102", "product-102-xl-black", 1)), "checkout-create-5");

        // then
        assertEquals(134000L, checkout.lineItems().get(0).item().price(), "129000 + XL 추가금 5000");
    }

    @Test
    @DisplayName("상품에 없는 옵션이면 incomplete 다")
    void create_unknownOptionValue_returnsIncomplete() {
        // given & when
        Checkout checkout = harness().service().create(
                request(SEOUL, line("product-101", "product-102-m-black", 1)), "checkout-create-6");

        // then
        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("CHECKOUT_INCOMPLETE"), codes(checkout));
    }

    @Test
    @DisplayName("없는 상품이면 체크아웃을 만들지 않고 404 다")
    void create_unknownProduct_throwsProductNotFound() {
        // given & when
        Harness h = harness();

        // then
        ApiException thrown = assertThrows(ApiException.class, () -> h.service().create(
                request(SEOUL, line("product-999", null, 1)), "checkout-create-7"));

        assertEquals("PRODUCT_NOT_FOUND", thrown.errorCode());
        assertEquals(0, h.repository().size(), "계산서 자체를 만들지 않는다");
    }

    @Test
    @DisplayName("단종 상품도 404 다")
    void create_discontinuedProduct_throwsProductNotFound() {
        // when & then
        ApiException thrown = assertThrows(ApiException.class, () -> harness().service().create(
                request(SEOUL, line("product-105", "product-105-navy", 1)), "checkout-create-8"));

        assertEquals("PRODUCT_NOT_FOUND", thrown.errorCode());
    }

    @Test
    @DisplayName("재고가 0 인 옵션은 CHECKOUT_STOCK_INSUFFICIENT 다")
    void create_zeroStockVariant_returnsStockInsufficient() {
        // given & when
        Checkout checkout = harness().service().create(
                request(SEOUL, line("product-101", "product-101-280", 1)), "checkout-create-9");

        // then
        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("CHECKOUT_STOCK_INSUFFICIENT"), codes(checkout));
    }

    @Test
    @DisplayName("수량이 재고를 넘으면 CHECKOUT_STOCK_INSUFFICIENT 다")
    void create_quantityOverStock_returnsStockInsufficient() {
        // given & when
        Checkout checkout = harness().service().create(
                request(SEOUL, line("product-101", "product-101-270", 8)), "checkout-create-10");

        // then
        assertEquals(List.of("CHECKOUT_STOCK_INSUFFICIENT"), codes(checkout), "270 사이즈 재고는 7");
    }

    @Test
    @DisplayName("수량이 0 이면 incomplete 다")
    void create_zeroQuantity_returnsIncomplete() {
        // given & when
        Checkout checkout = harness().service().create(
                request(SEOUL, line("product-101", "product-101-270", 0)), "checkout-create-11");

        // then
        assertEquals("incomplete", checkout.status());
        assertTrue(codes(checkout).contains("CHECKOUT_INCOMPLETE"));
    }

    @Test
    @DisplayName("시나리오 OUT_OF_STOCK 은 재고가 있어도 품절이다")
    void create_outOfStockScenario_returnsStockInsufficient() {
        // given & when
        Checkout checkout = harness(Scenario.OUT_OF_STOCK).service().create(
                runningShoesToSeoul(), "checkout-create-12");

        // then
        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("CHECKOUT_STOCK_INSUFFICIENT"), codes(checkout));
    }

    @Test
    @DisplayName("시나리오 ADDRESS_NOT_SERVICEABLE 은 배송 불가다")
    void create_addressNotServiceableScenario_marksFulfillmentUnavailable() {
        // given & when
        Checkout checkout = harness(Scenario.ADDRESS_NOT_SERVICEABLE).service().create(
                runningShoesToSeoul(), "checkout-create-13");

        // then
        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("ADDRESS_NOT_SERVICEABLE"), codes(checkout));
        assertEquals("unavailable", checkout.fulfillment().status());
        assertNull(checkout.fulfillment().amount(), "배송 못 하는데 배송비를 매기지 않는다");
        assertEquals(89000L, total(checkout, "total"));
    }

    @Test
    @DisplayName("시나리오 REQUIRES_ESCALATION 은 requires_escalation 이다")
    void create_requiresEscalationScenario_returnsRequiresEscalation() {
        // given & when
        Checkout checkout = harness(Scenario.REQUIRES_ESCALATION).service().create(
                runningShoesToSeoul(), "checkout-create-14");

        // then
        assertEquals("requires_escalation", checkout.status());
        assertEquals(List.of("MERCHANT_HANDOFF_REQUIRED"), codes(checkout));
        assertNull(checkout.expiresAt());
    }

    @Test
    @DisplayName("시나리오 PRICE_CHANGED 는 단가에 1,000원을 더한다")
    void create_priceChangedScenario_addsDeltaToUnitPrice() {
        // given & when
        Checkout checkout = harness(Scenario.PRICE_CHANGED).service().create(
                runningShoesToSeoul(), "checkout-create-15");

        // then
        assertEquals(90000L, checkout.lineItems().get(0).item().price());
        assertEquals("ready_for_complete", checkout.status(), "생성 시점에는 비교 대상이 없어 경고가 없다");
    }

    @Test
    @DisplayName("같은 멱등 키로 다시 만들면 같은 체크아웃을 돌려준다")
    void create_sameIdempotencyKey_returnsExistingCheckout() {
        // given & when
        Harness h = harness();

        Checkout first = h.service().create(runningShoesToSeoul(), "checkout-create-16");
        Checkout second = h.service().create(runningShoesToSeoul(), "checkout-create-16");

        // then
        assertEquals(first.id(), second.id());
        assertEquals(first, second);
        assertEquals(1, h.repository().size());
    }

    @Test
    @DisplayName("멱등 키가 다르면 다른 체크아웃이다")
    void create_differentIdempotencyKey_createsNewCheckout() {
        // given & when
        Harness h = harness();

        Checkout first = h.service().create(runningShoesToSeoul(), "checkout-create-17");
        Checkout second = h.service().create(runningShoesToSeoul(), "checkout-create-18");
        Checkout third = h.service().create(runningShoesToSeoul(), null);

        // then
        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.id(), third.id());
        assertEquals(3, h.repository().size());
    }

    @Test
    @DisplayName("조회는 저장된 스냅샷을 그대로 돌려준다")
    void get_existingCheckout_returnsStoredSnapshot() {
        // given & when
        Harness h = harness();
        Checkout created = h.service().create(runningShoesToSeoul(), "checkout-create-19");

        Checkout fetched = h.service().get(created.id());

        // then
        assertEquals(created, fetched);
    }

    @Test
    @DisplayName("없는 체크아웃 조회는 CHECKOUT_NOT_FOUND 다")
    void get_unknownId_throwsCheckoutNotFound() {
        // when & then
        ApiException thrown = assertThrows(ApiException.class, () -> harness().service().get("nope"));

        assertEquals("CHECKOUT_NOT_FOUND", thrown.errorCode());
        assertEquals(404, thrown.status().value());
    }

    @Test
    @DisplayName("배송지를 PUT 하면 incomplete 가 ready_for_complete 로 간다")
    void update_addressAdded_movesToReadyForComplete() {
        // given & when
        Harness h = harness();
        Checkout created = h.service().create(
                request(null, line("product-101", "product-101-270", 1)), "checkout-create-20");
        // then
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
    @DisplayName("PUT 에서 단가가 바뀌면 CHECKOUT_PRICE_CHANGED 경고를 얹고 200 이다")
    void update_unitPriceChanged_addsPriceChangedWarning() {
        // given & when
        Harness h = harness();
        Checkout created = h.service().create(runningShoesToSeoul(), "checkout-create-21");
        h.scenario().set(Scenario.PRICE_CHANGED);

        Checkout updated = h.service().update(created.id(), runningShoesToSeoul());

        // then
        assertEquals(List.of("CHECKOUT_PRICE_CHANGED"), codes(updated));
        assertEquals("warning", updated.messages().get(0).severity());
        assertEquals(90000L, updated.lineItems().get(0).item().price(), "새 가격이 담긴다");
        assertEquals("ready_for_complete", updated.status(), "경고는 완료를 막지 않는다. 재승인은 Core 와 Pay 의 몫");
    }

    @Test
    @DisplayName("PUT 은 전체 교체다")
    void update_newRequest_replacesEntireCheckout() {
        // given & when
        Harness h = harness();
        Checkout created = h.service().create(runningShoesToSeoul(), "checkout-create-22");

        Checkout updated = h.service().update(created.id(), request(SEOUL,
                line("product-104", null, 3), line("product-103", "product-103-black", 1)));

        // then
        assertEquals(2, updated.lineItems().size());
        assertEquals("product-104", updated.lineItems().get(0).item().id());
        assertEquals("product-103", updated.lineItems().get(1).item().id());
        assertEquals(27000L * 3 + 219000L + 3000L, total(updated, "total"));
    }

    @Test
    @DisplayName("completed 상태에서 PUT 하면 CHECKOUT_NOT_MODIFIABLE 이다")
    void update_completedCheckout_throwsNotModifiable() {
        // given & when
        Harness h = harness();
        Checkout created = h.service().create(runningShoesToSeoul(), "checkout-create-23");
        CheckoutSession row = h.repository().findById(created.id()).orElseThrow();
        h.repository().put(new CheckoutSession(row.id(), CheckoutStatus.COMPLETED, row.currency(),
                row.totalAmount(), row.fulfillmentAddressJson(), row.messagesJson(), row.canonicalCheckout(),
                row.expiresAt(), row.version()));

        // then
        ApiException thrown = assertThrows(ApiException.class,
                () -> h.service().update(created.id(), runningShoesToSeoul()));

        assertEquals("CHECKOUT_NOT_MODIFIABLE", thrown.errorCode());
        assertEquals(409, thrown.status().value());
    }

    @Test
    @DisplayName("없는 체크아웃 PUT 은 CHECKOUT_NOT_FOUND 다")
    void update_unknownId_throwsCheckoutNotFound() {
        // when & then
        ApiException thrown = assertThrows(ApiException.class,
                () -> harness().service().update("nope", runningShoesToSeoul()));

        assertEquals("CHECKOUT_NOT_FOUND", thrown.errorCode());
    }

    @Test
    @DisplayName("저장된 컬럼 캐시가 스냅샷과 맞는다")
    void create_always_keepsColumnCacheInSyncWithSnapshot() {
        // given & when
        Harness h = harness();
        Checkout created = h.service().create(runningShoesToSeoul(), "checkout-create-24");

        CheckoutSession row = h.repository().findById(created.id()).orElseThrow();

        // then
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
    @DisplayName("스냅샷 JSON 에는 null 키가 없다")
    void create_always_omitsNullKeysInSnapshot() {
        // given & when
        Harness h = harness();
        Checkout created = h.service().create(
                request(null, line("product-101", "product-101-270", 1)), "checkout-create-25");

        String json = h.repository().findById(created.id()).orElseThrow().canonicalCheckout();

        // then
        assertTrue(json.contains("\"messages\""));
        assertTrue(!json.contains("\"fulfillment\""), "배송지가 없으면 fulfillment 키 자체가 없다");
        assertTrue(!json.contains("\"expires_at\""));
        assertTrue(!json.contains("null"), "값이 없으면 키를 생략한다(DESIGN.md 17절)");
    }
}
