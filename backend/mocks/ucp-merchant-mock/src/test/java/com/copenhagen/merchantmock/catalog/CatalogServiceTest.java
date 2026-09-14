package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.List;

import com.copenhagen.merchantmock.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Catalog Search·Lookup·Product 단위 테스트. DB 없이 시드 목록만으로 돌린다. */
class CatalogServiceTest {

    private CatalogService service;

    private static CatalogService serviceOf(List<Product> products) {
        CatalogStore store = new CatalogStore(() -> products);
        store.load();
        return new CatalogService(store);
    }

    private static List<String> idsOf(CatalogMessages.ItemsResponse response) {
        List<String> ids = new ArrayList<>();
        response.items().forEach(item -> ids.add(item.productId()));
        return ids;
    }

    @BeforeEach
    void setUp() {
        this.service = serviceOf(CatalogFixture.seed());
    }

    @Test
    @DisplayName("빈 검색어는 판매 중인 상품을 전부 돌려준다")
    void search_blankQuery_returnsAllActiveProducts() {
        // given & when
        CatalogMessages.ItemsResponse response =
                service.search(new CatalogMessages.SearchRequest("", null));

        // then
        assertEquals(List.of("product-101", "product-102", "product-103", "product-104"), idsOf(response));
    }

    @Test
    @DisplayName("제목 부분일치로 검색한다")
    void search_partialTitle_returnsMatchingProducts() {
        // given & when
        CatalogMessages.ItemsResponse response =
                service.search(new CatalogMessages.SearchRequest("러닝화", null));

        // then
        assertEquals(List.of("product-101"), idsOf(response));
    }

    @Test
    @DisplayName("십만원 이하 러닝화 검색이 시드 상품을 돌려준다")
    void search_priceAndCurrencyFilter_returnsSeededProduct() {
        // given & when
        CatalogMessages.ItemsResponse response = service.search(new CatalogMessages.SearchRequest(
                "러닝화", new CatalogMessages.SearchRequest.Filters(100000L, "KRW")));

        // then
        assertEquals(1, response.items().size());
        CatalogMessages.Item item = response.items().get(0);
        assertEquals("product-101", item.productId());
        assertEquals(89000L, item.price().amount());
        assertEquals("KRW", item.price().currency());
        assertEquals("IN_STOCK", item.availability());
    }

    @Test
    @DisplayName("상한가를 넘는 상품은 빠진다")
    void search_maxPriceFilter_excludesExpensiveProducts() {
        // given & when
        CatalogMessages.ItemsResponse response = service.search(new CatalogMessages.SearchRequest(
                "", new CatalogMessages.SearchRequest.Filters(100000L, null)));

        // then
        assertEquals(List.of("product-101", "product-104"), idsOf(response));
    }

    @Test
    @DisplayName("통화가 다르면 빠진다")
    void search_otherCurrency_returnsEmpty() {
        // given & when
        CatalogMessages.ItemsResponse response = service.search(new CatalogMessages.SearchRequest(
                "", new CatalogMessages.SearchRequest.Filters(null, "USD")));

        // then
        assertTrue(response.items().isEmpty());
    }

    @Test
    @DisplayName("결과가 없으면 빈 배열이다")
    void search_noMatch_returnsEmpty() {
        // given & when
        CatalogMessages.ItemsResponse response =
                service.search(new CatalogMessages.SearchRequest("없는상품", null));

        // then
        assertTrue(response.items().isEmpty());
    }

    @Test
    @DisplayName("단종 상품은 검색에 나오지 않는다")
    void search_discontinuedProduct_returnsEmpty() {
        // given & when
        CatalogMessages.ItemsResponse response =
                service.search(new CatalogMessages.SearchRequest("백팩", null));

        // then
        assertTrue(response.items().isEmpty());
    }

    @Test
    @DisplayName("모든 옵션이 품절이면 OUT_OF_STOCK 이다")
    void search_allVariantsSoldOut_marksOutOfStock() {
        // given & when
        CatalogService soldOutService = serviceOf(List.of(CatalogFixture.soldOut()));

        CatalogMessages.ItemsResponse response =
                soldOutService.search(new CatalogMessages.SearchRequest("", null));

        // then
        assertEquals("OUT_OF_STOCK", response.items().get(0).availability());
    }

    @Test
    @DisplayName("일괄조회는 요청한 순서로 돌려준다")
    void lookup_multipleIds_keepsRequestedOrder() {
        // given & when
        CatalogMessages.ItemsResponse response = service.lookup(
                new CatalogMessages.LookupRequest(List.of("product-103", "product-101")));

        // then
        assertEquals(List.of("product-103", "product-101"), idsOf(response));
    }

    @Test
    @DisplayName("일괄조회에서 없는 ID 는 조용히 빠진다")
    void lookup_unknownId_dropsItSilently() {
        // given & when
        CatalogMessages.ItemsResponse response = service.lookup(
                new CatalogMessages.LookupRequest(List.of("product-101", "product-999", "product-105")));

        // then
        assertEquals(List.of("product-101"), idsOf(response));
    }

    @Test
    @DisplayName("일괄조회에 빈 목록을 주면 빈 배열이다")
    void lookup_emptyRequest_returnsEmpty() {
        // when & then
        assertTrue(service.lookup(new CatalogMessages.LookupRequest(List.of())).items().isEmpty());
        assertTrue(service.lookup(new CatalogMessages.LookupRequest(null)).items().isEmpty());
        assertTrue(service.lookup(null).items().isEmpty());
    }

    @Test
    @DisplayName("상세조회는 옵션을 묶어서 돌려준다")
    void product_existingId_groupsOptions() {
        // given & when
        CatalogMessages.ProductDetail detail =
                service.product(new CatalogMessages.ProductRequest("product-101"));

        // then
        assertEquals("product-101", detail.productId());
        assertEquals(89000L, detail.price().amount());
        assertEquals(1, detail.options().size());
        assertEquals("size", detail.options().get(0).name());
        assertEquals(List.of("260", "270"),
                detail.options().get(0).values().stream().map(CatalogMessages.OptionValue::label).toList());
    }

    @Test
    @DisplayName("품절 옵션은 상세조회에서 빠진다")
    void product_soldOutVariant_isHidden() {
        // given & when
        CatalogMessages.ProductDetail detail =
                service.product(new CatalogMessages.ProductRequest("product-101"));

        List<String> skus = detail.options().get(0).values().stream()
                .map(CatalogMessages.OptionValue::optionValueId).toList();

        // then
        assertFalse(skus.contains("product-101-280"), "stock 이 0 인 SKU 는 노출하지 않는다");
    }

    @Test
    @DisplayName("옵션이 여러 묶음이면 묶음별로 나눈다")
    void product_multipleOptionGroups_splitsByName() {
        // given & when
        CatalogMessages.ProductDetail detail =
                service.product(new CatalogMessages.ProductRequest("product-103"));

        // then
        assertEquals(1, detail.options().size());
        assertEquals("color", detail.options().get(0).name());
        assertEquals(2, detail.options().get(0).values().size());
    }

    @Test
    @DisplayName("없는 상품 상세조회는 PRODUCT_NOT_FOUND 다")
    void product_unknownId_throwsProductNotFound() {
        // when & then
        ApiException thrown = assertThrows(ApiException.class,
                () -> service.product(new CatalogMessages.ProductRequest("product-999")));

        assertEquals("PRODUCT_NOT_FOUND", thrown.errorCode());
        assertEquals(404, thrown.status().value());
    }

    @Test
    @DisplayName("단종 상품 상세조회도 PRODUCT_NOT_FOUND 다")
    void product_discontinuedId_throwsProductNotFound() {
        // when & then
        ApiException thrown = assertThrows(ApiException.class,
                () -> service.product(new CatalogMessages.ProductRequest("product-105")));

        assertEquals("PRODUCT_NOT_FOUND", thrown.errorCode());
    }

    @Test
    @DisplayName("productId 가 비면 PRODUCT_NOT_FOUND 다")
    void product_blankId_throwsProductNotFound() {
        // when & then
        assertThrows(ApiException.class, () -> service.product(new CatalogMessages.ProductRequest("")));
        assertThrows(ApiException.class, () -> service.product(new CatalogMessages.ProductRequest(null)));
        assertThrows(ApiException.class, () -> service.product(null));
    }
}
