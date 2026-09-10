package com.copenhagen.merchantmock.checkout;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.copenhagen.merchantmock.catalog.CatalogFixture;
import com.copenhagen.merchantmock.catalog.CatalogStore;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.CheckoutRequest;
import com.copenhagen.merchantmock.scenario.Scenario;
import com.copenhagen.merchantmock.scenario.ScenarioState;
import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 체크아웃 테스트가 같이 쓰는 조립 코드와 요청 샘플. */
final class CheckoutTestSupport {

    /** 2026-09-10 13:00:00 KST. ready_for_complete 면 expires_at 은 13:05:00 이다. */
    static final Instant NOW = Instant.parse("2026-09-10T04:00:00Z");
    static final String EXPECTED_EXPIRES_AT = "2026-09-10T13:05:00+09:00";

    static final CheckoutRequest.Destination SEOUL = new CheckoutRequest.Destination(
            "홍길동", "06236", "서울특별시 강남구 테헤란로 1", "101호");

    private CheckoutTestSupport() {
    }

    record Harness(CheckoutService service, InMemoryCheckoutRepository repository, ScenarioState scenario,
            ObjectMapper objectMapper) {
    }

    static Harness harness() {
        return harness(Scenario.NORMAL);
    }

    static Harness harness(Scenario initial) {
        CatalogStore catalog = new CatalogStore(CatalogFixture::seed);
        catalog.load();
        ScenarioState scenario = new ScenarioState(initial);
        Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        CheckoutEvaluator evaluator = new CheckoutEvaluator(
                catalog, scenario, MerchantPropertiesFixture.defaults(), clock);
        InMemoryCheckoutRepository repository = new InMemoryCheckoutRepository();
        ObjectMapper objectMapper = JsonMapper.builder().build();
        return new Harness(new CheckoutService(repository, evaluator, objectMapper), repository, scenario,
                objectMapper);
    }

    static CheckoutRequest.LineItemRequest line(String productId, String sku, int quantity) {
        return new CheckoutRequest.LineItemRequest(productId, sku == null ? null : List.of(sku), quantity);
    }

    static CheckoutRequest request(CheckoutRequest.Destination destination,
            CheckoutRequest.LineItemRequest... lines) {
        CheckoutRequest.Fulfillment fulfillment = destination == null
                ? null : new CheckoutRequest.Fulfillment("shipping", destination);
        return new CheckoutRequest(List.of(lines), fulfillment);
    }

    /** 러닝화 270 한 개를 서울로. 조건이 다 차 있어 ready_for_complete 가 나오는 기본 요청. */
    static CheckoutRequest runningShoesToSeoul() {
        return request(SEOUL, line("product-101", "product-101-270", 1));
    }
}
