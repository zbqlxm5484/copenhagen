package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.List;

import com.copenhagen.merchantmock.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
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
    void 빈_검색어는_판매중인_상품을_전부_준다() {
        CatalogMessages.ItemsResponse response =
                service.search(new CatalogMessages.SearchRequest("", null));

        assertEquals(List.of("product-101", "product-102", "product-103", "product-104"), idsOf(response));
    }

    @Test
    void 제목_부분일치로_검색한다() {
        CatalogMessages.ItemsResponse response =
                service.search(new CatalogMessages.SearchRequest("러닝화", null));

        assertEquals(List.of("product-101"), idsOf(response));
    }

    @Test
    void 십만원_이하_러닝화_검색이_시드_상품을_돌려준다() {
        CatalogMessages.ItemsResponse response = service.search(new CatalogMessages.SearchRequest(
                "러닝화", new CatalogMessages.SearchRequest.Filters(100000L, "KRW")));

        assertEquals(1, response.items().size());
        CatalogMessages.Item item = response.items().get(0);
        assertEquals("product-101", item.productId());
        assertEquals(89000L, item.price().amount());
        assertEquals("KRW", item.price().currency());
        assertEquals("IN_STOCK", item.availability());
    }

    @Test
    void 상한가를_넘는_상품은_빠진다() {
        CatalogMessages.ItemsResponse response = service.search(new CatalogMessages.SearchRequest(
                "", new CatalogMessages.SearchRequest.Filters(100000L, null)));

        assertEquals(List.of("product-101", "product-104"), idsOf(response));
    }

    @Test
    void 통화가_다르면_빠진다() {
        CatalogMessages.ItemsResponse response = service.search(new CatalogMessages.SearchRequest(
                "", new CatalogMessages.SearchRequest.Filters(null, "USD")));

        assertTrue(response.items().isEmpty());
    }

    @Test
    void 결과가_없으면_빈_배열이다() {
        CatalogMessages.ItemsResponse response =
                service.search(new CatalogMessages.SearchRequest("없는상품", null));

        assertTrue(response.items().isEmpty());
    }

    @Test
    void 단종_상품은_검색에_안_나온다() {
        CatalogMessages.ItemsResponse response =
                service.search(new CatalogMessages.SearchRequest("백팩", null));

        assertTrue(response.items().isEmpty());
    }

    @Test
    void 모든_옵션이_품절이면_OUT_OF_STOCK이다() {
        CatalogService soldOutService = serviceOf(List.of(CatalogFixture.soldOut()));

        CatalogMessages.ItemsResponse response =
                soldOutService.search(new CatalogMessages.SearchRequest("", null));

        assertEquals("OUT_OF_STOCK", response.items().get(0).availability());
    }

    @Test
    void 일괄조회는_요청한_순서로_준다() {
        CatalogMessages.ItemsResponse response = service.lookup(
                new CatalogMessages.LookupRequest(List.of("product-103", "product-101")));

        assertEquals(List.of("product-103", "product-101"), idsOf(response));
    }

    @Test
    void 일괄조회에서_없는_ID는_조용히_빠진다() {
        CatalogMessages.ItemsResponse response = service.lookup(
                new CatalogMessages.LookupRequest(List.of("product-101", "product-999", "product-105")));

        assertEquals(List.of("product-101"), idsOf(response));
    }

    @Test
    void 일괄조회에_빈_목록을_주면_빈_배열이다() {
        assertTrue(service.lookup(new CatalogMessages.LookupRequest(List.of())).items().isEmpty());
        assertTrue(service.lookup(new CatalogMessages.LookupRequest(null)).items().isEmpty());
        assertTrue(service.lookup(null).items().isEmpty());
    }

    @Test
    void 상세조회는_옵션을_묶어서_준다() {
        CatalogMessages.ProductDetail detail =
                service.product(new CatalogMessages.ProductRequest("product-101"));

        assertEquals("product-101", detail.productId());
        assertEquals(89000L, detail.price().amount());
        assertEquals(1, detail.options().size());
        assertEquals("size", detail.options().get(0).name());
        assertEquals(List.of("260", "270"),
                detail.options().get(0).values().stream().map(CatalogMessages.OptionValue::label).toList());
    }

    @Test
    void 품절_옵션은_상세조회에서_빠진다() {
        CatalogMessages.ProductDetail detail =
                service.product(new CatalogMessages.ProductRequest("product-101"));

        List<String> skus = detail.options().get(0).values().stream()
                .map(CatalogMessages.OptionValue::optionValueId).toList();

        assertFalse(skus.contains("product-101-280"), "stock 이 0 인 SKU 는 노출하지 않는다");
    }

    @Test
    void 옵션이_여러_묶음이면_묶음별로_나눈다() {
        CatalogMessages.ProductDetail detail =
                service.product(new CatalogMessages.ProductRequest("product-103"));

        assertEquals(1, detail.options().size());
        assertEquals("color", detail.options().get(0).name());
        assertEquals(2, detail.options().get(0).values().size());
    }

    @Test
    void 없는_상품_상세조회는_PRODUCT_NOT_FOUND다() {
        ApiException thrown = assertThrows(ApiException.class,
                () -> service.product(new CatalogMessages.ProductRequest("product-999")));

        assertEquals("PRODUCT_NOT_FOUND", thrown.errorCode());
        assertEquals(404, thrown.status().value());
    }

    @Test
    void 단종_상품_상세조회도_PRODUCT_NOT_FOUND다() {
        ApiException thrown = assertThrows(ApiException.class,
                () -> service.product(new CatalogMessages.ProductRequest("product-105")));

        assertEquals("PRODUCT_NOT_FOUND", thrown.errorCode());
    }

    @Test
    void productId가_비면_PRODUCT_NOT_FOUND다() {
        assertThrows(ApiException.class, () -> service.product(new CatalogMessages.ProductRequest("")));
        assertThrows(ApiException.class, () -> service.product(new CatalogMessages.ProductRequest(null)));
        assertThrows(ApiException.class, () -> service.product(null));
    }
}
